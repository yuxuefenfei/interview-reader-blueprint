package com.example.interviewreader.importpkg;

import com.example.interviewreader.document.SourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DocumentPackageNormalizer {
    public NormalizationResult normalize(DocumentPackage source) {
        return normalize(source, null);
    }

    /**
     * Generates opaque, immutable section anchors at the import boundary. External anchor values are deliberately
     * ignored so titles and import-package details can never affect stable reading links.
     */
    public NormalizationResult normalize(DocumentPackage source, SourceType importSourceType) {
        if (source == null) {
            return new NormalizationResult(null, List.of());
        }

        var issues = new ArrayList<ImportIssueDto>();
        var normalizedSections = normalizeSections(source.sections(), importSourceType, issues);
        var normalizedBlocks = normalizeBlocks(source.blocks(), issues);
        return new NormalizationResult(new DocumentPackage(
                source.schemaVersion(), source.document(), source.version(), normalizedSections, normalizedBlocks, source.assets()), issues);
    }

    /**
     * Upgrades a snapshot created before opaque anchors were introduced. This is intentionally idempotent: packages
     * that already have only opaque anchors keep the exact values generated when the job was first processed.
     */
    public NormalizationResult upgradeLegacyAnchors(DocumentPackage source) {
        if (source == null || source.sections() == null || source.sections().stream()
                .filter(Objects::nonNull)
                .allMatch(section -> isOpaqueAnchor(section.anchor()))) {
            return new NormalizationResult(source, List.of());
        }

        var normalized = new ArrayList<DocumentPackage.SectionInfo>();
        for (var section : source.sections()) {
            if (section == null) {
                normalized.add(null);
                continue;
            }
            normalized.add(new DocumentPackage.SectionInfo(
                    section.sectionKey(), section.parentSectionKey(), section.level(), section.nodeType(),
                    section.semanticRole(), section.title(), section.sortOrder(), opaqueAnchor(),
                    section.sourcePageStart(), section.sourcePageEnd(), section.sourceBbox(), section.contentHash()));
        }
        var upgraded = new DocumentPackage(
                source.schemaVersion(), source.document(), source.version(), normalized, source.blocks(), source.assets());
        var issue = new ImportIssueDto(ImportIssueSeverity.WARNING, "SECTION_ANCHORS_REGENERATED",
                "Upgraded a staged import snapshot to stable internal section anchors before commit.", null, null, null);
        return new NormalizationResult(upgraded, List.of(issue));
    }

    private List<DocumentPackage.SectionInfo> normalizeSections(
            List<DocumentPackage.SectionInfo> sections,
            SourceType importSourceType,
            List<ImportIssueDto> issues
    ) {
        if (sections == null) {
            return null;
        }
        var suppliedAnchorCount = 0;
        var normalized = new ArrayList<DocumentPackage.SectionInfo>();
        for (var section : sections) {
            if (section == null) {
                normalized.add(null);
                continue;
            }
            if (section.anchor() != null && !section.anchor().isBlank()) {
                suppliedAnchorCount++;
            }
            normalized.add(new DocumentPackage.SectionInfo(
                    section.sectionKey(), section.parentSectionKey(), section.level(), section.nodeType(),
                    section.semanticRole(), section.title(), section.sortOrder(), opaqueAnchor(),
                    section.sourcePageStart(), section.sourcePageEnd(), section.sourceBbox(), section.contentHash()));
        }
        if (suppliedAnchorCount > 0 && acceptsExternalAnchor(importSourceType)) {
            issues.add(new ImportIssueDto(ImportIssueSeverity.WARNING, "SECTION_ANCHORS_REGENERATED",
                    "Ignored " + suppliedAnchorCount + " supplied section anchor value(s); generated stable internal anchors instead.",
                    null, null, null));
        }
        return normalized;
    }

    private List<DocumentPackage.BlockInfo> normalizeBlocks(List<DocumentPackage.BlockInfo> blocks, List<ImportIssueDto> issues) {
        if (blocks == null) {
            return null;
        }
        var kept = new LinkedHashMap<String, List<DocumentPackage.BlockInfo>>();
        for (var block : blocks) {
            if (block == null || !DocumentBlockContent.isMeaningful(block.blockType(), block.plainText(), block.payload())) {
                if (block != null) {
                    issues.add(new ImportIssueDto(ImportIssueSeverity.WARNING, "EMPTY_CONTENT_BLOCK_REMOVED",
                            "Removed an empty content block during import normalization", block.sourcePage(), block.sectionKey(), block.blockKey()));
                }
                continue;
            }
            kept.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>()).add(block);
        }
        var normalized = new ArrayList<DocumentPackage.BlockInfo>();
        kept.values().forEach(group -> {
            group.sort(Comparator.comparing(block -> Objects.requireNonNullElse(block.seq(), Integer.MAX_VALUE)));
            for (var index = 0; index < group.size(); index++) {
                var block = group.get(index);
                normalized.add(new DocumentPackage.BlockInfo(block.blockKey(), block.sectionKey(), index + 1, block.blockType(),
                        block.payload(), block.plainText(), block.language(), block.sourcePage(), block.sourceBbox(), block.confidence(), block.contentHash()));
            }
        });
        return normalized;
    }

    private static boolean acceptsExternalAnchor(SourceType sourceType) {
        return sourceType == SourceType.JSON_PACKAGE || sourceType == SourceType.EXCEL;
    }

    private static String opaqueAnchor() {
        return "sec_" + UUID.randomUUID();
    }

    private static boolean isOpaqueAnchor(String anchor) {
        if (anchor == null || !anchor.startsWith("sec_")) {
            return false;
        }
        try {
            UUID.fromString(anchor.substring("sec_".length()));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record NormalizationResult(DocumentPackage documentPackage, List<ImportIssueDto> issues) {
    }
}
