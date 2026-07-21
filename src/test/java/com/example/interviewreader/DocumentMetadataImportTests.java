package com.example.interviewreader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentMetadataImportTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void documentMetadataUpdatesImmediatelyAndRejectsStaleRevision() throws Exception {
        var imported = importAndCommit(packageBytes(unique("metadata"), "原始标题", "第一版", "Java"));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/admin/documents/{documentId}/metadata", imported.documentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metadataRevision":0,"title":"  新标题  ","description":"  新描述  ","tags":["Java","java","并发"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.description").value("新描述"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.metadataRevision").value(1));

        mockMvc.perform(get("/api/reader/documents/{documentId}", imported.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.description").value("新描述"));

        mockMvc.perform(patch("/api/admin/documents/{documentId}/metadata", imported.documentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metadataRevision":0,"title":"过期写入","description":null,"tags":[]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METADATA_REVISION_CONFLICT"));
    }

    @Test
    void importingIntoExistingDocumentNeverOverwritesDocumentMetadata() throws Exception {
        var imported = importAndCommit(packageBytes(unique("existing"), "稳定标题", "第一版", "Java"));
        var replacement = packageBytes(unique("source-key"), "来源标题", "第二版", "Redis");
        var job = upload(replacement, "replacement.json", imported.documentId());

        mockMvc.perform(patch("/api/admin/import-jobs/{jobId}/document-metadata", job)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"禁止修改\",\"description\":null,\"tags\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_TARGET_METADATA_READ_ONLY"));

        mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", job))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value(imported.documentId().toString()));

        mockMvc.perform(get("/api/admin/documents/{documentId}/metadata", imported.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("稳定标题"))
                .andExpect(jsonPath("$.description").value("第一版"))
                .andExpect(jsonPath("$.tags[0]").value("Java"));
    }

    @Test
    void codeConflictRequiresExplicitResolutionAndCanCreateSuffixedDocument() throws Exception {
        var code = unique("conflict");
        var first = importAndCommit(packageBytes(code, "第一份", "第一版", "Java"));
        var secondJob = upload(packageBytes(code, "第二份", "第二版", "Redis"), "second.json", null);

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/document-metadata", secondJob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchingDocument.id").value(first.documentId().toString()))
                .andExpect(jsonPath("$.suggestedDocumentKey").value(code + "-2"));

        mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", secondJob))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CODE_CONFLICT"));

        var created = json(mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", secondJob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"CREATE_NEW\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        var createdDocumentId = UUID.fromString(created.get("documentId").asText());
        assertThat(createdDocumentId).isNotEqualTo(first.documentId());
        mockMvc.perform(get("/api/admin/documents/{documentId}/metadata", createdDocumentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code + "-2"))
                .andExpect(jsonPath("$.title").value("第二份"));
    }

    @Test
    void codeConflictCanExplicitlyImportAsExistingDocumentVersionWithoutMetadataChanges() throws Exception {
        var code = unique("match");
        var first = importAndCommit(packageBytes(code, "保留标题", "第一版", "Java"));
        var secondJob = upload(packageBytes(code, "不应覆盖", "第二版", "Redis"), "second-version.json", null);

        mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", secondJob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"IMPORT_AS_NEW_VERSION\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value(first.documentId().toString()))
                .andExpect(jsonPath("$.versionNo").value(2));

        mockMvc.perform(get("/api/admin/documents/{documentId}/metadata", first.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("保留标题"))
                .andExpect(jsonPath("$.tags[0]").value("Java"));
    }

    @Test
    void newImportMetadataCanBeRevisedBeforeCommitAndDuplicateTitlesRemainAllowed() throws Exception {
        var sharedTitle = "允许重名 " + UUID.randomUUID();
        importAndCommit(packageBytes(unique("duplicate-a"), sharedTitle, "A", "Java"));
        var job = upload(packageBytes(unique("duplicate-b"), "待修改", "B", "Redis"), "editable.json", null);

        mockMvc.perform(patch("/api/admin/import-jobs/{jobId}/document-metadata", job)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(java.util.Map.of(
                                "title", sharedTitle,
                                "description", "修改后的描述",
                                "tags", java.util.List.of("Vue", "vue", "前端")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(sharedTitle))
                .andExpect(jsonPath("$.tags.length()").value(2));

        var committed = json(mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", job))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(get("/api/admin/documents/{documentId}/metadata", committed.get("documentId").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(sharedTitle))
                .andExpect(jsonPath("$.duplicateTitleCount").value(1));
    }

    @Test
    void markdownUsesFileNameForStableCodeAndFallsBackToFileNameForMissingHeading() throws Exception {
        var withoutHeading = upload("正文内容".getBytes(StandardCharsets.UTF_8), "redis-notes.md", null);
        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/document-metadata", withoutHeading))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentKey").value("redis-notes"))
                .andExpect(jsonPath("$.title").value("redis-notes"));

        var withHeading = upload("# 展示标题\n\n正文".getBytes(StandardCharsets.UTF_8), "stable-file-name.md", null);
        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/document-metadata", withHeading))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentKey").value("stable-file-name"))
                .andExpect(jsonPath("$.title").value("展示标题"));
    }

    private ImportResult importAndCommit(byte[] bytes) throws Exception {
        var jobId = upload(bytes, "document.json", null);
        var committed = json(mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", jobId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return new ImportResult(
                jobId,
                UUID.fromString(committed.get("documentId").asText()),
                UUID.fromString(committed.get("id").asText()));
    }

    private UUID upload(byte[] bytes, String fileName, UUID targetDocumentId) throws Exception {
        var contentType = fileName.endsWith(".md") ? "text/markdown" : MediaType.APPLICATION_JSON_VALUE;
        var request = multipart("/api/admin/import-jobs")
                .file(new MockMultipartFile("file", fileName, contentType, bytes));
        if (targetDocumentId != null) {
            request.param("targetDocumentId", targetDocumentId.toString());
        }
        var response = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json(response).get("id").asText());
    }

    private byte[] packageBytes(String code, String title, String description, String tag) throws Exception {
        var root = (ObjectNode) objectMapper.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("docs/import/examples/document-package.example.json")));
        var document = (ObjectNode) root.get("document");
        document.put("documentKey", code);
        document.put("title", title);
        document.put("description", description);
        var tags = (ArrayNode) document.withArray("tags");
        tags.removeAll();
        tags.add(tag);
        ((ObjectNode) root.get("blocks").get(0)).put("plainText", description + "-" + UUID.randomUUID());
        return objectMapper.writeValueAsBytes(root);
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record ImportResult(UUID jobId, UUID documentId, UUID versionId) {
    }
}