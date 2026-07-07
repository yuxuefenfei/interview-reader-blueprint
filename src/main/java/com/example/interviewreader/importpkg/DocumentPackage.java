package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record DocumentPackage(
        String schemaVersion,
        DocumentInfo document,
        VersionInfo version,
        List<SectionInfo> sections,
        List<BlockInfo> blocks,
        List<AssetInfo> assets
) {
    public record DocumentInfo(
            String documentKey,
            String title,
            String description,
            String language,
            List<String> tags
    ) {
    }

    public record VersionInfo(
            String versionKey,
            String sourceType,
            String sourceFileName,
            String sourceSha256,
            String converterVersion,
            Map<String, Object> metadata
    ) {
    }

    public record SectionInfo(
            String sectionKey,
            String parentSectionKey,
            Integer level,
            String nodeType,
            String semanticRole,
            String title,
            Integer sortOrder,
            String anchor,
            Integer sourcePageStart,
            Integer sourcePageEnd,
            JsonNode sourceBbox,
            String contentHash
    ) {
    }

    public record BlockInfo(
            String blockKey,
            String sectionKey,
            Integer seq,
            String blockType,
            JsonNode payload,
            String plainText,
            String language,
            Integer sourcePage,
            JsonNode sourceBbox,
            BigDecimal confidence,
            String contentHash
    ) {
    }

    public record AssetInfo(
            String assetKey,
            String path,
            String mimeType,
            String sha256,
            String alt
    ) {
    }
}
