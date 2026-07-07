package com.example.interviewreader.importpkg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DocumentPackageValidator {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Set<String> SOURCE_TYPES = Set.of("PDF", "EXCEL", "JSON_PACKAGE", "MARKDOWN", "MANUAL");
    private static final Set<String> NODE_TYPES = Set.of("PART", "CHAPTER", "SECTION", "SUBSECTION", "QUESTION", "APPENDIX", "OTHER");
    private static final Set<String> BLOCK_TYPES = Set.of(
            "paragraph", "heading_note", "unordered_list", "ordered_list", "code", "table",
            "quote", "callout", "formula", "image", "divider", "table_snapshot");

    public List<ImportIssueDto> validate(DocumentPackage documentPackage) {
        var issues = new ArrayList<ImportIssueDto>();
        if (documentPackage == null) {
            issues.add(blocking("PACKAGE_EMPTY", "JSON package is empty", null, null));
            return issues;
        }

        validateHeader(documentPackage, issues);
        var sectionsByKey = validateSections(documentPackage.sections(), issues);
        validateBlocks(documentPackage.blocks(), sectionsByKey, issues);
        validateAssets(documentPackage.assets(), issues);
        return issues;
    }

    private void validateHeader(DocumentPackage documentPackage, List<ImportIssueDto> issues) {
        if (!"1.0".equals(documentPackage.schemaVersion())) {
            issues.add(blocking("SCHEMA_VERSION_UNSUPPORTED", "schemaVersion must be 1.0", null, null));
        }
        if (documentPackage.document() == null) {
            issues.add(blocking("DOCUMENT_REQUIRED", "document is required", null, null));
        } else {
            if (isBlank(documentPackage.document().documentKey())) {
                issues.add(blocking("DOCUMENT_KEY_REQUIRED", "document.documentKey is required", null, null));
            }
            if (isBlank(documentPackage.document().title())) {
                issues.add(blocking("DOCUMENT_TITLE_REQUIRED", "document.title is required", null, null));
            }
        }
        if (documentPackage.version() == null) {
            issues.add(blocking("VERSION_REQUIRED", "version is required", null, null));
        } else {
            if (isBlank(documentPackage.version().versionKey())) {
                issues.add(blocking("VERSION_KEY_REQUIRED", "version.versionKey is required", null, null));
            }
            if (!SOURCE_TYPES.contains(nullToEmpty(documentPackage.version().sourceType()).toUpperCase(Locale.ROOT))) {
                issues.add(blocking("SOURCE_TYPE_INVALID", "version.sourceType is invalid", null, null));
            }
            var sourceSha256 = documentPackage.version().sourceSha256();
            if (!isBlank(sourceSha256) && !SHA256.matcher(sourceSha256).matches()) {
                issues.add(blocking("SOURCE_SHA256_INVALID", "version.sourceSha256 must be a SHA-256 hex string", null, null));
            }
        }
    }

    private Map<String, DocumentPackage.SectionInfo> validateSections(
            List<DocumentPackage.SectionInfo> sections,
            List<ImportIssueDto> issues
    ) {
        var result = new HashMap<String, DocumentPackage.SectionInfo>();
        if (sections == null || sections.isEmpty()) {
            issues.add(blocking("SECTIONS_REQUIRED", "sections must contain at least one section", null, null));
            return result;
        }

        for (var section : sections) {
            if (section == null || isBlank(section.sectionKey())) {
                issues.add(blocking("SECTION_KEY_REQUIRED", "sectionKey is required", null, null));
                continue;
            }
            if (result.putIfAbsent(section.sectionKey(), section) != null) {
                issues.add(blocking("SECTION_KEY_DUPLICATE", "Duplicate sectionKey: " + section.sectionKey(), section.sectionKey(), null));
            }
            if (section.level() == null || section.level() < 1 || section.level() > 32) {
                issues.add(blocking("SECTION_LEVEL_INVALID", "level must be between 1 and 32", section.sectionKey(), null));
            }
            if (!NODE_TYPES.contains(nullToEmpty(section.nodeType()).toUpperCase(Locale.ROOT))) {
                issues.add(blocking("NODE_TYPE_INVALID", "nodeType is invalid", section.sectionKey(), null));
            }
            if (isBlank(section.title())) {
                issues.add(blocking("SECTION_TITLE_REQUIRED", "title is required", section.sectionKey(), null));
            }
            if (section.sortOrder() == null) {
                issues.add(blocking("SECTION_SORT_ORDER_REQUIRED", "sortOrder is required", section.sectionKey(), null));
            }
        }

        for (var section : sections) {
            if (section == null || isBlank(section.sectionKey()) || isBlank(section.parentSectionKey())) {
                continue;
            }
            var parent = result.get(section.parentSectionKey());
            if (parent == null) {
                issues.add(blocking("PARENT_SECTION_MISSING", "parentSectionKey does not exist: " + section.parentSectionKey(), section.sectionKey(), null));
                continue;
            }
            if (section.level() != null && parent.level() != null && section.level() != parent.level() + 1) {
                issues.add(blocking("SECTION_LEVEL_JUMP", "child section level must equal parent level + 1", section.sectionKey(), null));
            }
        }
        return result;
    }

    private void validateBlocks(
            List<DocumentPackage.BlockInfo> blocks,
            Map<String, DocumentPackage.SectionInfo> sectionsByKey,
            List<ImportIssueDto> issues
    ) {
        if (blocks == null) {
            issues.add(blocking("BLOCKS_REQUIRED", "blocks is required, use an empty array when there is no content", null, null));
            return;
        }
        var blockKeys = new HashSet<String>();
        var seqKeys = new HashSet<String>();
        for (var block : blocks) {
            if (block == null || isBlank(block.blockKey())) {
                issues.add(blocking("BLOCK_KEY_REQUIRED", "blockKey is required", null, null));
                continue;
            }
            if (!blockKeys.add(block.blockKey())) {
                issues.add(blocking("BLOCK_KEY_DUPLICATE", "Duplicate blockKey: " + block.blockKey(), block.sectionKey(), block.blockKey()));
            }
            if (!sectionsByKey.containsKey(block.sectionKey())) {
                issues.add(blocking("BLOCK_SECTION_MISSING", "block.sectionKey does not exist: " + block.sectionKey(), block.sectionKey(), block.blockKey()));
            }
            if (block.seq() == null || block.seq() < 1) {
                issues.add(blocking("BLOCK_SEQ_INVALID", "seq must be greater than 0", block.sectionKey(), block.blockKey()));
            } else if (!seqKeys.add(block.sectionKey() + "#" + block.seq())) {
                issues.add(blocking("BLOCK_SEQ_DUPLICATE", "Duplicate seq in section: " + block.seq(), block.sectionKey(), block.blockKey()));
            }
            if (!BLOCK_TYPES.contains(nullToEmpty(block.blockType()))) {
                issues.add(blocking("BLOCK_TYPE_INVALID", "blockType is invalid", block.sectionKey(), block.blockKey()));
            }
            if (block.payload() == null || block.payload().isNull()) {
                issues.add(blocking("BLOCK_PAYLOAD_REQUIRED", "payload is required", block.sectionKey(), block.blockKey()));
            }
            if (block.confidence() != null && (block.confidence().signum() < 0 || block.confidence().compareTo(java.math.BigDecimal.ONE) > 0)) {
                issues.add(blocking("BLOCK_CONFIDENCE_INVALID", "confidence must be between 0 and 1", block.sectionKey(), block.blockKey()));
            }
        }
    }

    private void validateAssets(List<DocumentPackage.AssetInfo> assets, List<ImportIssueDto> issues) {
        if (assets == null) {
            issues.add(blocking("ASSETS_REQUIRED", "assets is required, use an empty array when there are no assets", null, null));
            return;
        }
        var assetKeys = new HashSet<String>();
        for (var asset : assets) {
            if (asset == null || isBlank(asset.assetKey())) {
                issues.add(blocking("ASSET_KEY_REQUIRED", "assetKey is required", null, null));
                continue;
            }
            if (!assetKeys.add(asset.assetKey())) {
                issues.add(blocking("ASSET_KEY_DUPLICATE", "Duplicate assetKey: " + asset.assetKey(), null, null));
            }
            if (isBlank(asset.path())) {
                issues.add(blocking("ASSET_PATH_REQUIRED", "asset.path is required", null, null));
            }
            if (isBlank(asset.mimeType())) {
                issues.add(blocking("ASSET_MIME_TYPE_REQUIRED", "asset.mimeType is required", null, null));
            }
            if (isBlank(asset.sha256()) || !SHA256.matcher(asset.sha256()).matches()) {
                issues.add(blocking("ASSET_SHA256_INVALID", "asset.sha256 must be a SHA-256 hex string", null, null));
            }
        }
    }

    private ImportIssueDto blocking(String code, String message, String sectionKey, String blockKey) {
        return new ImportIssueDto("BLOCKING", code, message, null, sectionKey, blockKey);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
