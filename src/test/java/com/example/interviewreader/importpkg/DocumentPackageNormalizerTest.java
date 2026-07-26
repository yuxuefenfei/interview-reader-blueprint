package com.example.interviewreader.importpkg;

import com.example.interviewreader.document.BlockType;
import com.example.interviewreader.document.NodeType;
import com.example.interviewreader.document.SourceType;
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
                new DocumentPackage.VersionInfo("v1", SourceType.MANUAL, null, null, null, java.util.Map.of()),
                List.of(new DocumentPackage.SectionInfo("section", null, 1, NodeType.SECTION, null, "标题", 1, "title", null, null, null, null)),
                List.of(block("empty", 1, "paragraph", json.objectNode().put("text", ""), "")),
                List.of());

        assertThat(validator.validate(documentPackage))
                .extracting(ImportIssueDto::issueCode)
                .contains("EMPTY_CONTENT_BLOCK");
    }

    @Test
    void normalizerReplacesExternalAnchorsBeforeValidation() {
        var json = JsonNodeFactory.instance;
        var documentPackage = new DocumentPackage("1.0",
                new DocumentPackage.DocumentInfo("document", "标题", null, "zh-CN", List.of()),
                new DocumentPackage.VersionInfo("v1", SourceType.MANUAL, null, null, null, java.util.Map.of()),
                List.of(
                        new DocumentPackage.SectionInfo("first", null, 1, NodeType.SECTION, null, "第一节", 1, "same-anchor", null, null, null, null),
                        new DocumentPackage.SectionInfo("second", null, 1, NodeType.SECTION, null, "第二节", 2, "same-anchor", null, null, null, null)),
                List.of(block("content", 1, "paragraph", json.objectNode().put("text", "有效内容"), "有效内容")),
                List.of());

        var result = normalizer.normalize(documentPackage, SourceType.JSON_PACKAGE);

        assertThat(result.documentPackage().sections())
                .extracting(DocumentPackage.SectionInfo::anchor)
                .allMatch(anchor -> anchor.matches("sec_[0-9a-f-]{36}"))
                .doesNotHaveDuplicates();
        assertThat(result.issues())
                .extracting(ImportIssueDto::issueCode)
                .contains("SECTION_ANCHORS_REGENERATED");
        assertThat(validator.validate(result.documentPackage()))
                .extracting(ImportIssueDto::issueCode)
                .doesNotContain("SECTION_ANCHOR_DUPLICATE");
    }

    @Test
    void legacySnapshotAnchorsAreUpgradedOnlyOnce() {
        var source = new DocumentPackage("1.0", null, null, List.of(
                new DocumentPackage.SectionInfo("first", null, 1, NodeType.SECTION, null, "第一节", 1, "legacy-title", null, null, null, null)),
                List.of(), List.of());

        var upgraded = normalizer.upgradeLegacyAnchors(source);
        var stableAnchor = upgraded.documentPackage().sections().getFirst().anchor();
        var repeatedUpgrade = normalizer.upgradeLegacyAnchors(upgraded.documentPackage());

        assertThat(stableAnchor).matches("sec_[0-9a-f-]{36}");
        assertThat(upgraded.issues()).extracting(ImportIssueDto::issueCode).containsExactly("SECTION_ANCHORS_REGENERATED");
        assertThat(repeatedUpgrade.documentPackage().sections().getFirst().anchor()).isEqualTo(stableAnchor);
        assertThat(repeatedUpgrade.issues()).isEmpty();
    }

    @Test
    void validatorRejectsDuplicateAssetHashesBeforeCommit() {
        var json = JsonNodeFactory.instance;
        var hash = "a".repeat(64);
        var documentPackage = new DocumentPackage("1.0",
                new DocumentPackage.DocumentInfo("document", "标题", null, "zh-CN", List.of()),
                new DocumentPackage.VersionInfo("v1", SourceType.MANUAL, null, null, null, java.util.Map.of()),
                List.of(new DocumentPackage.SectionInfo("section", null, 1, NodeType.SECTION, null, "第一节", 1, "sec_existing", null, null, null, null)),
                List.of(block("content", 1, "paragraph", json.objectNode().put("text", "有效内容"), "有效内容")),
                List.of(
                        new DocumentPackage.AssetInfo("first", "first.png", "image/png", hash, null),
                        new DocumentPackage.AssetInfo("second", "second.png", "image/png", hash.toUpperCase(java.util.Locale.ROOT), null)));

        assertThat(validator.validate(documentPackage))
                .extracting(ImportIssueDto::issueCode)
                .contains("ASSET_SHA256_DUPLICATE");
    }

    private static DocumentPackage.BlockInfo block(String key, int seq, String type, com.fasterxml.jackson.databind.JsonNode payload, String plainText) {
        return new DocumentPackage.BlockInfo(key, "section", seq, BlockType.fromCode(type), payload, plainText, null, null, null, null, null);
    }
}
