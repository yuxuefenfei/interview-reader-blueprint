package com.example.interviewreader.markdownpkg;

import com.example.interviewreader.common.Hashes;
import com.example.interviewreader.document.BlockType;
import com.example.interviewreader.document.NodeType;
import com.example.interviewreader.document.SemanticRole;
import com.example.interviewreader.document.SourceType;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.importpkg.ImportDocumentNaming;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

@Service("markdownImportPackageService")
@RequiredArgsConstructor
public class MarkdownPackageService {
    private final ObjectMapper objectMapper;

    public DocumentPackage parse(byte[] bytes, String sourceFileName, String sourceSha256, String converterVersion) {
        var markdown = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        var state = new ParseState(sourceFileName, sourceSha256, converterVersion);
        var lines = markdown.lines().toList();
        var paragraph = new ArrayList<String>();
        var code = new ArrayList<String>();
        String codeLanguage = null;
        var inCode = false;

        while (state.lineIndex < lines.size()) {
            var line = lines.get(state.lineIndex);
            if (line.startsWith("```")) {
                if (inCode) {
                    state.addCode(codeLanguage, String.join("\n", code));
                    code.clear();
                    codeLanguage = null;
                    inCode = false;
                } else {
                    flushParagraph(state, paragraph);
                    inCode = true;
                    codeLanguage = line.substring(3).trim();
                }
                state.lineIndex += 1;
                continue;
            }
            if (inCode) {
                code.add(line);
                state.lineIndex += 1;
                continue;
            }
            var heading = heading(line);
            if (heading != null) {
                flushParagraph(state, paragraph);
                state.addSection(heading.level(), heading.title());
                state.lineIndex += 1;
                continue;
            }
            if (isTableStart(lines, state.lineIndex)) {
                flushParagraph(state, paragraph);
                var consumed = state.addTable(lines, state.lineIndex);
                state.lineIndex += consumed;
                continue;
            }
            var listItem = listItem(line);
            if (listItem != null) {
                flushParagraph(state, paragraph);
                var consumed = state.addList(lines, state.lineIndex, listItem.ordered());
                state.lineIndex += consumed;
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(state, paragraph);
            } else {
                paragraph.add(line.trim());
            }
            state.lineIndex += 1;
        }
        if (inCode) {
            state.addCode(codeLanguage, String.join("\n", code));
        }
        flushParagraph(state, paragraph);
        return state.toPackage();
    }

    private void flushParagraph(ParseState state, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        state.addTextBlock(String.join("\n", paragraph));
        paragraph.clear();
    }

    private Heading heading(String line) {
        var trimmed = line.trim();
        if (!trimmed.startsWith("#")) {
            return null;
        }
        var level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6 || level == trimmed.length() || trimmed.charAt(level) != ' ') {
            return null;
        }
        return new Heading(level, trimmed.substring(level).trim());
    }

    private ListItem listItem(String line) {
        var trimmed = line.trim();
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            return new ListItem(false, trimmed.substring(2).trim());
        }
        if (trimmed.matches("\\d+[.)] .+")) {
            return new ListItem(true, trimmed.replaceFirst("^\\d+[.)] ", "").trim());
        }
        return null;
    }

    private boolean isTableStart(List<String> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }
        return isTableRow(lines.get(index)) && lines.get(index + 1).matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*");
    }

    private boolean isTableRow(String line) {
        return line.contains("|") && !line.trim().isBlank();
    }

    private List<String> tableCells(String line) {
        var trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Stream.of(trimmed.split("\\|", -1)).map(String::trim).toList();
    }

    private String slug(String value, String fallback) {
        var slug = Objects.requireNonNullElse(value, "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? fallback : slug;
    }

    private final class ParseState {
        private final String sourceFileName;
        private final String sourceSha256;
        private final String converterVersion;
        private final List<DocumentPackage.SectionInfo> sections = new ArrayList<>();
        private final List<DocumentPackage.BlockInfo> blocks = new ArrayList<>();
        private final Map<Integer, String> latestSectionByLevel = new HashMap<>();
        private int lineIndex;
        private int sectionIndex;
        private int blockIndex;
        private String currentSectionKey;
        private String documentTitle;

        private ParseState(String sourceFileName, String sourceSha256, String converterVersion) {
            this.sourceFileName = sourceFileName;
            this.sourceSha256 = sourceSha256;
            this.converterVersion = converterVersion;
        }

        private void addSection(int markdownLevel, String title) {
            sectionIndex += 1;
            var sectionKey = "section-" + sectionIndex;
            var parentKey = latestSectionByLevel.get(markdownLevel - 1);
            for (var level = markdownLevel; level <= 6; level++) {
                latestSectionByLevel.remove(level);
            }
            latestSectionByLevel.put(markdownLevel, sectionKey);
            currentSectionKey = sectionKey;
            if (documentTitle == null) {
                documentTitle = title;
            }
            sections.add(new DocumentPackage.SectionInfo(
                    sectionKey,
                    parentKey,
                    markdownLevel,
                    guessNodeType(title),
                    guessSemanticRole(title),
                    title,
                    sectionIndex * 10,
                    slug(title, sectionKey),
                    null,
                    null,
                    null,
                    Hashes.sha256(title)));
        }

        private void addTextBlock(String text) {
            ensureSection();
            blockIndex += 1;
            var payload = objectMapper.createObjectNode().put("text", text);
            blocks.add(new DocumentPackage.BlockInfo(
                    "block-" + blockIndex,
                    currentSectionKey,
                    nextSeq(),
                    BlockType.PARAGRAPH,
                    payload,
                    text,
                    null,
                    null,
                    null,
                    BigDecimal.valueOf(0.95),
                    Hashes.sha256(text)));
        }

        private void addCode(String language, String text) {
            ensureSection();
            blockIndex += 1;
            var normalizedLanguage = language == null || language.isBlank() ? "text" : language.trim();
            var payload = objectMapper.createObjectNode()
                    .put("language", normalizedLanguage)
                    .put("text", text);
            blocks.add(new DocumentPackage.BlockInfo(
                    "block-" + blockIndex,
                    currentSectionKey,
                    nextSeq(),
                    BlockType.CODE,
                    payload,
                    text,
                    normalizedLanguage,
                    null,
                    null,
                    BigDecimal.valueOf(0.98),
                    Hashes.sha256(text)));
        }

        private int addList(List<String> lines, int startIndex, boolean ordered) {
            ensureSection();
            blockIndex += 1;
            var items = objectMapper.createArrayNode();
            var consumed = 0;
            for (var i = startIndex; i < lines.size(); i++) {
                var item = listItem(lines.get(i));
                if (item == null || item.ordered() != ordered) {
                    break;
                }
                items.add(item.text());
                consumed += 1;
            }
            var payload = objectMapper.createObjectNode().set("items", items);
            var plainText = String.join("\n", iterable(items));
            blocks.add(new DocumentPackage.BlockInfo(
                    "block-" + blockIndex,
                    currentSectionKey,
                    nextSeq(),
                    ordered ? BlockType.ORDERED_LIST : BlockType.UNORDERED_LIST,
                    payload,
                    plainText,
                    null,
                    null,
                    null,
                    BigDecimal.valueOf(0.95),
                    Hashes.sha256(plainText)));
            return consumed;
        }

        private int addTable(List<String> lines, int startIndex) {
            ensureSection();
            blockIndex += 1;
            ArrayNode columns = objectMapper.valueToTree(tableCells(lines.get(startIndex)));
            var rows = objectMapper.createArrayNode();
            var consumed = 2;
            for (var i = startIndex + 2; i < lines.size(); i++) {
                if (!isTableRow(lines.get(i))) {
                    break;
                }
                rows.add(objectMapper.valueToTree(tableCells(lines.get(i))));
                consumed += 1;
            }
            var payload = objectMapper.createObjectNode();
            payload.set("columns", columns);
            payload.set("rows", rows);
            var rowText = new ArrayList<String>();
            rows.forEach(row -> row.forEach(cell -> rowText.add(cell.asText())));
            var plainText = String.join(" ", iterable(columns)) + "\n" + String.join(" ", rowText);
            blocks.add(new DocumentPackage.BlockInfo(
                    "block-" + blockIndex,
                    currentSectionKey,
                    nextSeq(),
                    BlockType.TABLE,
                    payload,
                    plainText,
                    null,
                    null,
                    null,
                    BigDecimal.valueOf(0.9),
                    Hashes.sha256(payload.toString())));
            return consumed;
        }

        private DocumentPackage toPackage() {
            ensureSection();
            var sourceBaseName = ImportDocumentNaming.baseName(
                    sourceFileName, List.of(".markdown", ".md"), "Markdown Document");
            var title = documentTitle == null ? sourceBaseName : documentTitle;
            var documentKey = ImportDocumentNaming.slug(sourceBaseName, "markdown-document");
            return new DocumentPackage(
                    "1.0",
                    new DocumentPackage.DocumentInfo(documentKey, title, "Imported from Markdown", "zh-CN", List.of()),
                    new DocumentPackage.VersionInfo(
                            "v1",
                            SourceType.MARKDOWN,
                            sourceFileName,
                            sourceSha256,
                            converterVersion,
                            Map.of("format", "markdown")),
                    sections,
                    blocks,
                    List.of());
        }

        private void ensureSection() {
            if (currentSectionKey == null) {
                addSection(1, "Markdown Document");
                // 合成章节只用于承载无标题正文，不能冒充从源文件识别出的文档标题。
                documentTitle = null;
            }
        }

        private int nextSeq() {
            return (int) blocks.stream().filter(block -> block.sectionKey().equals(currentSectionKey)).count() + 1;
        }

        private List<String> iterable(ArrayNode node) {
            var result = new ArrayList<String>();
            node.forEach(value -> result.add(value.asText()));
            return result;
        }

        private NodeType guessNodeType(String title) {
            return title.endsWith("?") || title.endsWith("？") ? NodeType.QUESTION : NodeType.SECTION;
        }

        private SemanticRole guessSemanticRole(String title) {
            return title.endsWith("?") || title.endsWith("？") ? SemanticRole.QUESTION : null;
        }
    }

    private record Heading(int level, String title) {
    }

    private record ListItem(boolean ordered, String text) {
    }
}
