package com.example.interviewreader.exportpkg;

import com.example.interviewreader.importpkg.DocumentPackage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class MarkdownPackageService {
    public String write(DocumentPackage documentPackage) {
        return write(documentPackage, assetKey -> assetKey);
    }

    public String write(DocumentPackage documentPackage, Function<String, String> assetUrl) {
        var markdown = new StringBuilder();
        appendHeading(markdown, 1, documentPackage.document().title());
        if (hasText(documentPackage.document().description())) {
            markdown.append(documentPackage.document().description().trim()).append("\n\n");
        }
        if (documentPackage.document().tags() != null && !documentPackage.document().tags().isEmpty()) {
            markdown.append("Tags: ").append(String.join(", ", documentPackage.document().tags())).append("\n\n");
        }

        var blocksBySection = new LinkedHashMap<String, List<DocumentPackage.BlockInfo>>();
        for (var block : Objects.requireNonNullElse(documentPackage.blocks(), List.<DocumentPackage.BlockInfo>of())) {
            blocksBySection.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>()).add(block);
        }
        for (var section : Objects.requireNonNullElse(documentPackage.sections(), List.<DocumentPackage.SectionInfo>of())) {
            appendHeading(markdown, Math.min(6, section.level() + 1), section.title());
            for (var block : blocksBySection.getOrDefault(section.sectionKey(), List.of())) {
                appendBlock(markdown, block, assetUrl);
            }
        }
        return markdown.toString().stripTrailing() + "\n";
    }

    private void appendBlock(StringBuilder markdown, DocumentPackage.BlockInfo block, Function<String, String> assetUrl) {
        switch (block.blockType()) {
            case UNORDERED_LIST -> appendList(markdown, block, false);
            case ORDERED_LIST -> appendList(markdown, block, true);
            case CODE -> appendCode(markdown, block);
            case TABLE -> appendTable(markdown, block);
            case QUOTE -> appendQuote(markdown, block);
            case CALLOUT -> appendCallout(markdown, block);
            case FORMULA -> markdown.append("$$\n").append(payloadText(block.payload(), "latex", text(block))).append("\n$$\n\n");
            case IMAGE -> appendImage(markdown, block, assetUrl);
            case DIVIDER -> markdown.append("---\n\n");
            default -> markdown.append(text(block)).append("\n\n");
        }
    }

    private void appendHeading(StringBuilder markdown, int level, String title) {
        markdown.repeat("#", Math.max(1, level)).append(' ').append(Objects.requireNonNullElse(title, "").trim()).append("\n\n");
    }

    private void appendList(StringBuilder markdown, DocumentPackage.BlockInfo block, boolean ordered) {
        var items = arrayText(block.payload(), "items");
        if (items.isEmpty()) {
            markdown.append(text(block)).append("\n\n");
            return;
        }
        for (var i = 0; i < items.size(); i++) {
            markdown.append(ordered ? (i + 1) + ". " : "- ").append(items.get(i)).append('\n');
        }
        markdown.append('\n');
    }

    private void appendCode(StringBuilder markdown, DocumentPackage.BlockInfo block) {
        var language = payloadText(block.payload(), "language", Objects.requireNonNullElse(block.language(), ""));
        markdown.append("```").append(language).append('\n')
                .append(text(block))
                .append("\n```\n\n");
    }

    private void appendTable(StringBuilder markdown, DocumentPackage.BlockInfo block) {
        var columns = arrayText(block.payload(), "columns");
        var rows = block.payload() == null ? List.<JsonNode>of() : iterable(block.payload().get("rows"));
        if (columns.isEmpty() && rows.isEmpty()) {
            markdown.append(text(block)).append("\n\n");
            return;
        }
        if (columns.isEmpty()) {
            for (var i = 0; i < rows.getFirst().size(); i++) {
                columns.add("");
            }
        }
        markdown.append("| ").append(String.join(" | ", columns.stream().map(this::escapeTableCell).toList())).append(" |\n");
        markdown.append("| ").append(String.join(" | ", columns.stream().map(ignored -> "---").toList())).append(" |\n");
        for (var row : rows) {
            var cells = iterable(row).stream().map(JsonNode::asText).map(this::escapeTableCell).toList();
            markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        markdown.append('\n');
    }

    private void appendQuote(StringBuilder markdown, DocumentPackage.BlockInfo block) {
        for (var line : text(block).lines().toList()) {
            markdown.append("> ").append(line).append('\n');
        }
        markdown.append('\n');
    }

    private void appendCallout(StringBuilder markdown, DocumentPackage.BlockInfo block) {
        var title = payloadText(block.payload(), "title", "");
        if (hasText(title)) {
            markdown.append("> **").append(title).append("**\n");
        }
        for (var line : text(block).lines().toList()) {
            markdown.append("> ").append(line).append('\n');
        }
        markdown.append('\n');
    }

    private void appendImage(StringBuilder markdown, DocumentPackage.BlockInfo block, Function<String, String> assetUrl) {
        var alt = payloadText(block.payload(), "alt", text(block));
        var assetKey = payloadText(block.payload(), "assetKey", "");
        markdown.append("![").append(alt).append("](").append(assetKey.isBlank() ? "" : assetUrl.apply(assetKey)).append(")\n\n");
    }

    private String text(DocumentPackage.BlockInfo block) {
        return payloadText(block.payload(), "text", Objects.requireNonNullElse(block.plainText(), ""));
    }

    private String payloadText(JsonNode payload, String field, String fallback) {
        return payload != null && payload.hasNonNull(field) ? payload.get(field).asText() : fallback;
    }

    private List<String> arrayText(JsonNode payload, String field) {
        if (payload == null || !payload.has(field) || !payload.get(field).isArray()) {
            return new ArrayList<>();
        }
        var values = new ArrayList<String>();
        payload.get(field).forEach(value -> values.add(value.asText()));
        return values;
    }

    private List<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<JsonNode>();
        node.forEach(values::add);
        return values;
    }

    private String escapeTableCell(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
