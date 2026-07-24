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
public class StaticHtmlPackageService {
    public String write(DocumentPackage documentPackage) {
        return write(documentPackage, assetKey -> assetKey);
    }

    public String write(DocumentPackage documentPackage, Function<String, String> assetUrl) {
        var body = new StringBuilder();
        body.append("<main class=\"reader-document\">\n");
        body.append("<h1>").append(escape(documentPackage.document().title())).append("</h1>\n");
        if (hasText(documentPackage.document().description())) {
            body.append("<p class=\"description\">").append(escape(documentPackage.document().description())).append("</p>\n");
        }
        if (documentPackage.document().tags() != null && !documentPackage.document().tags().isEmpty()) {
            body.append("<p class=\"tags\">")
                    .append(escape(String.join(", ", documentPackage.document().tags())))
                    .append("</p>\n");
        }

        var blocksBySection = new LinkedHashMap<String, List<DocumentPackage.BlockInfo>>();
        for (var block : Objects.requireNonNullElse(documentPackage.blocks(), List.<DocumentPackage.BlockInfo>of())) {
            blocksBySection.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>()).add(block);
        }
        for (var section : Objects.requireNonNullElse(documentPackage.sections(), List.<DocumentPackage.SectionInfo>of())) {
            var headingLevel = Math.min(6, section.level() + 1);
            body.append("<section id=\"").append(escapeAttribute(section.anchor())).append("\">\n")
                    .append("<h").append(headingLevel).append(">")
                    .append(escape(section.title()))
                    .append("</h").append(headingLevel).append(">\n");
            for (var block : blocksBySection.getOrDefault(section.sectionKey(), List.of())) {
                appendBlock(body, block, assetUrl);
            }
            body.append("</section>\n");
        }
        body.append("</main>\n");

        return """
                <!doctype html>
                <html lang="%s">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>%s</title>
                  <style>
                    :root { color-scheme: light; font-family: Inter, "Segoe UI", system-ui, sans-serif; }
                    body { margin: 0; background: #fbfcfd; color: #17202a; }
                    .reader-document { width: min(100%%, 820px); margin: 0 auto; padding: 40px 24px 80px; }
                    h1, h2, h3, h4, h5, h6 { line-height: 1.25; }
                    p, li, blockquote { font-size: 18px; line-height: 1.82; }
                    pre { padding: 14px; overflow: auto; background: #f0f4f8; border: 1px solid #d7dee8; }
                    code { font-family: "JetBrains Mono", Consolas, monospace; font-size: 14px; line-height: 1.6; }
                    table { width: 100%%; border-collapse: collapse; margin: 0 0 18px; }
                    th, td { padding: 10px 12px; border: 1px solid #d7dee8; text-align: left; vertical-align: top; }
                    th { background: #f0f4f8; }
                    blockquote, .callout { padding: 12px 14px; border-left: 4px solid #0f766e; background: #d9f3ee; }
                    .description, .tags { color: #64748b; }
                    figure { margin: 20px 0; }
                    figure img { display: block; width: 100%%; height: auto; max-height: 720px; object-fit: contain; border: 1px solid #d7dee8; border-radius: 4px; }
                    figcaption { margin-top: 8px; color: #64748b; font-size: 14px; }
                  </style>
                </head>
                <body>
                %s</body>
                </html>
                """.formatted(
                escapeAttribute(Objects.requireNonNullElse(documentPackage.document().language(), "zh-CN")),
                escape(documentPackage.document().title()),
                body);
    }

    private void appendBlock(StringBuilder html, DocumentPackage.BlockInfo block, Function<String, String> assetUrl) {
        switch (block.blockType()) {
            case PARAGRAPH -> html.append("<p>").append(escape(text(block))).append("</p>\n");
            case HEADING_NOTE -> html.append("<p class=\"heading-note\"><strong>").append(escape(text(block))).append("</strong></p>\n");
            case UNORDERED_LIST -> appendList(html, block, "ul");
            case ORDERED_LIST -> appendList(html, block, "ol");
            case CODE -> appendCode(html, block);
            case TABLE -> appendTable(html, block);
            case QUOTE -> html.append("<blockquote>").append(escape(text(block))).append("</blockquote>\n");
            case CALLOUT -> appendCallout(html, block);
            case FORMULA -> html.append("<p class=\"formula\"><code>").append(escape(payloadText(block.payload(), "latex", text(block)))).append("</code></p>\n");
            case IMAGE -> appendImage(html, block, assetUrl);
            case DIVIDER -> html.append("<hr />\n");
            default -> html.append("<pre>").append(escape(text(block))).append("</pre>\n");
        }
    }

    private void appendList(StringBuilder html, DocumentPackage.BlockInfo block, String tag) {
        var items = arrayText(block.payload(), "items");
        if (items.isEmpty()) {
            html.append("<p>").append(escape(text(block))).append("</p>\n");
            return;
        }
        html.append('<').append(tag).append(">\n");
        for (var item : items) {
            html.append("<li>").append(escape(item)).append("</li>\n");
        }
        html.append("</").append(tag).append(">\n");
    }

    private void appendCode(StringBuilder html, DocumentPackage.BlockInfo block) {
        var language = payloadText(block.payload(), "language", Objects.requireNonNullElse(block.language(), "text"));
        html.append("<pre><code class=\"language-")
                .append(escapeAttribute(language))
                .append("\">")
                .append(escape(text(block)))
                .append("</code></pre>\n");
    }

    private void appendTable(StringBuilder html, DocumentPackage.BlockInfo block) {
        var columns = arrayText(block.payload(), "columns");
        var rows = block.payload() == null ? List.<JsonNode>of() : iterable(block.payload().get("rows"));
        if (columns.isEmpty() && rows.isEmpty()) {
            html.append("<p>").append(escape(text(block))).append("</p>\n");
            return;
        }
        html.append("<table>\n");
        if (!columns.isEmpty()) {
            html.append("<thead><tr>");
            for (var column : columns) {
                html.append("<th>").append(escape(column)).append("</th>");
            }
            html.append("</tr></thead>\n");
        }
        html.append("<tbody>\n");
        for (var row : rows) {
            html.append("<tr>");
            for (var cell : iterable(row)) {
                html.append("<td>").append(escape(cell.asText())).append("</td>");
            }
            html.append("</tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private void appendCallout(StringBuilder html, DocumentPackage.BlockInfo block) {
        html.append("<aside class=\"callout\">");
        var title = payloadText(block.payload(), "title", "");
        if (hasText(title)) {
            html.append("<strong>").append(escape(title)).append("</strong>");
        }
        html.append("<p>").append(escape(text(block))).append("</p></aside>\n");
    }

    private void appendImage(StringBuilder html, DocumentPackage.BlockInfo block, Function<String, String> assetUrl) {
        var alt = payloadText(block.payload(), "alt", text(block));
        var assetKey = payloadText(block.payload(), "assetKey", "");
        var caption = payloadText(block.payload(), "caption", "");
        html.append("<figure data-asset-key=\"").append(escapeAttribute(assetKey)).append("\">");
        if (!assetKey.isBlank()) {
            html.append("<img src=\"").append(escapeAttribute(assetUrl.apply(assetKey))).append("\" alt=\"")
                    .append(escapeAttribute(alt)).append("\" />");
        }
        if (hasText(caption) || assetKey.isBlank()) {
            html.append("<figcaption>").append(escape(hasText(caption) ? caption : alt)).append("</figcaption>");
        }
        html.append("</figure>\n");
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

    private String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String escape(String value) {
        return Objects.requireNonNullElse(value, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
