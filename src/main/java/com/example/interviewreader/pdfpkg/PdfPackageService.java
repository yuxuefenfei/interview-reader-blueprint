package com.example.interviewreader.pdfpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.Hashes;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.importpkg.ImportIssueDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PdfPackageService {
    private static final int MAX_PAGES = 300;
    private static final int MAX_PARAGRAPH_CHARS = 1_200;
    private static final BigDecimal PDF_TEXT_CONFIDENCE = new BigDecimal("0.82");

    private final ObjectMapper objectMapper;

    public PdfPackageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PdfParseResult parse(byte[] fileBytes, String sourceFileName, String sourceSha256, String converterVersion) {
        if (!looksLikePdf(fileBytes)) {
            return new PdfParseResult(null, List.of(issue("BLOCKING", "PDF_MAGIC_INVALID", "Uploaded file is not a PDF")), null);
        }
        try (var document = Loader.loadPDF(fileBytes)) {
            if (document.isEncrypted()) {
                return new PdfParseResult(null, List.of(issue("BLOCKING", "PDF_ENCRYPTED", "Encrypted PDF is not supported")), null);
            }
            var pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                return new PdfParseResult(null, List.of(issue("BLOCKING", "PDF_EMPTY", "PDF has no pages")), null);
            }
            if (pageCount > MAX_PAGES) {
                return new PdfParseResult(null, List.of(issue("BLOCKING", "PDF_TOO_MANY_PAGES", "PDF page count exceeds " + MAX_PAGES)), null);
            }

            var issues = new ArrayList<ImportIssueDto>();
            var outlineSections = outlineSections(document);
            var hasOutline = !outlineSections.isEmpty();
            if (outlineSections.isEmpty()) {
                issues.add(issue("WARNING", "PDF_OUTLINE_MISSING", "PDF has no outline; imported as a single document section"));
                outlineSections = List.of(new OutlineSection(
                        "s0001-" + slug(baseName(sourceFileName)),
                        null,
                        1,
                        titleFromFileName(sourceFileName),
                        1,
                        10));
            }
            var classification = hasOutline ? "TEXT_OUTLINE" : "TEXT_NO_OUTLINE";
            var rawExtraction = rawExtraction(document, sourceFileName, sourceSha256, classification, outlineSections);

            var sections = buildSections(outlineSections, pageCount);
            var blocks = buildBlocks(document, outlineSections, pageCount, issues);
            if (blocks.isEmpty()) {
                issues.add(issue("BLOCKING", "PDF_TEXT_EMPTY", "PDF text layer did not produce readable content"));
            }

            var documentPackage = new DocumentPackage(
                    "1.0",
                    new DocumentPackage.DocumentInfo(
                            slug(baseName(sourceFileName)),
                            titleFromFileName(sourceFileName),
                            "PDF 自动结构化导入",
                            "zh-CN",
                            List.of("PDF")),
                    new DocumentPackage.VersionInfo(
                            sourceSha256.substring(0, 12),
                            "PDF",
                            sourceFileName,
                            sourceSha256,
                            converterVersion,
                            Map.of(
                                    "pageCount", pageCount,
                                    "classification", classification,
                                    "outlineCount", outlineSections.size())),
                    sections,
                    blocks,
                    List.of());
            return new PdfParseResult(documentPackage, issues, rawExtraction);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot parse PDF: " + exception.getMessage());
        }
    }

    private List<OutlineSection> outlineSections(PDDocument document) throws IOException {
        var outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null || !outline.hasChildren()) {
            return List.of();
        }
        var sections = new ArrayList<OutlineSection>();
        var counter = new AtomicInteger();
        collectOutline(document, outline.getFirstChild(), null, 1, counter, sections);
        return sections;
    }

    private void collectOutline(
            PDDocument document,
            PDOutlineItem item,
            String parentKey,
            int level,
            AtomicInteger counter,
            List<OutlineSection> sections
    ) throws IOException {
        var current = item;
        while (current != null) {
            var index = counter.incrementAndGet();
            var title = cleanTitle(current.getTitle(), "Section " + index);
            var sectionKey = "s" + String.format(Locale.ROOT, "%04d", index) + "-" + slug(title);
            var page = Math.max(1, pageNumber(document, current));
            sections.add(new OutlineSection(sectionKey, parentKey, level, title, page, index * 10));
            if (current.hasChildren()) {
                collectOutline(document, current.getFirstChild(), sectionKey, level + 1, counter, sections);
            }
            current = current.getNextSibling();
        }
    }

    private int pageNumber(PDDocument document, PDOutlineItem item) throws IOException {
        PDDestination destination = item.getDestination();
        if (destination == null && item.getAction() instanceof PDActionGoTo goTo) {
            destination = goTo.getDestination();
        }
        if (destination instanceof PDPageDestination pageDestination) {
            var pageNumber = pageDestination.retrievePageNumber();
            if (pageNumber >= 0) {
                return pageNumber + 1;
            }
            var page = pageDestination.getPage();
            if (page != null) {
                var pageIndex = document.getPages().indexOf(page);
                if (pageIndex >= 0) {
                    return pageIndex + 1;
                }
            }
        }
        return 1;
    }

    private List<DocumentPackage.SectionInfo> buildSections(List<OutlineSection> outlineSections, int pageCount) {
        var result = new ArrayList<DocumentPackage.SectionInfo>();
        for (var index = 0; index < outlineSections.size(); index++) {
            var section = outlineSections.get(index);
            result.add(new DocumentPackage.SectionInfo(
                    section.sectionKey(),
                    section.parentKey(),
                    section.level(),
                    nodeType(section),
                    semanticRole(section),
                    section.title(),
                    section.sortOrder(),
                    slug(section.title()),
                    section.pageStart(),
                    sectionEndPage(outlineSections, index, pageCount),
                    null,
                    Hashes.sha256(section.title())));
        }
        return result;
    }

    private List<DocumentPackage.BlockInfo> buildBlocks(
            PDDocument document,
            List<OutlineSection> outlineSections,
            int pageCount,
            List<ImportIssueDto> issues
    ) throws IOException {
        var parentKeys = new HashSet<String>();
        for (var section : outlineSections) {
            if (section.parentKey() != null) {
                parentKeys.add(section.parentKey());
            }
        }

        var blocks = new ArrayList<DocumentPackage.BlockInfo>();
        for (var index = 0; index < outlineSections.size(); index++) {
            var section = outlineSections.get(index);
            if (parentKeys.contains(section.sectionKey())) {
                continue;
            }
            var paragraphs = paragraphs(document, section.pageStart(), sectionEndPage(outlineSections, index, pageCount));
            if (paragraphs.isEmpty()) {
                issues.add(new ImportIssueDto(
                        "WARNING",
                        "PDF_SECTION_EMPTY",
                        "No readable text extracted for PDF section",
                        section.pageStart(),
                        section.sectionKey(),
                        null));
                continue;
            }
            var seq = 1;
            for (var paragraph : paragraphs) {
                blocks.add(new DocumentPackage.BlockInfo(
                        section.sectionKey() + "-p" + String.format(Locale.ROOT, "%03d", seq),
                        section.sectionKey(),
                        seq,
                        "paragraph",
                        textPayload(paragraph),
                        paragraph,
                        null,
                        section.pageStart(),
                        null,
                        PDF_TEXT_CONFIDENCE,
                        Hashes.sha256(paragraph)));
                seq++;
            }
        }
        return blocks;
    }

    private PdfRawExtraction rawExtraction(
            PDDocument document,
            String sourceFileName,
            String sourceSha256,
            String classification,
            List<OutlineSection> outlineSections
    ) throws IOException {
        var pages = new ArrayList<RawPage>();
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        for (var page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            var text = stripper.getText(document).stripTrailing();
            pages.add(new RawPage(page, text.length(), text));
        }
        var outline = outlineSections.stream()
                .map(section -> new RawOutline(
                        section.sectionKey(),
                        section.parentKey(),
                        section.level(),
                        section.title(),
                        section.pageStart(),
                        section.sortOrder()))
                .toList();
        return new PdfRawExtraction(
                "1.0",
                sourceFileName,
                sourceSha256,
                document.getNumberOfPages(),
                false,
                classification,
                outline.size(),
                outline,
                pages);
    }

    private int sectionEndPage(List<OutlineSection> outlineSections, int index, int pageCount) {
        var section = outlineSections.get(index);
        for (var nextIndex = index + 1; nextIndex < outlineSections.size(); nextIndex++) {
            var next = outlineSections.get(nextIndex);
            if (next.level() <= section.level()) {
                return Math.max(section.pageStart(), next.pageStart() - 1);
            }
        }
        return pageCount;
    }

    private List<String> paragraphs(PDDocument document, int startPage, int endPage) throws IOException {
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        var result = new ArrayList<String>();
        for (var page = startPage; page <= endPage; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            addParagraphs(result, stripper.getText(document));
        }
        return result;
    }

    private void addParagraphs(List<String> result, String text) {
        var paragraph = new StringBuilder();
        for (var rawLine : text.lines().toList()) {
            var line = rawLine.strip();
            if (line.isBlank() || line.matches("\\d{1,4}")) {
                flushParagraph(result, paragraph);
                continue;
            }
            appendLine(paragraph, line);
            if (paragraph.length() >= MAX_PARAGRAPH_CHARS || line.endsWith("。") || line.endsWith("；") || line.endsWith("：")) {
                flushParagraph(result, paragraph);
            }
        }
        flushParagraph(result, paragraph);
    }

    private void appendLine(StringBuilder paragraph, String line) {
        if (paragraph.isEmpty()) {
            paragraph.append(line);
            return;
        }
        if (paragraph.charAt(paragraph.length() - 1) == '-') {
            paragraph.setLength(paragraph.length() - 1);
            paragraph.append(line);
            return;
        }
        if (endsWithCjk(paragraph) || startsWithCjk(line)) {
            paragraph.append(line);
            return;
        }
        paragraph.append(' ').append(line);
    }

    private void flushParagraph(List<String> result, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        var text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (text.length() > 1) {
            result.add(text);
        }
    }

    private String nodeType(OutlineSection section) {
        if (section.level() == 1 && (section.title().contains("?") || section.title().contains("？"))) {
            return "QUESTION";
        }
        return switch (section.level()) {
            case 1 -> "CHAPTER";
            case 2 -> "SECTION";
            case 3 -> "SUBSECTION";
            default -> "OTHER";
        };
    }

    private String semanticRole(OutlineSection section) {
        return section.title().contains("?") || section.title().contains("？") ? "QUESTION" : null;
    }

    private JsonNode textPayload(String text) {
        return objectMapper.valueToTree(Map.of("text", text));
    }

    private ImportIssueDto issue(String severity, String code, String message) {
        return new ImportIssueDto(severity, code, message, null, null, null);
    }

    private static boolean looksLikePdf(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-';
    }

    private static String titleFromFileName(String fileName) {
        return cleanTitle(baseName(fileName), "PDF Document");
    }

    private static String baseName(String fileName) {
        var normalized = fileName == null || fileName.isBlank() ? "pdf-document.pdf" : fileName;
        var slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        var name = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }

    private static String cleanTitle(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String slug(String value) {
        var slug = cleanTitle(value, "document")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            return "document";
        }
        return slug.length() <= 80 ? slug : slug.substring(0, 80).replaceAll("-$", "");
    }

    private static boolean startsWithCjk(String value) {
        return !value.isBlank() && isCjk(value.charAt(0));
    }

    private static boolean endsWithCjk(StringBuilder value) {
        return !value.isEmpty() && isCjk(value.charAt(value.length() - 1));
    }

    private static boolean isCjk(char value) {
        return value >= '\u4e00' && value <= '\u9fff';
    }

    public record PdfParseResult(DocumentPackage documentPackage, List<ImportIssueDto> issues, PdfRawExtraction rawExtraction) {
    }

    private record OutlineSection(String sectionKey, String parentKey, int level, String title, int pageStart, int sortOrder) {
    }

    public record PdfRawExtraction(
            String schemaVersion,
            String sourceFileName,
            String sourceSha256,
            int pageCount,
            boolean encrypted,
            String classification,
            int outlineCount,
            List<RawOutline> outline,
            List<RawPage> pages
    ) {
    }

    public record RawOutline(String sectionKey, String parentKey, int level, String title, int pageStart, int sortOrder) {
    }

    public record RawPage(int pageNumber, int charCount, String text) {
    }
}
