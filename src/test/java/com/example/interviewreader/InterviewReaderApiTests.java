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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @Test
    void jsonPackageCanBeImportedCommittedAndRead() throws Exception {
        var imported = importAndCommit(Files.readAllBytes(Path.of("docs/examples/document-package.example.json")));

        mockMvc.perform(get("/api/import-jobs/{jobId}/issues", imported.jobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Java 高级开发面试题完整答案"));

        var tocBody = mockMvc.perform(get("/api/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].children[0].title").value("1.1 结论先行"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var childNodeId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());

        mockMvc.perform(get("/api/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), childNodeId)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].blockKey").value("q1-conclusion-p1"))
                .andExpect(jsonPath("$.blocks[0].payload.text").value("HashMap 的设计目标是单线程下提供高效查询与更新，它没有为复合操作、结构修改和内存可见性提供并发保证。"));

        mockMvc.perform(get("/api/search").param("q", "HashMap"))
                .andExpect(status().isOk())
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
        mockMvc.perform(put("/api/reading-progress/{documentId}", imported.documentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progress))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(get("/api/reading-progress/{documentId}", imported.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressRatio").value(0.42));
    }

    @Test
    void publishingNewVersionMigratesReadingProgressByStableBlockKey() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "progress-migration-" + UUID.randomUUID());

        var first = importAndCommit(objectMapper.writeValueAsBytes(source));
        mockMvc.perform(post("/api/documents/{documentId}/versions/{versionId}/publish", first.documentId(), first.versionId()))
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
        mockMvc.perform(put("/api/reading-progress/{documentId}", first.documentId())
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
        mockMvc.perform(post("/api/documents/{documentId}/versions/{versionId}/publish", second.documentId(), second.versionId()))
                .andExpect(status().isNoContent());
        var secondPosition = firstChildFirstBlock(second.versionId());

        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(secondPosition.blockId()).isNotEqualTo(firstPosition.blockId());

        mockMvc.perform(get("/api/reading-progress/{documentId}", first.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(second.versionId().toString()))
                .andExpect(jsonPath("$.sectionId").value(secondPosition.sectionId().toString()))
                .andExpect(jsonPath("$.blockId").value(secondPosition.blockId().toString()))
                .andExpect(jsonPath("$.charOffset").value(7))
                .andExpect(jsonPath("$.blockViewportOffset").value(24))
                .andExpect(jsonPath("$.progressRatio").value(0.37))
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

        var repeatedCommitBody = mockMvc.perform(post("/api/import-jobs/{jobId}/commit", first.jobId()))
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
        var tocBody = mockMvc.perform(get("/api/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].children[0].title").value("1.1 结论先行"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var conclusionNodeId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());

        mockMvc.perform(get("/api/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), conclusionNodeId)
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
        mockMvc.perform(post("/api/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        var tocBody = mockMvc.perform(get("/api/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value(title))
                .andExpect(jsonPath("$[0].children[0].title").value("1. HashMap 为什么线程不安全？"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var questionNodeId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());

        mockMvc.perform(get("/api/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), questionNodeId)
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
            var rawExtraction = getJson("/api/import-jobs/{jobId}/raw-extraction", imported.jobId());
            assertThat(rawExtraction.get("schemaVersion").asText()).isEqualTo("1.0");
            assertThat(rawExtraction.get("sourceFileName").asText()).isEqualTo(sample.getFileName().toString());
            assertThat(rawExtraction.get("classification").asText()).isEqualTo("TEXT_OUTLINE");
            assertThat(rawExtraction.get("pageCount").asInt()).isPositive();
            assertThat(rawExtraction.get("outlineCount").asInt()).isPositive();
            assertThat(rawExtraction.get("outline").size()).isEqualTo(rawExtraction.get("outlineCount").asInt());
            assertThat(rawExtraction.get("pages").size()).isEqualTo(rawExtraction.get("pageCount").asInt());
            assertThat(rawExtraction.get("pages").get(0).get("charCount").asInt()).isPositive();

            var normalized = getJson("/api/import-jobs/{jobId}/normalized-package", imported.jobId());
            assertThat(normalized.get("version").get("sourceType").asText()).isEqualTo("PDF");
            assertThat(normalized.get("sections").size()).isPositive();
            assertThat(normalized.get("blocks").size()).isPositive();
            assertThat(normalized.get("version").get("metadata").get("pageCount").asInt())
                    .isEqualTo(rawExtraction.get("pageCount").asInt());

            mockMvc.perform(post("/api/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                    .andExpect(status().isNoContent());

            var tocBody = mockMvc.perform(get("/api/versions/{versionId}/toc", imported.versionId()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var toc = objectMapper.readTree(tocBody);
            assertThat(toc).isNotEmpty();

            var readablePosition = firstReadablePosition(imported.versionId(), toc);
            assertThat(readablePosition.blockId()).isNotNull();

            var exported = exportJsonPackage(imported);
            assertThat(exported.get("version").get("sourceType").asText()).isEqualTo("PDF");
            assertThat(exported.get("sections").size()).isPositive();
            assertThat(exported.get("blocks").size()).isPositive();
        }
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
        mockMvc.perform(post("/api/documents/{documentId}/versions/{versionId}/publish", imported.documentId(), imported.versionId()))
                .andExpect(status().isNoContent());

        var tocBody = mockMvc.perform(get("/api/versions/{versionId}/toc", imported.versionId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var toc = objectMapper.readTree(tocBody);
        var sectionId = UUID.fromString(toc.get(0).get("children").get(0).get("id").asText());
        var contentBody = mockMvc.perform(get("/api/versions/{versionId}/nodes/{nodeId}/content", imported.versionId(), sectionId))
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
        var bookmarkBody = mockMvc.perform(post("/api/bookmarks")
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
        mockMvc.perform(post("/api/bookmarks")
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
        mockMvc.perform(post("/api/notes")
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
        mockMvc.perform(put("/api/review-states/{nodeId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mastery").value("FUZZY"))
                .andExpect(jsonPath("$.intervalDays").value(3))
                .andExpect(jsonPath("$.repetitions").value(1));

        mockMvc.perform(put("/api/review-states/{nodeId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewRequest.replace("FUZZY", "KNOWN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mastery").value("KNOWN"))
                .andExpect(jsonPath("$.intervalDays").value(7))
                .andExpect(jsonPath("$.repetitions").value(2));

        mockMvc.perform(delete("/api/bookmarks/{bookmarkId}", bookmarkId))
                .andExpect(status().isNoContent());
    }

    @Test
    void reviewStateRejectsUnknownMastery() throws Exception {
        var source = (ObjectNode) objectMapper.readTree(Files.readString(Path.of("docs/examples/document-package.example.json")));
        ((ObjectNode) source.get("document")).put("documentKey", "review-invalid-" + UUID.randomUUID());
        var imported = importAndCommit(objectMapper.writeValueAsBytes(source));
        var tocBody = mockMvc.perform(get("/api/versions/{versionId}/toc", imported.versionId()))
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

        mockMvc.perform(put("/api/review-states/{nodeId}", sectionId)
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

        mockMvc.perform(get("/api/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.issueCode == 'PARENT_SECTION_MISSING' && @.cellRef == 'Sections!D3')]").exists());
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

        var body = mockMvc.perform(multipart("/api/import-jobs")
                        .file(upload)
                        .param("sourceType", "JSON_PACKAGE"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var jobId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/import-jobs/{jobId}/issues", jobId))
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

        var body = mockMvc.perform(multipart("/api/import-jobs")
                        .file(upload)
                        .param("sourceType", "JSON_PACKAGE"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var jobId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/import-jobs/{jobId}/issues", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.issueCode == 'PARENT_SECTION_MISSING')]").exists());
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

        var versionBody = mockMvc.perform(post("/api/import-jobs/{jobId}/commit", jobId))
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

        var jobBody = mockMvc.perform(multipart("/api/import-jobs")
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
        var body = mockMvc.perform(post("/api/exports")
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
        return mockMvc.perform(post("/api/exports")
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
        return mockMvc.perform(post("/api/exports")
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
        return mockMvc.perform(post("/api/exports")
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

    private ContentPosition firstChildFirstBlock(UUID versionId) throws Exception {
        var tocBody = mockMvc.perform(get("/api/versions/{versionId}/toc", versionId))
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
            var contentBody = mockMvc.perform(get("/api/versions/{versionId}/nodes/{nodeId}/content", versionId, nodeId)
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

    private record ContentPosition(UUID sectionId, UUID blockId) {
    }

    private record ImportResult(UUID jobId, UUID documentId, UUID versionId) {
    }
}
