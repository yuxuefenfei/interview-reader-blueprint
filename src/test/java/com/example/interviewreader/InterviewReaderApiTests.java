package com.example.interviewreader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterviewReaderApiTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void pdfBoxFontCacheDefaultsToBuildDirectory() {
        var fontCache = System.getProperty("pdfbox.fontcache");

        assertThat(fontCache).contains("pdfbox-font-cache");
        assertThat(Files.isDirectory(Path.of(fontCache))).isTrue();
    }

    @Test
    void h2MigrationKeepsTableAndColumnShapeInStepWithMysql() throws Exception {
        var h2Columns = tableColumns(Path.of("src/main/resources/db/migration/h2/V1__initial_schema.sql"));
        var mysqlColumns = tableColumns(Path.of("src/main/resources/db/migration/mysql/V1__initial_schema.sql"));

        assertThat(h2Columns.keySet()).containsExactlyElementsOf(mysqlColumns.keySet());
        assertThat(h2Columns).containsExactlyEntriesOf(mysqlColumns);
    }

    @Test
    void jsonPackageCanBeImportedCommittedAndRead() throws Exception {
        var imported = importAndCommit(Files.readAllBytes(Path.of("docs/examples/document-package.example.json")));

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/issues", imported.jobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        var sourceFile = mockMvc.perform(get("/api/admin/import-jobs/{jobId}/source-file", imported.jobId()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains(MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sourceFile).contains("\"schemaVersion\"");

        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/reader/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Java 高级开发面试题完整答案"));

        var tocResult = mockMvc.perform(get("/api/reader/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].children[0].title").value("1.1 结论先行"))
                .andReturn()
                .getResponse();
        var tocEtag = tocResult.getHeader(HttpHeaders.ETAG);
        assertThat(tocEtag).isNotBlank();
        mockMvc.perform(get("/api/reader/versions/{versionId}/toc", imported.versionId())
                        .header(HttpHeaders.IF_NONE_MATCH, tocEtag))
                .andExpect(status().isNotModified());
        var tocBody = tocResult.getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var childNodeId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());

        var contentResult = mockMvc.perform(get("/api/reader/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), childNodeId)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].blockKey").value("q1-conclusion-p1"))
                .andExpect(jsonPath("$.blocks[0].payload.text").value("HashMap 的设计目标是单线程下提供高效查询与更新，它没有为复合操作、结构修改和内存可见性提供并发保证。"))
                .andReturn()
                .getResponse();
        var contentEtag = contentResult.getHeader(HttpHeaders.ETAG);
        assertThat(contentEtag).isNotBlank();
        mockMvc.perform(get("/api/reader/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), childNodeId)
                        .param("limit", "1")
                        .header(HttpHeaders.IF_NONE_MATCH, contentEtag))
                .andExpect(status().isNotModified());

        mockMvc.perform(get("/api/reader/search").param("q", "HashMap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("1.1 结论先行"));
        mockMvc.perform(get("/api/reader/search")
                        .param("q", "1.1 结论先行")
                        .param("documentId", imported.documentId().toString())
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentId").value(imported.documentId().toString()))
                .andExpect(jsonPath("$[0].title").value("1.1 结论先行"));

        var progress = """
                {
                  "versionId": "%s",
                  "sectionId": "%s",
                  "charOffset": 3,
                  "blockViewportOffset": 64,
                  "progressRatio": 0.42,
                  "deviceId": "test"
                }
                """.formatted(imported.versionId(), childNodeId);
        mockMvc.perform(put("/api/reader/reading-progress/{documentId}", imported.documentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progress))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(get("/api/reader/reading-progress/{documentId}", imported.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressRatio").value(0.42));
    }

    @Test
    void searchFiltersUnpublishedMatchesBeforeApplyingLimit() throws Exception {
        var marker = "published-search-" + UUID.randomUUID();
        var imports = new java.util.ArrayList<ImportResult>();
        for (var index = 0; index < 6; index++) {
            var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
            ((ObjectNode) source.get("document")).put("documentKey", marker + "-key-" + index);
            ((ObjectNode) source.get("document")).put("title", marker + " title " + index);
            ((ObjectNode) source.get("version")).put("versionKey", marker + "-version-" + index);
            var block = (ObjectNode) source.get("blocks").get(0);
            block.put("plainText", marker);
            ((ObjectNode) block.get("payload")).put("text", marker);
            imports.add(importAndCommit(objectMapper.writeValueAsBytes(source)));
        }
        var published = imports.stream()
                .max(java.util.Comparator.comparing(result -> result.versionId().toString()))
                .orElseThrow();
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish",
                        published.documentId(), published.versionId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/reader/search")
                        .param("q", marker)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentId").value(published.documentId().toString()));
    }

    @Test
    void readingProgressRejectsMissingRatioAndCrossDocumentPositions() throws Exception {
        var firstSource = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) firstSource.get("document")).put("documentKey", "progress-first-" + UUID.randomUUID());
        ((ObjectNode) firstSource.get("version")).put("versionKey", "progress-first-version");
        var first = importAndCommit(objectMapper.writeValueAsBytes(firstSource));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish",
                        first.documentId(), first.versionId()))
                .andExpect(status().isNoContent());

        var secondSource = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) secondSource.get("document")).put("documentKey", "progress-other-" + UUID.randomUUID());
        ((ObjectNode) secondSource.get("version")).put("versionKey", "progress-other-version");
        var second = importAndCommit(objectMapper.writeValueAsBytes(secondSource));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish",
                        second.documentId(), second.versionId()))
                .andExpect(status().isNoContent());

        var missingRatio = """
                {
                  "versionId": "%s",
                  "charOffset": 0,
                  "blockViewportOffset": 0,
                  "revision": 0
                }
                """.formatted(first.versionId());
        mockMvc.perform(put("/api/reader/reading-progress/{documentId}", first.documentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingRatio))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var crossDocument = """
                {
                  "versionId": "%s",
                  "charOffset": 0,
                  "blockViewportOffset": 0,
                  "progressRatio": 0.1,
                  "revision": 0
                }
                """.formatted(second.versionId());
        mockMvc.perform(put("/api/reader/reading-progress/{documentId}", first.documentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossDocument))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_VERSION_INVALID"));
    }

    @Test
    void documentLibraryUsesDatabaseCursorPagination() throws Exception {
        var marker = "cursor-page-" + UUID.randomUUID();
        for (var index = 0; index < 3; index++) {
            var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
            ((ObjectNode) source.get("document")).put("documentKey", marker + "-key-" + index);
            ((ObjectNode) source.get("document")).put("title", marker + " title " + index);
            ((ObjectNode) source.get("version")).put("versionKey", "cursor-" + index);
            var imported = importAndCommit(objectMapper.writeValueAsBytes(source));
            mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                    .andExpect(status().isNoContent());
        }

        var first = objectMapper.readTree(mockMvc.perform(get("/api/reader/documents")
                        .param("query", marker)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var cursor = first.get("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        var second = objectMapper.readTree(mockMvc.perform(get("/api/reader/documents")
                        .param("query", marker)
                        .param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(first.get("items").findValuesAsText("id"))
                .doesNotContain(second.get("items").get(0).get("id").asText());

        mockMvc.perform(get("/api/reader/documents").param("cursor", "not-a-valid-cursor"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importJobRecordsWorkerStageAndStatistics() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "worker-stage-" + UUID.randomUUID());

        var job = uploadJsonPackage(objectMapper.writeValueAsBytes(source));

        assertThat(job.get("status").asText()).isEqualTo("READY");
        assertThat(job.get("currentStage").asText()).isEqualTo("READY");
        assertThat(job.get("progress").asInt()).isEqualTo(100);
        assertThat(job.get("statistics").get("workerMode").asText()).isEqualTo("INLINE");
        assertThat(job.get("statistics").get("sourceFileName").asText()).isEqualTo("document-package.json");
        assertThat(job.get("statistics").get("sectionCount").asInt()).isPositive();
        assertThat(job.get("statistics").get("blockCount").asInt()).isPositive();
    }

    @Test
    void uploadedSourceFileNameIsStoredAsSafeBaseName() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "safe-filename-" + UUID.randomUUID());

        var job = uploadPackage(objectMapper.writeValueAsBytes(source), "JSON_PACKAGE", "..\\..\\evil.json");
        var jobId = UUID.fromString(job.get("id").asText());
        var objectKey = jdbc.queryForObject("SELECT source_object_key FROM import_job WHERE id = ?", String.class, jobId);

        assertThat(job.get("statistics").get("sourceFileName").asText()).isEqualTo("evil.json");
        assertThat(objectKey).doesNotContain("..").endsWith("/evil.json");

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/source-file", jobId))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                        .contains("evil.json")
                        .doesNotContain(".."));
    }

    @Test
    void activeImportJobCanBeCanceled() throws Exception {
        var jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO import_job(
                    id, owner_id, source_type, source_object_key, source_sha256, converter_version,
                    import_fingerprint, status, progress, current_stage, statistics, started_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                jobId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "PDF",
                "manual/test.pdf",
                "a".repeat(64),
                "test-converter",
                "b".repeat(64),
                "EXTRACTING",
                35,
                "EXTRACTING",
                "{}");

        mockMvc.perform(post("/api/admin/import-jobs/{jobId}/cancel", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.currentStage").value("CANCELED"))
                .andExpect(jsonPath("$.progress").value(100));
    }

    @Test
    void completedImportJobCannotBeCanceled() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "cancel-ready-" + UUID.randomUUID());
        var job = uploadJsonPackage(objectMapper.writeValueAsBytes(source));

        mockMvc.perform(post("/api/admin/import-jobs/{jobId}/cancel", UUID.fromString(job.get("id").asText())))
                .andExpect(status().isConflict());
    }

    @Test
    void publishingNewVersionResetsReadingProgressWhenBlockIdentityChanges() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "progress-migration-" + UUID.randomUUID());

        var first = importAndCommit(objectMapper.writeValueAsBytes(source));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", first.documentId(), first.versionId()))
                .andExpect(status().isNoContent());
        var firstPosition = firstChildFirstBlock(first.versionId());

        var progress = """
                {
                  "versionId": "%s",
                  "sectionId": "%s",
                  "blockId": "%s",
                  "charOffset": 7,
                  "blockViewportOffset": 24,
                  "progressRatio": 0.37,
                  "deviceId": "migration-test"
                }
                """.formatted(first.versionId(), firstPosition.sectionId(), firstPosition.blockId());
        mockMvc.perform(put("/api/reader/reading-progress/{documentId}", first.documentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progress))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        var changed = (ObjectNode) source.deepCopy();
        ((ObjectNode) changed.get("document")).put("title", "Java 高级开发面试题完整答案 v2");
        ((ObjectNode) changed.get("version")).put("versionKey", "v2");
        var firstBlock = (ObjectNode) changed.get("blocks").get(0);
        ((ObjectNode) firstBlock.get("payload")).put("text", "HashMap 的设计目标没有变化，但新版补充了迁移锚点验证。");
        firstBlock.put("plainText", "HashMap 的设计目标没有变化，但新版补充了迁移锚点验证。");

        var second = importAndCommit(objectMapper.writeValueAsBytes(changed));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", second.documentId(), second.versionId()))
                .andExpect(status().isNoContent());
        var secondPosition = firstChildFirstBlock(second.versionId());

        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(secondPosition.blockId()).isNotEqualTo(firstPosition.blockId());

        mockMvc.perform(get("/api/reader/reading-progress/{documentId}", first.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(second.versionId().toString()))
                .andExpect(jsonPath("$.sectionId").doesNotExist())
                .andExpect(jsonPath("$.blockId").doesNotExist())
                .andExpect(jsonPath("$.charOffset").value(0))
                .andExpect(jsonPath("$.blockViewportOffset").value(0))
                .andExpect(jsonPath("$.progressRatio").value(0.0))
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void exportedJsonPackageCanBeImportedAgainWithStableHashes() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "roundtrip-" + UUID.randomUUID());
        var sections = (ArrayNode) source.get("sections");
        ((ObjectNode) sections.get(0)).put("contentHash", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        ((ObjectNode) sections.get(1)).put("contentHash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        var blocks = (ArrayNode) source.get("blocks");
        ((ObjectNode) blocks.get(0)).put("contentHash", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");

        var first = importAndCommit(objectMapper.writeValueAsBytes(source));
        var firstExport = exportJsonPackage(first);
        var second = importAndCommit(objectMapper.writeValueAsBytes(firstExport));
        var secondExport = exportJsonPackage(second);

        assertThat(hashesByKey(firstExport.get("sections"), "sectionKey"))
                .isEqualTo(hashesByKey(secondExport.get("sections"), "sectionKey"));
        assertThat(hashesByKey(firstExport.get("blocks"), "blockKey"))
                .isEqualTo(hashesByKey(secondExport.get("blocks"), "blockKey"));
    }

    @Test
    void repeatedUploadAndCommitReuseExistingImportResult() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "idempotent-" + UUID.randomUUID());
        var bytes = objectMapper.writeValueAsBytes(source);

        var first = importAndCommit(bytes);
        var repeatedJob = uploadJsonPackage(bytes);
        assertThat(repeatedJob.get("id").asText()).isEqualTo(first.jobId().toString());
        assertThat(repeatedJob.get("status").asText()).isEqualTo("IMPORTED");

        var repeatedCommitBody = mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", first.jobId()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var repeatedCommit = objectMapper.readTree(repeatedCommitBody);
        assertThat(repeatedCommit.get("id").asText()).isEqualTo(first.versionId().toString());
    }

    @Test
    void excelTemplateCanBeImportedAndPreservesCodeWhitespace() throws Exception {
        var imported = importAndCommitExcel(Files.readAllBytes(Path.of("docs/templates/interview-reader-import-template.xlsx")));
        publish(imported);
        var tocBody = mockMvc.perform(get("/api/reader/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].children[0].title").value("1.1 结论先行"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var conclusionNodeId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());

        mockMvc.perform(get("/api/reader/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), conclusionNodeId)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[1].blockKey").value("q1-code1"))
                .andExpect(jsonPath("$.blocks[1].payload.text").value("if (!map.containsKey(key)) {\n    map.put(key, value);\n}"));
    }

    @Test
    void markdownCanBeImportedCommittedAndRead() throws Exception {
        var title = "Markdown 导入 " + UUID.randomUUID();
        var markdown = """
                # %s

                ## 1. HashMap 为什么线程不安全？

                结论先行：HashMap 不保证并发安全。

                - 结构修改可能破坏链表或树结构
                - 可见性没有保证

                ```java
                if (a < b) {
                    return a;
                }
                ```

                | 概念 | 说明 |
                | --- | --- |
                | HashMap | 非线程安全 |
                """.formatted(title);

        var imported = importAndCommitMarkdown(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        var tocBody = mockMvc.perform(get("/api/reader/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value(title))
                .andExpect(jsonPath("$[0].children[0].title").value("1. HashMap 为什么线程不安全？"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var questionNodeId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());

        mockMvc.perform(get("/api/reader/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), questionNodeId)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].blockType").value("paragraph"))
                .andExpect(jsonPath("$.blocks[0].payload.text").value("结论先行：HashMap 不保证并发安全。"))
                .andExpect(jsonPath("$.blocks[1].blockType").value("unordered_list"))
                .andExpect(jsonPath("$.blocks[1].payload.items[0]").value("结构修改可能破坏链表或树结构"))
                .andExpect(jsonPath("$.blocks[2].blockType").value("code"))
                .andExpect(jsonPath("$.blocks[2].payload.text").value("if (a < b) {\n    return a;\n}"))
                .andExpect(jsonPath("$.blocks[3].blockType").value("table"))
                .andExpect(jsonPath("$.blocks[3].payload.rows[0][0]").value("HashMap"));

        var exported = exportJsonPackage(imported);
        assertThat(exported.get("version").get("sourceType").asText()).isEqualTo("MARKDOWN");
    }

    @Test
    void pdfSamplesCanBeImportedCommittedAndRead() throws Exception {
        var samples = List.of(
                Path.of("docs/Java高级开发面试题完整答案.pdf"),
                Path.of("docs/Redis高级面试题完整答案.pdf"),
                Path.of("docs/MySQL与PostgreSQL高级面试题完整答案.pdf"),
                Path.of("docs/Elasticsearch搜索引擎高级面试题完整答案.pdf"));

        for (var sample : samples) {
            var imported = importAndCommitPdf(Files.readAllBytes(sample), sample.getFileName().toString());
            var sourceBytes = mockMvc.perform(get("/api/admin/import-jobs/{jobId}/source-file", imported.jobId()))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertThat(result.getResponse().getContentType()).contains(MediaType.APPLICATION_PDF_VALUE))
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();
            assertThat(new String(sourceBytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

            var rawExtraction = getJson("/api/admin/import-jobs/{jobId}/raw-extraction", imported.jobId());
            assertThat(rawExtraction.get("schemaVersion").asText()).isEqualTo("1.0");
            assertThat(rawExtraction.get("preflight").get("mimeType").asText()).isEqualTo("application/pdf");
            assertThat(rawExtraction.get("preflight").get("pageCount").asInt()).isPositive();
            assertThat(rawExtraction.get("preflight").get("outlineMaxDepth").asInt()).isPositive();
            assertThat(rawExtraction.get("preflight").get("textPageCount").asInt()).isPositive();
            assertThat(rawExtraction.get("preflight").get("pageSizes").size()).isPositive();
            assertThat(rawExtraction.get("preflight").get("fontSummary").size()).isPositive();
            assertThat(rawExtraction.get("preflight").get("fontSummary").get(0).get("fontName").asText()).isNotBlank();
            assertThat(rawExtraction.get("preflight").get("fontSummary").get(0).get("fontSize").asDouble()).isPositive();
            assertThat(rawExtraction.get("preflight").get("fontSummary").get(0).get("charCount").asInt()).isPositive();
            assertThat(rawExtraction.get("sourceFileName").asText()).isEqualTo(sample.getFileName().toString());
            assertThat(rawExtraction.get("classification").asText()).isEqualTo("TEXT_OUTLINE");
            assertThat(rawExtraction.get("pageCount").asInt()).isPositive();
            assertThat(rawExtraction.get("outlineCount").asInt()).isPositive();
            assertThat(rawExtraction.get("outline").size()).isEqualTo(rawExtraction.get("outlineCount").asInt());
            assertThat(rawExtraction.get("pages").size()).isEqualTo(rawExtraction.get("pageCount").asInt());
            assertThat(rawExtraction.get("pages").get(0).get("charCount").asInt()).isPositive();
            assertThat(rawExtraction.get("pages").get(0).get("width").asDouble()).isPositive();
            assertThat(rawExtraction.get("pages").get(0).get("height").asDouble()).isPositive();
            assertThat(rawExtraction.get("pages").get(0).has("rotation")).isTrue();
            assertThat(rawExtraction.get("pages").get(0).has("blockCount")).isTrue();
            assertThat(rawExtraction.get("pages").get(0).has("coveredByBlocks")).isTrue();

            var normalized = getJson("/api/admin/import-jobs/{jobId}/normalized-package", imported.jobId());
            assertThat(normalized.get("version").get("sourceType").asText()).isEqualTo("PDF");
            assertThat(normalized.get("sections").size()).isPositive();
            assertThat(normalized.get("blocks").size()).isPositive();
            assertThat(normalized.get("blocks").get(0).get("sourcePage").asInt()).isPositive();
            assertThat(normalized.get("blocks").get(0).get("sourceBbox").get("page").asInt()).isPositive();
            assertThat(normalized.get("blocks").get(0).get("sourceBbox").get("width").asDouble()).isPositive();
            assertThat(normalized.get("version").get("metadata").get("pageCount").asInt())
                    .isEqualTo(rawExtraction.get("pageCount").asInt());
            assertThat(normalized.get("version").get("metadata").get("uncoveredTextPageCount").asInt())
                    .isEqualTo(rawExtraction.get("preflight").get("uncoveredTextPageCount").asInt());

            mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                    .andExpect(status().isNoContent());

            var tocBody = mockMvc.perform(get("/api/reader/versions/{versionId}/toc", imported.versionId()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var toc = objectMapper.readTree(tocBody);
            assertThat(toc).isNotEmpty();

            var readablePosition = firstReadablePosition(imported.versionId(), toc);
            assertThat(readablePosition.blockId()).isNotNull();
            var readerContent = getJson(
                    "/api/reader/versions/{versionId}/nodes/{nodeId}/content",
                    imported.versionId(),
                    readablePosition.sectionId());
            assertThat(readerContent.get("blocks").get(0).get("sourceBbox").get("page").asInt()).isPositive();
            assertThat(readerContent.get("blocks").get(0).get("sourceBbox").get("width").asDouble()).isPositive();

            var exported = exportJsonPackage(imported);
            assertThat(exported.get("version").get("sourceType").asText()).isEqualTo("PDF");
            assertThat(exported.get("sections").size()).isPositive();
            assertThat(exported.get("blocks").size()).isPositive();
        }
    }

    @Test
    void samePagePdfBookmarksAssignEachBlockToExactlyOneSection() throws Exception {
        var job = uploadPackage(generatedSamePageOutlinePdf(), "PDF", "same-page-outline.pdf");
        assertThat(job.get("status").asText()).isEqualTo("READY");

        var normalized = getJson("/api/admin/import-jobs/{jobId}/normalized-package", UUID.fromString(job.get("id").asText()));
        var firstSection = findByField(normalized.get("sections"), "title", "9.1 Index terminology");
        var secondSection = findByField(normalized.get("sections"), "title", "9.2 Prefix rule");
        var firstKey = firstSection.get("sectionKey").asText();
        var secondKey = secondSection.get("sectionKey").asText();
        var firstText = new StringBuilder();
        var secondText = new StringBuilder();
        var firstHashes = new java.util.HashSet<String>();
        var secondHashes = new java.util.HashSet<String>();
        for (var block : normalized.get("blocks")) {
            if (firstKey.equals(block.get("sectionKey").asText())) {
                firstText.append(block.get("plainText").asText()).append('\n');
                firstHashes.add(block.get("contentHash").asText());
            }
            if (secondKey.equals(block.get("sectionKey").asText())) {
                secondText.append(block.get("plainText").asText()).append('\n');
                secondHashes.add(block.get("contentHash").asText());
            }
        }

        assertThat(firstText).contains("Term content").doesNotContain("Prefix content");
        assertThat(secondText).contains("Prefix content").doesNotContain("Term content");
        assertThat(firstHashes).doesNotContainAnyElementsOf(secondHashes);
    }
    @Test
    void generatedPdfRecognizesListAndCodeBlocks() throws Exception {
        var job = uploadPackage(generatedSemanticPdf(), "PDF", "semantic-blocks.pdf");
        assertThat(job.get("status").asText()).isEqualTo("READY");
        var normalized = getJson("/api/admin/import-jobs/{jobId}/normalized-package", UUID.fromString(job.get("id").asText()));
        var blockTypes = normalized.get("blocks").findValuesAsText("blockType");
        assertThat(blockTypes).contains("unordered_list", "code");

        var listBlock = findByField(normalized.get("blocks"), "blockType", "unordered_list");
        assertThat(listBlock.get("payload").get("items").get(0).asText()).contains("structural modification");
        var codeBlock = findByField(normalized.get("blocks"), "blockType", "code");
        assertThat(codeBlock.get("payload").get("language").asText()).isEqualTo("java");
        assertThat(codeBlock.get("payload").get("text").asText()).contains("if (a < b)");
        assertThat(codeBlock.get("payload").get("text").asText()).contains("}\n@Transactional");
        assertThat(codeBlock.get("payload").get("text").asText()).contains("public void save() {");
    }

    @Test
    void generatedPdfFlagsPossibleTablesAsReviewSnapshots() throws Exception {
        var job = uploadPackage(generatedTableSnapshotPdf(), "PDF", "table-snapshot.pdf");
        assertThat(job.get("status").asText()).isEqualTo("READY");
        var jobId = UUID.fromString(job.get("id").asText());

        var normalized = getJson("/api/admin/import-jobs/{jobId}/normalized-package", jobId);
        var snapshot = findByField(normalized.get("blocks"), "blockType", "table_snapshot");
        assertThat(snapshot.get("confidence").decimalValue()).isLessThan(new java.math.BigDecimal("0.50"));
        assertThat(snapshot.get("payload").get("lines").get(0).asText()).contains("Metric | Value | Risk");
        assertThat(snapshot.get("plainText").asText()).contains("Threads | Many | Race");

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.issueCode == 'PDF_TABLE_REVIEW_REQUIRED' && @.severity == 'WARNING')]").exists());
    }

    @Test
    void exportedExcelCanBeImportedAgainWithStableHashes() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "excel-roundtrip-" + UUID.randomUUID());
        var sections = (ArrayNode) source.get("sections");
        ((ObjectNode) sections.get(0)).put("contentHash", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        ((ObjectNode) sections.get(1)).put("contentHash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        var blocks = (ArrayNode) source.get("blocks");
        ((ObjectNode) blocks.get(0)).put("contentHash", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");

        var first = importAndCommit(objectMapper.writeValueAsBytes(source));
        var exportedExcel = exportExcelPackage(first);
        var second = importAndCommitExcel(exportedExcel);
        var secondExport = exportJsonPackage(second);

        assertThat(hashesByKey(source.get("sections"), "sectionKey"))
                .isEqualTo(hashesByKey(secondExport.get("sections"), "sectionKey"));
        assertThat(hashesByKey(source.get("blocks"), "blockKey"))
                .isEqualTo(hashesByKey(secondExport.get("blocks"), "blockKey"));
    }

    @Test
    void markdownExportRendersHeadingsCodeAndTables() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "markdown-export-" + UUID.randomUUID());
        var blocks = (ArrayNode) source.get("blocks");
        blocks.add(objectMapper.readTree("""
                {
                  "blockKey": "q1-conclusion-code",
                  "sectionKey": "q1-conclusion",
                  "seq": 2,
                  "blockType": "code",
                  "payload": {
                    "language": "java",
                    "text": "if (!map.containsKey(key)) {\\n    map.put(key, value);\\n}"
                  },
                  "plainText": "if (!map.containsKey(key)) {\\n    map.put(key, value);\\n}",
                  "language": "java",
                  "confidence": 0.99
                }
                """));
        blocks.add(objectMapper.readTree("""
                {
                  "blockKey": "q1-conclusion-table",
                  "sectionKey": "q1-conclusion",
                  "seq": 3,
                  "blockType": "table",
                  "payload": {
                    "columns": ["概念", "说明"],
                    "rows": [
                      ["HashMap", "非线程安全"],
                      ["ConcurrentHashMap", "并发容器"]
                    ]
                  },
                  "plainText": "HashMap 非线程安全",
                  "confidence": 0.98
                }
                """));

        var imported = importAndCommit(objectMapper.writeValueAsBytes(source));
        var markdown = exportMarkdownPackage(imported);

        assertThat(markdown)
                .contains("# Java 高级开发面试题完整答案")
                .contains("## 1. HashMap 为什么线程不安全？")
                .contains("### 1.1 结论先行")
                .contains("```java\nif (!map.containsKey(key)) {\n    map.put(key, value);\n}\n```")
                .contains("| 概念 | 说明 |")
                .contains("| HashMap | 非线程安全 |");
    }

    @Test
    void staticHtmlExportEscapesTextAndRendersSemanticBlocks() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "static-html-export-" + UUID.randomUUID());
        var blocks = (ArrayNode) source.get("blocks");
        ((ObjectNode) blocks.get(0).get("payload")).put("text", "安全文本 <script>alert('x')</script>");
        ((ObjectNode) blocks.get(0)).put("plainText", "安全文本 <script>alert('x')</script>");
        blocks.add(objectMapper.readTree("""
                {
                  "blockKey": "q1-conclusion-code-html",
                  "sectionKey": "q1-conclusion",
                  "seq": 2,
                  "blockType": "code",
                  "payload": {
                    "language": "java",
                    "text": "if (a < b) {\\n    return a;\\n}"
                  },
                  "plainText": "if (a < b) {\\n    return a;\\n}",
                  "language": "java",
                  "confidence": 0.99
                }
                """));
        blocks.add(objectMapper.readTree("""
                {
                  "blockKey": "q1-conclusion-table-html",
                  "sectionKey": "q1-conclusion",
                  "seq": 3,
                  "blockType": "table",
                  "payload": {
                    "columns": ["概念", "说明"],
                    "rows": [["HashMap", "非线程安全"]]
                  },
                  "plainText": "HashMap 非线程安全",
                  "confidence": 0.98
                }
                """));

        var imported = importAndCommit(objectMapper.writeValueAsBytes(source));
        var html = exportStaticHtmlPackage(imported);

        assertThat(html)
                .contains("<!doctype html>")
                .contains("<h1>Java 高级开发面试题完整答案</h1>")
                .contains("<section id=\"hashmap-thread-safety-conclusion\">")
                .contains("安全文本 &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
                .doesNotContain("<script>alert")
                .contains("<code class=\"language-java\">if (a &lt; b) {\n    return a;\n}</code>")
                .contains("<table>")
                .contains("<th>概念</th>")
                .contains("<td>HashMap</td>");
    }

    @Test
    void bookmarkNoteAndReviewStateCanBeSavedForPublishedContent() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "interactions-" + UUID.randomUUID());
        var imported = importAndCommit(objectMapper.writeValueAsBytes(source));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        var tocBody = mockMvc.perform(get("/api/reader/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var sectionId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());
        var contentBody = mockMvc.perform(get("/api/reader/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), sectionId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var blockId = UUID.fromString(objectMapper.readTree(contentBody).get("blocks").get(0).get("id").asText());

        var bookmarkRequest = """
                {
                  "documentId": "%s",
                  "versionId": "%s",
                  "sectionId": "%s",
                  "blockId": "%s",
                  "title": "重点收藏"
                }
                """.formatted(imported.documentId(), imported.versionId(), sectionId, blockId);
        var bookmarkBody = mockMvc.perform(post("/api/reader/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookmarkRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("重点收藏"))
                .andExpect(jsonPath("$.blockId").value(blockId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var bookmarkId = objectMapper.readTree(bookmarkBody).get("id").asText();

        var repeatedBookmark = bookmarkRequest.replace("重点收藏", "更新后的收藏");
        mockMvc.perform(post("/api/reader/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(repeatedBookmark))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(bookmarkId))
                .andExpect(jsonPath("$.title").value("更新后的收藏"));

        var noteRequest = """
                {
                  "documentId": "%s",
                  "versionId": "%s",
                  "sectionId": "%s",
                  "blockId": "%s",
                  "selectedText": "HashMap",
                  "body": "复习并发修改风险"
                }
                """.formatted(imported.documentId(), imported.versionId(), sectionId, blockId);
        mockMvc.perform(post("/api/reader/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noteRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.selectedText").value("HashMap"))
                .andExpect(jsonPath("$.body").value("复习并发修改风险"));

        var reviewRequest = """
                {
                  "documentId": "%s",
                  "mastery": "FUZZY"
                }
                """.formatted(imported.documentId());
        mockMvc.perform(put("/api/reader/review-states/{nodeId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mastery").value("FUZZY"))
                .andExpect(jsonPath("$.intervalDays").value(3))
                .andExpect(jsonPath("$.repetitions").value(1));

        mockMvc.perform(put("/api/reader/review-states/{nodeId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewRequest.replace("FUZZY", "KNOWN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mastery").value("KNOWN"))
                .andExpect(jsonPath("$.intervalDays").value(7))
                .andExpect(jsonPath("$.repetitions").value(2));

        mockMvc.perform(delete("/api/reader/bookmarks/{bookmarkId}", bookmarkId))
                .andExpect(status().isNoContent());
    }

    @Test
    void reviewQueueReturnsRandomQuestionsAndCanFilterDueOnly() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "review-queue-" + UUID.randomUUID());
        var imported = importAndCommit(objectMapper.writeValueAsBytes(source));
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        var dueQueueBody = mockMvc.perform(get("/api/reader/review-queue")
                        .param("documentId", imported.documentId().toString())
                        .param("limit", "5")
                        .param("dueOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("1. HashMap 为什么线程不安全？"))
                .andExpect(jsonPath("$[0].mastery").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].sectionPath[0]").value("1. HashMap 为什么线程不安全？"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        var dueQueue = objectMapper.readTree(dueQueueBody);
        var questionId = UUID.fromString(dueQueue.get(0).get("nodeId").asText());

        var hardReviewRequest = """
                {
                  "documentId": "%s",
                  "mastery": "HARD"
                }
                """.formatted(imported.documentId());
        mockMvc.perform(put("/api/reader/review-states/{nodeId}", questionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hardReviewRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mastery").value("HARD"))
                .andExpect(jsonPath("$.intervalDays").value(1));

        mockMvc.perform(get("/api/reader/review-queue")
                        .param("documentId", imported.documentId().toString())
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value(questionId.toString()))
                .andExpect(jsonPath("$[0].mastery").value("HARD"))
                .andExpect(jsonPath("$[0].repetitions").value(1));

        mockMvc.perform(get("/api/reader/review-queue")
                        .param("documentId", imported.documentId().toString())
                        .param("dueOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reviewStateRejectsUnknownMastery() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "review-invalid-" + UUID.randomUUID());
        var imported = importAndCommit(objectMapper.writeValueAsBytes(source));
        publish(imported);
        var tocBody = mockMvc.perform(get("/api/reader/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var sectionId = UUID.fromString(objectMapper.readTree(tocBody).get(0).get("children").get(0).get("id").asText());
        var reviewRequest = """
                {
                  "documentId": "%s",
                  "mastery": "MAYBE"
                }
                """.formatted(imported.documentId());

        mockMvc.perform(put("/api/reader/review-states/{nodeId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mastery must be UNKNOWN, HARD, FUZZY or KNOWN"));
    }

    @Test
    void excelParentSectionErrorReportsCellReference() throws Exception {
        var brokenWorkbook = withCellValue(
                Files.readAllBytes(Path.of("docs/templates/interview-reader-import-template.xlsx")),
                "Sections",
                2,
                3,
                "missing-parent");

        var job = uploadPackage(brokenWorkbook, "EXCEL", "broken-template.xlsx");
        assertThat(job.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        var jobId = UUID.fromString(job.get("id").asText());

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.issueCode == 'PARENT_SECTION_MISSING' && @.cellRef == 'Sections!D3')]").exists());
    }

    @Test
    void legacyXlsUploadIsRejectedInsteadOfCreatingAnUnprocessableJob() throws Exception {
        var file = new MockMultipartFile(
                "file",
                "legacy.xls",
                "application/vnd.ms-excel",
                "legacy-binary-workbook".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/admin/import-jobs").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_SOURCE_FILE"));
    }

    @Test
    void excelUploadRejectsNonZipContentBeforeParsing() throws Exception {
        var job = uploadPackage("not an xlsx".getBytes(StandardCharsets.UTF_8), "EXCEL", "fake.xlsx");
        assertThat(job.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        var jobId = UUID.fromString(job.get("id").asText());

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.issueCode == 'EXCEL_MAGIC_INVALID' && @.severity == 'BLOCKING')]").exists());
    }

    @Test
    void duplicateSectionKeyIsReportedAsBlockingIssue() throws Exception {
        var root = (JsonNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        var mutable = (ObjectNode) root.deepCopy();
        var sections = (ArrayNode) mutable.get("sections");
        sections.add(sections.get(0).deepCopy());

        var upload = new MockMultipartFile(
                "file",
                "invalid.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(mutable));

        var body = mockMvc.perform(multipart("/api/admin/import-jobs")
                        .file(upload)
                        .param("sourceType", "JSON_PACKAGE"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var jobId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].issueCode").value("SECTION_KEY_DUPLICATE"));
    }

    @Test
    void orphanParentAndLevelJumpAreReportedAsBlockingIssues() throws Exception {
        var root = (JsonNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        var mutable = (ObjectNode) root.deepCopy();
        var sections = (ArrayNode) mutable.get("sections");
        ((ObjectNode) sections.get(1)).put("parentSectionKey", "missing-parent");
        ((ObjectNode) sections.get(1)).put("level", 4);

        var upload = new MockMultipartFile(
                "file",
                "invalid-parent.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(mutable));

        var body = mockMvc.perform(multipart("/api/admin/import-jobs")
                        .file(upload)
                        .param("sourceType", "JSON_PACKAGE"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var jobId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.issueCode == 'PARENT_SECTION_MISSING')]").exists());
    }

    @Test
    void stagedPackageCanBeRevisedBeforeCommit() throws Exception {
        var root = (JsonNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        var mutable = (ObjectNode) root.deepCopy();
        ((ObjectNode) mutable.get("document")).put("documentKey", "review-revision-" + UUID.randomUUID());
        var sections = (ArrayNode) mutable.get("sections");
        ((ObjectNode) sections.get(1)).put("parentSectionKey", "missing-parent");
        ((ObjectNode) sections.get(1)).put("level", 4);

        var upload = new MockMultipartFile(
                "file",
                "review-required.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(mutable));
        var body = mockMvc.perform(multipart("/api/admin/import-jobs")
                        .file(upload)
                        .param("sourceType", "JSON_PACKAGE"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var jobId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        var revision = """
                {
                  "parentSectionKey": "q1",
                  "level": 2,
                  "title": "1.1 人工修订后的结论"
                }
                """;
        mockMvc.perform(patch("/api/admin/import-jobs/{jobId}/normalized-package/sections/{sectionKey}", jobId, "q1-conclusion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[1].title").value("1.1 人工修订后的结论"));

        mockMvc.perform(get("/api/admin/import-jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.currentStage").value("REVIEWING"));
        mockMvc.perform(get("/api/admin/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        var versionBody = mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", jobId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var version = objectMapper.readTree(versionBody);
        var versionId = UUID.fromString(version.get("id").asText());
        var documentId = UUID.fromString(version.get("documentId").asText());
        publish(new ImportResult(jobId, documentId, versionId));

        mockMvc.perform(get("/api/reader/versions/{versionId}/toc", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].children[0].title").value("1.1 人工修订后的结论"));
    }

    @Test
    void apiHealthMatchesFrontendContract() throws Exception {
        var body = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("UP");
    }

    private void publish(ImportResult imported) throws Exception {
        mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());
    }
    @Test
    void revisionSummaryKeepsSourceVersionNumberWhenParentIsDeleted() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "revision-parent-" + UUID.randomUUID());
        var imported = importAndCommit(objectMapper.writeValueAsBytes(source));

        var revision = objectMapper.readTree(mockMvc.perform(post("/api/admin/documents/{documentId}/versions/{versionId}/revisions", imported.documentId(), imported.versionId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.parentVersionId").value(imported.versionId().toString()))
                .andExpect(jsonPath("$.parentVersionNo").value(1))
                .andReturn().getResponse().getContentAsString());
        var revisionId = UUID.fromString(revision.get("id").asText());

        mockMvc.perform(delete("/api/admin/versions/{versionId}/editor", imported.versionId()))
                .andExpect(status().isNoContent());

        var versions = objectMapper.readTree(mockMvc.perform(get("/api/admin/documents/{documentId}/versions", imported.documentId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(versions.size()).isEqualTo(1);
        assertThat(versions.get(0).get("id").asText()).isEqualTo(revisionId.toString());
        assertThat(versions.get(0).get("parentVersionId").isNull()).isTrue();
        assertThat(versions.get(0).get("parentVersionNo").asInt()).isEqualTo(1);
    }

    @Test
    void editorUsesLightweightSnapshotAndDiscardReleasesImportJobReference() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "editor-snapshot-" + UUID.randomUUID());
        var job = uploadPackage(objectMapper.writeValueAsBytes(source), "PDF", "document-package.json");
        var jobId = UUID.fromString(job.get("id").asText());
        assertThat(job.get("sourceType").asText()).isEqualTo("JSON_PACKAGE");

        var version = objectMapper.readTree(mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", jobId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        var versionId = UUID.fromString(version.get("id").asText());

        var snapshot = objectMapper.readTree(mockMvc.perform(get("/api/admin/versions/{versionId}/editor", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentPackage").doesNotExist())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andReturn().getResponse().getContentAsString());
        var childId = UUID.fromString(snapshot.get("nodes").get(1).get("id").asText());

        var blockPage = objectMapper.readTree(mockMvc.perform(get("/api/admin/versions/{versionId}/editor/nodes/{nodeId}/blocks", versionId, childId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString());
        var blockId = UUID.fromString(blockPage.get("items").get(0).get("id").asText());

        var nodeUpdate = """
                { "draftRevision": 0, "title": "已修订的结论", "nodeType": "SECTION", "semanticRole": "CONCLUSION", "anchor": "updated-conclusion" }
                """;
        mockMvc.perform(patch("/api/admin/versions/{versionId}/editor/nodes/{nodeId}", versionId, childId)
                        .contentType(MediaType.APPLICATION_JSON).content(nodeUpdate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.draftRevision").value(1))
                .andExpect(jsonPath("$.nodes[1].title").value("已修订的结论"));

        var staleBlockUpdate = """
                { "draftRevision": 0, "blockType": "paragraph", "payload": { "text": "过期写入" }, "plainText": "过期写入", "language": null }
                """;
        mockMvc.perform(patch("/api/admin/versions/{versionId}/editor/blocks/{blockId}", versionId, blockId)
                        .contentType(MediaType.APPLICATION_JSON).content(staleBlockUpdate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_REVISION_CONFLICT"));

        var blockUpdate = """
                { "draftRevision": 1, "blockType": "paragraph", "payload": { "text": "可检索的新内容" }, "plainText": "可检索的新内容", "language": null }
                """;
        mockMvc.perform(patch("/api/admin/versions/{versionId}/editor/blocks/{blockId}", versionId, blockId)
                        .contentType(MediaType.APPLICATION_JSON).content(blockUpdate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockType").value("paragraph"))
                .andExpect(jsonPath("$.plainText").value("可检索的新内容"));

        var createBlock = """
                { "draftRevision": 2, "blockType": "paragraph", "payload": { "text": "人工补录正文" }, "plainText": "人工补录正文", "language": null }
                """;
        mockMvc.perform(post("/api/admin/versions/{versionId}/editor/nodes/{nodeId}/blocks", versionId, childId)
                        .contentType(MediaType.APPLICATION_JSON).content(createBlock))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(11))
                .andExpect(jsonPath("$.plainText").value("人工补录正文"));

        mockMvc.perform(delete("/api/admin/versions/{versionId}/editor", versionId))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/import-jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.currentStage").value("DRAFT_DISCARDED"));
        mockMvc.perform(get("/api/admin/versions/{versionId}/editor", versionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only draft versions can be edited"));
    }
    private ImportResult importAndCommit(byte[] jsonPackage) throws Exception {
        var job = uploadJsonPackage(jsonPackage);
        return commitReadyJob(job);
    }

    private ImportResult importAndCommitExcel(byte[] workbook) throws Exception {
        var job = uploadPackage(workbook, "EXCEL", "document-package.xlsx");
        return commitReadyJob(job);
    }

    private ImportResult importAndCommitMarkdown(byte[] markdown) throws Exception {
        var job = uploadPackage(markdown, "MARKDOWN", "document.md");
        return commitReadyJob(job);
    }

    private ImportResult importAndCommitPdf(byte[] pdf, String fileName) throws Exception {
        var job = uploadPackage(pdf, "PDF", fileName);
        return commitReadyJob(job);
    }

    private ImportResult commitReadyJob(JsonNode job) throws Exception {
        assertThat(job.get("status").asText()).isEqualTo("READY");
        var jobId = UUID.fromString(job.get("id").asText());

        var versionBody = mockMvc.perform(post("/api/admin/import-jobs/{jobId}/commit", jobId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var version = objectMapper.readTree(versionBody);
        return new ImportResult(
                jobId,
                UUID.fromString(version.get("documentId").asText()),
                UUID.fromString(version.get("id").asText()));
    }

    private JsonNode uploadJsonPackage(byte[] jsonPackage) throws Exception {
        return uploadPackage(jsonPackage, "JSON_PACKAGE", "document-package.json");
    }

    private JsonNode getJson(String urlTemplate, Object... uriVars) throws Exception {
        var body = mockMvc.perform(get(urlTemplate, uriVars))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private JsonNode uploadPackage(byte[] bytes, String sourceType, String fileName) throws Exception {
        var upload = new MockMultipartFile(
                "file",
                fileName,
                contentType(fileName),
                bytes);

        var jobBody = mockMvc.perform(multipart("/api/admin/import-jobs")
                        .file(upload)
                        .param("sourceType", sourceType))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(jobBody);
    }

    private String contentType(String fileName) {
        if (fileName.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (fileName.endsWith(".md")) {
            return "text/markdown";
        }
        if (fileName.endsWith(".pdf")) {
            return "application/pdf";
        }
        return MediaType.APPLICATION_JSON_VALUE;
    }

    private JsonNode exportJsonPackage(ImportResult imported) throws Exception {
        var request = """
                {
                  "documentId": "%s",
                  "versionId": "%s",
                  "format": "JSON_PACKAGE"
                }
                """.formatted(imported.documentId(), imported.versionId());
        var body = mockMvc.perform(post("/api/admin/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private byte[] exportExcelPackage(ImportResult imported) throws Exception {
        var request = """
                {
                  "documentId": "%s",
                  "versionId": "%s",
                  "format": "EXCEL"
                }
                """.formatted(imported.documentId(), imported.versionId());
        return mockMvc.perform(post("/api/admin/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("spreadsheetml.sheet"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    private String exportMarkdownPackage(ImportResult imported) throws Exception {
        var request = """
                {
                  "documentId": "%s",
                  "versionId": "%s",
                  "format": "MARKDOWN"
                }
                """.formatted(imported.documentId(), imported.versionId());
        return mockMvc.perform(post("/api/admin/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("text/markdown"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String exportStaticHtmlPackage(ImportResult imported) throws Exception {
        var request = """
                {
                  "documentId": "%s",
                  "versionId": "%s",
                  "format": "STATIC_HTML"
                }
                """.formatted(imported.documentId(), imported.versionId());
        return mockMvc.perform(post("/api/admin/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("text/html"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private byte[] withCellValue(byte[] workbookBytes, String sheetName, int rowIndex, int columnIndex, String value) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes));
             var out = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet(sheetName);
            var row = sheet.getRow(rowIndex);
            var cell = row.getCell(columnIndex);
            if (cell == null) {
                cell = row.createCell(columnIndex);
            }
            cell.setCellValue(value);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] generatedSemanticPdf() throws Exception {
        try (var document = new PDDocument();
             var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16);
                content.newLineAtOffset(72, 720);
                for (var line : List.of(
                        "Generated interview notes",
                        "- structural modification can break hash bins",
                        "- visibility is not guaranteed",
                        "if (a < b) {",
                        "    return a;",
                        "}",
                        "@Transactional",
                        "public void save() {",
                        "    return;",
                        "}")) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] generatedSamePageOutlinePdf() throws Exception {
        try (var document = new PDDocument();
             var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            var outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            addOutlineItem(outline, "9.1 Index terminology", page);
            addOutlineItem(outline, "9.2 Prefix rule", page);
            outline.openNode();
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(18);
                content.newLineAtOffset(72, 720);
                for (var line : List.of(
                        "9.1 Index terminology",
                        "Term content is only for the first section.",
                        "9.2 Prefix rule",
                        "Prefix content is only for the second section.")) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private void addOutlineItem(PDDocumentOutline outline, String title, PDPage page) {
        var item = new PDOutlineItem();
        item.setTitle(title);
        item.setDestination(page);
        outline.addLast(item);
    }
    private byte[] generatedTableSnapshotPdf() throws Exception {
        try (var document = new PDDocument();
             var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16);
                content.newLineAtOffset(72, 720);
                for (var line : List.of(
                        "Generated table notes",
                        "Metric | Value | Risk",
                        "Threads | Many | Race",
                        "This paragraph follows the snapshot.")) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private Map<String, String> hashesByKey(JsonNode array, String keyField) {
        return array.findValuesAsText(keyField).stream()
                .collect(Collectors.toMap(
                        key -> key,
                        key -> findByKey(array, keyField, key).get("contentHash").asText()));
    }

    private JsonNode findByKey(JsonNode array, String keyField, String key) {
        for (var node : array) {
            if (key.equals(node.get(keyField).asText())) {
                return node;
            }
        }
        throw new AssertionError("Missing key " + key);
    }

    private JsonNode findByField(JsonNode array, String field, String value) {
        for (var node : array) {
            if (value.equals(node.get(field).asText())) {
                return node;
            }
        }
        throw new AssertionError("Missing " + field + " " + value);
    }

    private ContentPosition firstChildFirstBlock(UUID versionId) throws Exception {
        var tocBody = mockMvc.perform(get("/api/reader/versions/{versionId}/toc", versionId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        return firstReadablePosition(versionId, toc);
    }

    private ContentPosition firstReadablePosition(UUID versionId, JsonNode toc) throws Exception {
        var nodeIds = new java.util.ArrayList<UUID>();
        collectNodeIds(toc, nodeIds);
        for (var nodeId : nodeIds) {
            var contentBody = mockMvc.perform(get("/api/reader/versions/{versionId}/nodes/{nodeId}/content", versionId, nodeId)
                            .param("limit", "1"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var content = objectMapper.readTree(contentBody);
            if (content.get("blocks").size() > 0) {
                var blockId = UUID.fromString(content.get("blocks").get(0).get("id").asText());
                return new ContentPosition(nodeId, blockId);
            }
        }
        throw new AssertionError("No readable content block found");
    }

    private void collectNodeIds(JsonNode nodes, List<UUID> nodeIds) {
        for (var node : nodes) {
            nodeIds.add(UUID.fromString(node.get("id").asText()));
            collectNodeIds(node.get("children"), nodeIds);
        }
    }

    private Map<String, List<String>> tableColumns(Path migration) throws Exception {
        var result = new LinkedHashMap<String, List<String>>();
        var lines = Files.readAllLines(migration);
        String tableName = null;
        var columns = new java.util.ArrayList<String>();

        for (var line : lines) {
            var trimmed = line.strip();
            if (trimmed.startsWith("CREATE TABLE ")) {
                tableName = trimmed.substring("CREATE TABLE ".length(), trimmed.indexOf('(')).strip();
                columns = new java.util.ArrayList<>();
                continue;
            }
            if (tableName == null) {
                continue;
            }
            if (trimmed.startsWith(");")) {
                result.put(tableName, List.copyOf(columns));
                tableName = null;
                continue;
            }
            var firstToken = firstToken(trimmed);
            if (!firstToken.isBlank() && isColumnDefinition(firstToken)) {
                columns.add(firstToken);
            }
        }
        return result;
    }

    private boolean isColumnDefinition(String firstToken) {
        var normalized = firstToken.toUpperCase();
        return !firstToken.startsWith("(")
                && !Set.of("CONSTRAINT", "PRIMARY", "FOREIGN", "CHECK").contains(normalized)
                && !normalized.startsWith("UNIQUE");
    }

    private String firstToken(String line) {
        if (line.isBlank()) {
            return "";
        }
        var withoutComma = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
        var space = withoutComma.indexOf(' ');
        return space < 0 ? withoutComma : withoutComma.substring(0, space);
    }

    private record ContentPosition(UUID sectionId, UUID blockId) {
    }

    private record ImportResult(UUID jobId, UUID documentId, UUID versionId) {
    }
}
