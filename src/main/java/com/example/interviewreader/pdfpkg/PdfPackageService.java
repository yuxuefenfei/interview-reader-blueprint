package com.example.interviewreader.pdfpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.Hashes;
import com.example.interviewreader.document.BlockType;
import com.example.interviewreader.document.NodeType;
import com.example.interviewreader.document.SemanticRole;
import com.example.interviewreader.document.SourceType;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.importpkg.ImportDocumentNaming;
import com.example.interviewreader.importpkg.ImportIssueDto;
import com.example.interviewreader.importpkg.ImportIssueSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PdfPackageService {
    private static final int MAX_PAGES = 300;
    private static final int MAX_PARAGRAPH_CHARS = 1_200;
    private static final BigDecimal PDF_TEXT_CONFIDENCE = new BigDecimal("0.82");
    private static final BigDecimal PDF_HEURISTIC_CONFIDENCE = new BigDecimal("0.72");
    private static final BigDecimal PDF_TABLE_SNAPSHOT_CONFIDENCE = new BigDecimal("0.45");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\s*(\\d+[.)]|\\(?[一二三四五六七八九十]+[.)、])\\s+.+");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^\\s*([•·*]|-|–|—)\\s+.+");
    private static final Pattern CODE_KEYWORD_PATTERN = Pattern.compile("^(public|private|protected|class|interface|if|for|while|try|catch|return|throw|new|var|String|int|long|Map|List|Set|SELECT|UPDATE|INSERT|DELETE|CREATE|EXPLAIN|GET|POST|curl|redis-cli)\\b.*");
    private static final Pattern TABLE_GAP_PATTERN = Pattern.compile("\\S+\\s{2,}\\S+");

    private final ObjectMapper objectMapper;

    public PdfParseResult parse(byte[] fileBytes, String sourceFileName, String sourceSha256, String converterVersion) {
        if (!looksLikePdf(fileBytes)) {
            return new PdfParseResult(null, List.of(issue(ImportIssueSeverity.BLOCKING, "PDF_MAGIC_INVALID", "Uploaded file is not a PDF")), null);
        }
        try (var document = Loader.loadPDF(fileBytes)) {
            if (document.isEncrypted()) {
                return new PdfParseResult(null, List.of(issue(ImportIssueSeverity.BLOCKING, "PDF_ENCRYPTED", "Encrypted PDF is not supported")), null);
            }
            var pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                return new PdfParseResult(null, List.of(issue(ImportIssueSeverity.BLOCKING, "PDF_EMPTY", "PDF has no pages")), null);
            }
            if (pageCount > MAX_PAGES) {
                return new PdfParseResult(null, List.of(issue(ImportIssueSeverity.BLOCKING, "PDF_TOO_MANY_PAGES", "PDF page count exceeds " + MAX_PAGES)), null);
            }

            var issues = new ArrayList<ImportIssueDto>();
            var outlineSections = outlineSections(document);
            var hasOutline = !outlineSections.isEmpty();
            if (outlineSections.isEmpty()) {
                issues.add(issue(ImportIssueSeverity.WARNING, "PDF_OUTLINE_MISSING", "PDF has no outline; imported as a single document section"));
                outlineSections = List.of(new OutlineSection(
                        "s0001-" + slug(baseName(sourceFileName)),
                        null,
                        1,
                        titleFromFileName(sourceFileName),
                        1,
                        10));
            }
            var classification = hasOutline ? "TEXT_OUTLINE" : "TEXT_NO_OUTLINE";
            var sections = buildSections(outlineSections, pageCount);
            var blocks = buildBlocks(document, outlineSections, pageCount, issues);
            if (blocks.isEmpty()) {
                issues.add(issue(ImportIssueSeverity.BLOCKING, "PDF_TEXT_EMPTY", "PDF text layer did not produce readable content"));
            }
            var rawExtraction = rawExtraction(document, sourceFileName, sourceSha256, classification, outlineSections, blocks, issues);

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
                            SourceType.PDF,
                            sourceFileName,
                            sourceSha256,
                            converterVersion,
                            Map.of(
                                    "pageCount", pageCount,
                                    "classification", classification,
                                    "outlineCount", outlineSections.size(),
                                    "uncoveredTextPageCount", rawExtraction.preflight().uncoveredTextPageCount())),
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

        var leafSections = outlineSections.stream()
                .filter(section -> !parentKeys.contains(section.sectionKey()))
                .toList();
        var sectionEndPages = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < outlineSections.size(); index++) {
            var section = outlineSections.get(index);
            sectionEndPages.put(section.sectionKey(), sectionEndPage(outlineSections, index, pageCount));
        }
        var candidatesBySection = new LinkedHashMap<String, List<PdfBlockCandidate>>();
        for (var section : leafSections) {
            candidatesBySection.put(section.sectionKey(), new ArrayList<>());
        }

        // A page can contain several bookmarked sections. Parse it once, then let its headings move
        // the active owner so a physical PDF block is never copied into sibling sections.
        for (var page = 1; page <= pageCount; page++) {
            var currentPage = page;
            var pageSections = leafSections.stream()
                    .filter(section -> section.pageStart() <= currentPage && currentPage <= sectionEndPages.get(section.sectionKey()))
                    .toList();
            if (pageSections.isEmpty()) {
                continue;
            }
            OutlineSection activeSection = null;
            for (var candidate : blockCandidates(document, page, page, pageSections)) {
                var headingSection = sectionForHeading(candidate.plainText(), pageSections);
                if (headingSection != null) {
                    activeSection = headingSection;
                } else if (activeSection == null || !pageSections.contains(activeSection)) {
                    activeSection = pageSections.getFirst();
                }
                candidatesBySection.get(activeSection.sectionKey()).add(candidate);
            }
        }

        var blocks = new ArrayList<DocumentPackage.BlockInfo>();
        for (var section : leafSections) {
            var candidates = candidatesBySection.get(section.sectionKey());
            if (candidates.isEmpty()) {
                issues.add(new ImportIssueDto(
                        ImportIssueSeverity.WARNING,
                        "PDF_SECTION_EMPTY",
                        "No readable text extracted for PDF section",
                        section.pageStart(),
                        section.sectionKey(),
                        null));
                continue;
            }
            var seq = 1;
            for (var candidate : candidates) {
                var blockKey = section.sectionKey() + "-b" + String.format(Locale.ROOT, "%03d", seq);
                var plainText = candidate.plainText();
                blocks.add(new DocumentPackage.BlockInfo(
                        blockKey,
                        section.sectionKey(),
                        seq,
                        candidate.blockType(),
                        payload(candidate),
                        plainText,
                        candidate.language(),
                        candidate.pageNumber(),
                        pageBbox(candidate),
                        confidence(candidate),
                        Hashes.sha256(plainText)));
                if (candidate.blockType() == BlockType.TABLE_SNAPSHOT) {
                    issues.add(new ImportIssueDto(
                            ImportIssueSeverity.WARNING,
                            "PDF_TABLE_REVIEW_REQUIRED",
                            "Possible PDF table was preserved as a low-confidence snapshot for manual review",
                            candidate.pageNumber(),
                            section.sectionKey(),
                            blockKey));
                }
                seq++;
            }
        }
        return blocks;
    }

    private OutlineSection sectionForHeading(String candidateText, List<OutlineSection> sections) {
        var normalizedCandidate = normalizeHeading(candidateText);
        OutlineSection match = null;
        var longestTitle = 0;
        for (var section : sections) {
            var normalizedTitle = normalizeHeading(section.title());
            if (!normalizedTitle.isBlank()
                    && normalizedCandidate.startsWith(normalizedTitle)
                    && normalizedTitle.length() > longestTitle) {
                match = section;
                longestTitle = normalizedTitle.length();
            }
        }
        return match;
    }

    private static String normalizeHeading(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{P}\\p{S}]+", "").toLowerCase(Locale.ROOT);
    }
    private PdfRawExtraction rawExtraction(
            PDDocument document,
            String sourceFileName,
            String sourceSha256,
            String classification,
            List<OutlineSection> outlineSections,
            List<DocumentPackage.BlockInfo> blocks,
            List<ImportIssueDto> issues
    ) throws IOException {
        var pages = new ArrayList<RawPage>();
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        var blocksByPage = blockCountsByPage(blocks);
        for (var page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            var text = stripper.getText(document).stripTrailing();
            var mediaBox = document.getPage(page - 1).getMediaBox();
            var blockCount = blocksByPage.getOrDefault(page, 0);
            var covered = text.isBlank() || blockCount > 0;
            if (!covered) {
                issues.add(new ImportIssueDto(
                        ImportIssueSeverity.WARNING,
                        "PDF_PAGE_TEXT_UNMAPPED",
                        "PDF page has text but no normalized content block",
                        page,
                        null,
                        null));
            }
            pages.add(new RawPage(page, mediaBox.getWidth(), mediaBox.getHeight(), document.getPage(page - 1).getRotation(), text.length(), blockCount, covered, text));
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
        var preflight = preflight(document, sourceSha256, classification, outlineSections, pages, fontSummary(document));
        return new PdfRawExtraction(
                "1.0",
                preflight,
                sourceFileName,
                sourceSha256,
                document.getNumberOfPages(),
                false,
                classification,
                outline.size(),
                outline,
                pages);
    }

    private RawPreflight preflight(
            PDDocument document,
            String sourceSha256,
            String classification,
            List<OutlineSection> outlineSections,
            List<RawPage> pages,
            List<RawFontStat> fontSummary
    ) {
        var textPageCount = pages.stream().filter(page -> page.charCount() > 0).count();
        var uncoveredTextPageCount = pages.stream().filter(page -> page.charCount() > 0 && !page.coveredByBlocks()).count();
        var totalChars = pages.stream().mapToInt(RawPage::charCount).sum();
        var pageSizes = pages.stream()
                .map(page -> page.width() + "x" + page.height())
                .distinct()
                .toList();
        return new RawPreflight(
                sourceSha256,
                "application/pdf",
                document.getNumberOfPages(),
                false,
                outlineSections.size(),
                outlineSections.stream().mapToInt(OutlineSection::level).max().orElse(0),
                textPageCount,
                pages.size() - textPageCount,
                uncoveredTextPageCount,
                textPageCount == 0 ? 0 : totalChars / textPageCount,
                pageSizes,
                fontSummary,
                classification);
    }

    private List<RawFontStat> fontSummary(PDDocument document) throws IOException {
        var stripper = new FontStatsStripper();
        stripper.setSortByPosition(true);
        stripper.getText(document);
        return stripper.summary();
    }

    private Map<Integer, Integer> blockCountsByPage(List<DocumentPackage.BlockInfo> blocks) {
        var counts = new LinkedHashMap<Integer, Integer>();
        for (var block : blocks) {
            var page = block.sourcePage();
            if (page != null) {
                counts.merge(page, 1, Integer::sum);
            }
        }
        return counts;
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

    private List<PdfBlockCandidate> blockCandidates(PDDocument document, int startPage, int endPage, List<OutlineSection> pageSections) throws IOException {
        var result = new ArrayList<PdfBlockCandidate>();
        for (var page = startPage; page <= endPage; page++) {
            var stripper = new PositionedTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            var firstCandidate = result.size();
            addBlockCandidates(result, stripper.getText(document), page, document.getPage(page - 1).getMediaBox(), pageSections);
            attachLineBounds(result, firstCandidate, stripper.lines());
        }
        return result;
    }

    private void addBlockCandidates(List<PdfBlockCandidate> result, String text, int pageNumber, PDRectangle mediaBox, List<OutlineSection> pageSections) {
        var paragraph = new StringBuilder();
        var code = new StringBuilder();
        var listItems = new ArrayList<String>();
        var listType = BlockType.UNORDERED_LIST;
        var tableLines = new ArrayList<String>();
        for (var rawLine : text.lines().toList()) {
            var rawText = rawLine.stripTrailing();
            var line = rawLine.strip();
            if (looksLikeRunningHeader(line)) {
                continue;
            }
            if (sectionForHeading(line, pageSections) != null) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                flushCode(result, code, pageNumber, mediaBox);
                flushList(result, listItems, listType, pageNumber, mediaBox);
                flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
                appendLine(paragraph, line);
                continue;
            }
            var headingAnnotation = splitHeadingAndAnnotation(rawText);
            if (headingAnnotation != null) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                flushList(result, listItems, listType, pageNumber, mediaBox);
                flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
                appendLine(paragraph, headingAnnotation.heading());
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                appendCodeLine(code, headingAnnotation.annotation());
                continue;
            }
            if (line.isBlank() || line.matches("\\d{1,4}")) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                flushCode(result, code, pageNumber, mediaBox);
                flushList(result, listItems, listType, pageNumber, mediaBox);
                flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
                continue;
            }
            var codeProse = splitCodeAndProse(rawText);
            if (codeProse != null) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                flushList(result, listItems, listType, pageNumber, mediaBox);
                flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
                appendCodeLine(code, codeProse.code());
                flushCode(result, code, pageNumber, mediaBox);
                appendLine(paragraph, codeProse.prose());
                continue;
            }
            if (isCodeLine(rawText, line) || isCodeContinuation(code, line)) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                flushList(result, listItems, listType, pageNumber, mediaBox);
                flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
                appendCodeLine(code, rawText);
                continue;
            }
            if (isListLine(line)) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                flushCode(result, code, pageNumber, mediaBox);
                flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
                var nextListType = isOrderedListLine(line) ? BlockType.ORDERED_LIST : BlockType.UNORDERED_LIST;
                if (!listItems.isEmpty() && !listType.equals(nextListType)) {
                    flushList(result, listItems, listType, pageNumber, mediaBox);
                }
                listType = nextListType;
                listItems.add(stripListMarker(line));
                continue;
            }
            if (isTableLine(line)) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
                flushCode(result, code, pageNumber, mediaBox);
                flushList(result, listItems, listType, pageNumber, mediaBox);
                tableLines.add(line);
                continue;
            }
            flushCode(result, code, pageNumber, mediaBox);
            flushList(result, listItems, listType, pageNumber, mediaBox);
            flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
            appendLine(paragraph, line);
            if (paragraph.length() >= MAX_PARAGRAPH_CHARS || line.endsWith("。") || line.endsWith("；") || line.endsWith("：")) {
                flushParagraph(result, paragraph, pageNumber, mediaBox);
            }
        }
        flushParagraph(result, paragraph, pageNumber, mediaBox);
        flushCode(result, code, pageNumber, mediaBox);
        flushList(result, listItems, listType, pageNumber, mediaBox);
        flushTableSnapshot(result, tableLines, pageNumber, mediaBox);
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

    private void flushParagraph(List<PdfBlockCandidate> result, StringBuilder paragraph, int pageNumber, PDRectangle mediaBox) {
        if (paragraph.isEmpty()) {
            return;
        }
        var text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (text.length() > 1) {
            result.add(new PdfBlockCandidate(BlockType.PARAGRAPH, text, List.of(), null, pageNumber, mediaBox, null));
        }
    }

    private void flushCode(List<PdfBlockCandidate> result, StringBuilder code, int pageNumber, PDRectangle mediaBox) {
        if (code.isEmpty()) {
            return;
        }
        var text = trimCode(code.toString());
        code.setLength(0);
        if (text.length() > 1) {
            result.add(new PdfBlockCandidate(BlockType.CODE, text, List.of(), inferLanguage(text), pageNumber, mediaBox, null));
        }
    }

    private void flushList(List<PdfBlockCandidate> result, List<String> items, BlockType blockType, int pageNumber, PDRectangle mediaBox) {
        if (items.isEmpty()) {
            return;
        }
        result.add(new PdfBlockCandidate(blockType, String.join("\n", items), List.copyOf(items), null, pageNumber, mediaBox, null));
        items.clear();
    }

    private void flushTableSnapshot(List<PdfBlockCandidate> result, List<String> lines, int pageNumber, PDRectangle mediaBox) {
        if (lines.isEmpty()) {
            return;
        }
        var text = String.join("\n", lines);
        if (lines.size() >= 2) {
            result.add(new PdfBlockCandidate(BlockType.TABLE_SNAPSHOT, text, List.copyOf(lines), null, pageNumber, mediaBox, null));
        } else {
            result.add(new PdfBlockCandidate(BlockType.PARAGRAPH, text, List.of(), null, pageNumber, mediaBox, null));
        }
        lines.clear();
    }

    private NodeType nodeType(OutlineSection section) {
        if (section.level() == 1 && (section.title().contains("?") || section.title().contains("？"))) {
            return NodeType.QUESTION;
        }
        return switch (section.level()) {
            case 1 -> NodeType.CHAPTER;
            case 2 -> NodeType.SECTION;
            case 3 -> NodeType.SUBSECTION;
            default -> NodeType.OTHER;
        };
    }

    private SemanticRole semanticRole(OutlineSection section) {
        return section.title().contains("?") || section.title().contains("？") ? SemanticRole.QUESTION : null;
    }

    private JsonNode textPayload(String text) {
        return objectMapper.valueToTree(Map.of("text", text));
    }

    private JsonNode payload(PdfBlockCandidate candidate) {
        return switch (candidate.blockType()) {
            case UNORDERED_LIST, ORDERED_LIST -> objectMapper.valueToTree(Map.of("items", candidate.items()));
            case CODE -> objectMapper.valueToTree(Map.of(
                    "language", candidate.language() == null ? "text" : candidate.language(),
                    "text", candidate.text()));
            case TABLE_SNAPSHOT -> objectMapper.valueToTree(Map.of(
                    "lines", candidate.items(),
                    "text", candidate.text()));
            default -> textPayload(candidate.text());
        };
    }

    private BigDecimal confidence(PdfBlockCandidate candidate) {
        return switch (candidate.blockType()) {
            case PARAGRAPH -> PDF_TEXT_CONFIDENCE;
            case TABLE_SNAPSHOT -> PDF_TABLE_SNAPSHOT_CONFIDENCE;
            default -> PDF_HEURISTIC_CONFIDENCE;
        };
    }

    private JsonNode pageBbox(PdfBlockCandidate candidate) {
        var bounds = candidate.bounds();
        if (bounds == null) {
            return objectMapper.valueToTree(Map.of(
                    "page", candidate.pageNumber(),
                    "x", 0,
                    "y", 0,
                    "width", candidate.mediaBox().getWidth(),
                    "height", candidate.mediaBox().getHeight()));
        }
        return objectMapper.valueToTree(Map.of(
                "page", candidate.pageNumber(),
                "x", bounds.x(),
                "y", bounds.y(),
                "width", bounds.width(),
                "height", bounds.height()));
    }

    private ImportIssueDto issue(ImportIssueSeverity severity, String code, String message) {
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
        return ImportDocumentNaming.baseName(fileName, List.of(".pdf"), "PDF Document");
    }

    private static String cleanTitle(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String slug(String value) {
        return ImportDocumentNaming.slug(value, "document");
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

    private static boolean isListLine(String line) {
        return isOrderedListLine(line) || UNORDERED_LIST_PATTERN.matcher(line).matches();
    }

    private static boolean isOrderedListLine(String line) {
        return ORDERED_LIST_PATTERN.matcher(line).matches();
    }

    private static String stripListMarker(String line) {
        return line.replaceFirst("^\\s*(\\d+[.)]|\\(?[一二三四五六七八九十]+[.)、]|[•·*]|-|–|—)\\s+", "").strip();
    }

    private static boolean isTableLine(String line) {
        if (line.length() < 5) {
            return false;
        }
        if (line.chars().filter(value -> value == '|').count() >= 2) {
            return true;
        }
        if (!TABLE_GAP_PATTERN.matcher(line).find()) {
            return false;
        }
        var cells = line.split("\\s{2,}");
        return cells.length >= 2 && Stream.of(cells).allMatch(cell -> !cell.isBlank() && cell.length() <= 80);
    }

    private static boolean isCodeLine(String rawText, String line) {
        if (line.length() < 2) {
            return false;
        }
        var hasCodePunctuation = line.contains("{")
                || line.contains("}")
                || line.endsWith(";")
                || line.contains("->")
                || line.contains("==")
                || line.contains("!=");
        return hasCodePunctuation
                || line.matches("^@[A-Za-z][\\w.]*.*")
                || CODE_KEYWORD_PATTERN.matcher(line).matches()
                || rawText.startsWith("    ") && (line.contains("(") || line.contains("=") || line.contains(";"));
    }

    private static boolean isCodeContinuation(StringBuilder code, String line) {
        if (code.isEmpty() || line.isBlank()) {
            return false;
        }
        return line.matches("^(?://|/\\*|\\*|\\*/).*")
                || line.matches("^@[A-Za-z][\\w.]*.*")
                || line.matches("^(else|catch|finally)\\b.*")
                || line.matches("^[)}\\],;]+$");
    }

    private static CodeProseSplit splitCodeAndProse(String rawText) {
        var closingBrace = rawText.indexOf('}');
        if (closingBrace < 0) {
            return null;
        }
        var codeEnd = closingBrace;
        var proseStart = closingBrace + 1;
        while (proseStart < rawText.length()) {
            var character = rawText.charAt(proseStart);
            if (Character.isWhitespace(character)) {
                proseStart++;
                continue;
            }
            if (character == '}') {
                codeEnd = proseStart;
                proseStart++;
                continue;
            }
            break;
        }
        if (proseStart >= rawText.length() || !isCjk(rawText.charAt(proseStart))) {
            return null;
        }
        var code = rawText.substring(0, codeEnd + 1).stripTrailing();
        var prose = rawText.substring(proseStart).strip();
        return code.isBlank() || prose.isBlank() || !isCodeLine(code, code.strip()) ? null : new CodeProseSplit(code, prose);
    }

    private static HeadingAnnotationSplit splitHeadingAndAnnotation(String rawText) {
        var annotationStart = rawText.indexOf('@');
        if (annotationStart <= 0 || annotationStart == rawText.length() - 1) {
            return null;
        }
        var heading = rawText.substring(0, annotationStart).strip();
        var annotation = rawText.substring(annotationStart).strip();
        if (heading.isBlank() || heading.chars().noneMatch(value -> isCjk((char) value)) || !annotation.matches("^@[A-Za-z][\\w.]*.*")) {
            return null;
        }
        return new HeadingAnnotationSplit(heading, annotation);
    }

    private static boolean looksLikeRunningHeader(String line) {
        return line.length() < 100 && line.contains("系统化解析") && line.contains("面试题");
    }

    private static void appendCodeLine(StringBuilder code, String rawText) {
        if (!code.isEmpty()) {
            code.append('\n');
        }
        code.append(rawText.stripTrailing());
    }

    private static String trimCode(String value) {
        var lines = value.lines().toList();
        var minIndent = lines.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(PdfPackageService::leadingSpaces)
                .min()
                .orElse(0);
        return lines.stream()
                .map(line -> line.length() >= minIndent ? line.substring(minIndent).stripTrailing() : line.stripTrailing())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .strip();
    }

    private static int leadingSpaces(String value) {
        var count = 0;
        while (count < value.length() && value.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String inferLanguage(String text) {
        var upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("SELECT ") || upper.contains("UPDATE ") || upper.contains("INSERT ") || upper.contains("DELETE ")) {
            return "sql";
        }
        if (text.contains("{") || text.contains(";") || text.contains("public ") || text.contains("class ")) {
            return "java";
        }
        return "text";
    }

    public record PdfParseResult(DocumentPackage documentPackage, List<ImportIssueDto> issues, PdfRawExtraction rawExtraction) {
    }

    private record OutlineSection(String sectionKey, String parentKey, int level, String title, int pageStart, int sortOrder) {
    }

    public record PdfRawExtraction(
            String schemaVersion,
            RawPreflight preflight,
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

    public record RawPreflight(
            String sha256,
            String mimeType,
            int pageCount,
            boolean encrypted,
            int outlineCount,
            int outlineMaxDepth,
            long textPageCount,
            long scannedPageCountEstimate,
            long uncoveredTextPageCount,
            long averageCharsPerTextPage,
            List<String> pageSizes,
            List<RawFontStat> fontSummary,
            String classification
    ) {
    }

    public record RawFontStat(String fontName, float fontSize, int charCount) {
    }

    public record RawPage(int pageNumber, float width, float height, int rotation, int charCount, int blockCount, boolean coveredByBlocks, String text) {
    }

    private record CodeProseSplit(String code, String prose) {
    }

    private record HeadingAnnotationSplit(String heading, String annotation) {
    }

    private record PositionedLine(String text, BlockBounds bounds) {
    }

    private record BlockBounds(float x, float y, float width, float height) {
        private static BlockBounds merge(BlockBounds left, BlockBounds right) {
            var x = Math.min(left.x, right.x);
            var y = Math.min(left.y, right.y);
            var endX = Math.max(left.x + left.width, right.x + right.width);
            var endY = Math.max(left.y + left.height, right.y + right.height);
            return new BlockBounds(x, y, endX - x, endY - y);
        }
    }

    private record PdfBlockCandidate(
            BlockType blockType,
            String text,
            List<String> items,
            String language,
            int pageNumber,
            PDRectangle mediaBox,
            BlockBounds bounds
    ) {
        private String plainText() {
            return blockType == BlockType.UNORDERED_LIST || blockType == BlockType.ORDERED_LIST
                    ? String.join("\n", items)
                    : text;
        }
    }

    private void attachLineBounds(List<PdfBlockCandidate> candidates, int firstCandidate, List<PositionedLine> lines) {
        var lineIndex = 0;
        for (var index = firstCandidate; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            var target = compact(candidate.plainText());
            if (target.isBlank() || lineIndex >= lines.size()) {
                continue;
            }
            var merged = "";
            BlockBounds bounds = null;
            while (lineIndex < lines.size()) {
                var line = lines.get(lineIndex++);
                merged += compact(line.text());
                bounds = bounds == null ? line.bounds() : BlockBounds.merge(bounds, line.bounds());
                if (merged.length() >= target.length() || !target.startsWith(merged)) {
                    break;
                }
            }
            candidates.set(index, new PdfBlockCandidate(candidate.blockType(), candidate.text(), candidate.items(), candidate.language(),
                    candidate.pageNumber(), candidate.mediaBox(), bounds));
        }
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static final class PositionedTextStripper extends PDFTextStripper {
        private final List<PositionedLine> lines = new ArrayList<>();

        private PositionedTextStripper() throws IOException {
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            super.writeString(text, positions);
            if (text == null || text.isBlank() || positions == null || positions.isEmpty()) {
                return;
            }
            var minX = Float.MAX_VALUE;
            var minY = Float.MAX_VALUE;
            var maxX = Float.MIN_VALUE;
            var maxY = Float.MIN_VALUE;
            for (var position : positions) {
                minX = Math.min(minX, position.getXDirAdj());
                minY = Math.min(minY, position.getYDirAdj());
                maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
                maxY = Math.max(maxY, position.getYDirAdj() + position.getHeightDir());
            }
            lines.add(new PositionedLine(text.strip(), new BlockBounds(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY))));
        }

        private List<PositionedLine> lines() {
            return List.copyOf(lines);
        }
    }
    private static final class FontStatsStripper extends PDFTextStripper {
        private final Map<FontKey, Integer> counts = new LinkedHashMap<>();

        private FontStatsStripper() throws IOException {
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            for (var position : textPositions) {
                var font = position.getFont();
                var fontName = font == null ? "unknown" : font.getName();
                var key = new FontKey(fontName, roundFontSize(position.getFontSizeInPt()));
                var charCount = position.getUnicode() == null ? 0 : position.getUnicode().length();
                counts.merge(key, charCount, Integer::sum);
            }
        }

        private List<RawFontStat> summary() {
            return counts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> new RawFontStat(entry.getKey().fontName(), entry.getKey().fontSize(), entry.getValue()))
                    .sorted((left, right) -> {
                        var byChars = Integer.compare(right.charCount(), left.charCount());
                        if (byChars != 0) {
                            return byChars;
                        }
                        var byName = left.fontName().compareTo(right.fontName());
                        return byName != 0 ? byName : Float.compare(left.fontSize(), right.fontSize());
                    })
                    .limit(25)
                    .toList();
        }

        private static float roundFontSize(float value) {
            return Math.round(value * 10.0f) / 10.0f;
        }
    }

    private record FontKey(String fontName, float fontSize) {
    }
}
