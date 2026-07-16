package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPackageNormalizerTest {
    private final DocumentPackageNormalizer normalizer = new DocumentPackageNormalizer();
    private final DocumentPackageValidator validator = new DocumentPackageValidator();

    @Test
    void removesBlankBlocksAndResequencesTheRemainingBlocks() {
        var json = JsonNodeFactory.instance;
        var source = new DocumentPackage("1.0", null, null, List.of(), List.of(
                block("empty", 10, "paragraph", json.objectNode().put("text", ""), ""),
                block("content", 30, "paragraph", json.objectNode().put("text", "有效内容"), "有效内容"),
                block("divider", 40, "divider", json.objectNode(), "")
        ), List.of());

        var result = normalizer.normalize(source);

        assertThat(result.documentPackage().blocks())
                .extracting(DocumentPackage.BlockInfo::blockKey)
                .containsExactly("content", "divider");
        assertThat(result.documentPackage().blocks())
                .extracting(DocumentPackage.BlockInfo::seq)
                .containsExactly(1, 2);
        assertThat(result.issues())
                .extracting(ImportIssueDto::issueCode)
                .containsExactly("EMPTY_CONTENT_BLOCK_REMOVED");
    }

    @Test
    void validatorRejectsAnEmptyBlockWhenAClientBypassesNormalization() {
        var json = JsonNodeFactory.instance;
        var documentPackage = new DocumentPackage("1.0",
                new DocumentPackage.DocumentInfo("document", "标题", null, "zh-CN", List.of()),
                new DocumentPackage.VersionInfo("v1", "MANUAL", null, null, null, java.util.Map.of()),
                List.of(new DocumentPackage.SectionInfo("section", null, 1, "SECTION", null, "标题", 1, "title", null, null, null, null)),
                List.of(block("empty", 1, "paragraph", json.objectNode().put("text", ""), "")),
                List.of());

        assertThat(validator.validate(documentPackage))
                .extracting(ImportIssueDto::issueCode)
                .contains("EMPTY_CONTENT_BLOCK");
    }

    private static DocumentPackage.BlockInfo block(String key, int seq, String type, com.fasterxml.jackson.databind.JsonNode payload, String plainText) {
        return new DocumentPackage.BlockInfo(key, "section", seq, type, payload, plainText, null, null, null, null, null);
    }
}