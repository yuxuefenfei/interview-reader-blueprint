package com.example.interviewreader.importpkg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DocumentPackageNormalizer {
    public NormalizationResult normalize(DocumentPackage source) {
        if (source == null || source.blocks() == null) return new NormalizationResult(source, List.of());
        var kept = new LinkedHashMap<String, List<DocumentPackage.BlockInfo>>();
        var issues = new ArrayList<ImportIssueDto>();
        for (var block : source.blocks()) {
            if (block == null || !DocumentBlockContent.isMeaningful(block.blockType(), block.plainText(), block.payload())) {
                if (block != null) issues.add(new ImportIssueDto(ImportIssueSeverity.WARNING, "EMPTY_CONTENT_BLOCK_REMOVED",
                        "Removed an empty content block during import normalization", block.sourcePage(), block.sectionKey(), block.blockKey()));
                continue;
            }
            kept.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>()).add(block);
        }
        var normalizedBlocks = new ArrayList<DocumentPackage.BlockInfo>();
        kept.values().forEach(group -> {
            group.sort(Comparator.comparing(block -> Objects.requireNonNullElse(block.seq(), Integer.MAX_VALUE)));
            for (var index = 0; index < group.size(); index++) {
                var block = group.get(index);
                normalizedBlocks.add(new DocumentPackage.BlockInfo(block.blockKey(), block.sectionKey(), index + 1, block.blockType(),
                        block.payload(), block.plainText(), block.language(), block.sourcePage(), block.sourceBbox(), block.confidence(), block.contentHash()));
            }
        });
        return new NormalizationResult(new DocumentPackage(source.schemaVersion(), source.document(), source.version(), source.sections(), normalizedBlocks, source.assets()), issues);
    }

    public record NormalizationResult(DocumentPackage documentPackage, List<ImportIssueDto> issues) {
    }
}
