package com.example.interviewreader.excelpkg;

import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.importpkg.ImportIssueDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ExcelPackageService {
    private static final String DOCUMENTS = "Documents";
    private static final String SECTIONS = "Sections";
    private static final String BLOCKS = "Blocks";
    private static final String ASSETS = "Assets";

    private static final List<String> DOCUMENT_HEADERS = List.of(
            "document_key", "title", "description", "language", "tags", "version_key",
            "source_type", "source_file_name", "source_sha256", "converter_version", "metadata_json");
    private static final List<String> SECTION_HEADERS = List.of(
            "document_key", "version_key", "section_key", "parent_section_key", "level", "node_type",
            "semantic_role", "title", "sort_order", "source_page_start", "source_page_end",
            "source_bbox_json", "content_hash", "tags", "enabled");
    private static final List<String> BLOCK_HEADERS = List.of(
            "version_key", "block_key", "section_key", "seq", "block_type", "text_markdown",
            "language", "payload_json", "source_page", "source_bbox_json", "confidence",
            "content_hash", "enabled");
    private static final List<String> ASSET_HEADERS = List.of(
            "version_key", "asset_key", "path", "mime_type", "sha256", "alt", "enabled");

    private final ObjectMapper objectMapper;

    public ParsedExcelPackage parse(byte[] workbookBytes) {
        var issues = new ArrayList<ImportIssueDto>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var documentsSheet = sheet(workbook, DOCUMENTS, issues);
            var sectionsSheet = sheet(workbook, SECTIONS, issues);
            var blocksSheet = sheet(workbook, BLOCKS, issues);
            var assetsSheet = sheet(workbook, ASSETS, issues);
            if (documentsSheet == null || sectionsSheet == null || blocksSheet == null || assetsSheet == null) {
                return new ParsedExcelPackage(null, issues);
            }

            var documentRows = rows(documentsSheet);
            if (documentRows.isEmpty()) {
                issues.add(issue("DOCUMENT_ROW_REQUIRED", "Documents sheet must contain one data row", "Documents!A2", null, null));
                return new ParsedExcelPackage(null, issues);
            }
            var documentRow = documentRows.getFirst();
            var documentKey = required(documentRow, "document_key", issues, null, null);
            var title = required(documentRow, "title", issues, null, null);
            var versionKey = value(documentRow, "version_key", "v1");
            var sourceType = value(documentRow, "source_type", "EXCEL");

            var document = new DocumentPackage.DocumentInfo(
                    documentKey,
                    title,
                    valueOrNull(documentRow, "description"),
                    value(documentRow, "language", "zh-CN"),
                    splitTags(valueOrNull(documentRow, "tags")));
            var version = new DocumentPackage.VersionInfo(
                    versionKey,
                    sourceType,
                    valueOrNull(documentRow, "source_file_name"),
                    valueOrNull(documentRow, "source_sha256"),
                    value(documentRow, "converter_version", "excel-0.1.0"),
                    readMap(documentRow, "metadata_json", issues));

            var sectionRows = rows(sectionsSheet).stream()
                    .filter(this::enabled)
                    .toList();
            validateSectionRows(sectionRows, issues);
            var sections = sectionRows.stream()
                    .map(row -> section(row, issues))
                    .toList();
            var blocks = rows(blocksSheet).stream()
                    .filter(this::enabled)
                    .map(row -> block(row, issues))
                    .toList();
            var assets = rows(assetsSheet).stream()
                    .filter(this::enabled)
                    .map(row -> asset(row, issues))
                    .toList();

            var documentPackage = new DocumentPackage("1.0", document, version, sections, blocks, assets);
            return new ParsedExcelPackage(documentPackage, issues);
        } catch (IOException exception) {
            issues.add(issue("EXCEL_READ_FAILED", "Cannot read Excel workbook: " + exception.getMessage(), null, null, null));
            return new ParsedExcelPackage(null, issues);
        }
    }

    public byte[] write(DocumentPackage documentPackage) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            writeDocuments(workbook, headerStyle, documentPackage);
            writeSections(workbook, headerStyle, documentPackage);
            writeBlocks(workbook, headerStyle, documentPackage);
            writeAssets(workbook, headerStyle, documentPackage);
            writeReadme(workbook, headerStyle);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write Excel workbook", exception);
        }
    }

    private void writeDocuments(Workbook workbook, CellStyle headerStyle, DocumentPackage documentPackage) {
        var sheet = workbook.createSheet(DOCUMENTS);
        writeHeader(sheet, headerStyle, DOCUMENT_HEADERS);
        var row = sheet.createRow(1);
        write(row, 0, documentPackage.document().documentKey());
        write(row, 1, documentPackage.document().title());
        write(row, 2, documentPackage.document().description());
        write(row, 3, documentPackage.document().language());
        write(row, 4, String.join(",", Objects.requireNonNullElse(documentPackage.document().tags(), List.of())));
        write(row, 5, documentPackage.version().versionKey());
        write(row, 6, documentPackage.version().sourceType());
        write(row, 7, documentPackage.version().sourceFileName());
        write(row, 8, documentPackage.version().sourceSha256());
        write(row, 9, documentPackage.version().converterVersion());
        write(row, 10, toJson(documentPackage.version().metadata()));
        autosize(sheet, DOCUMENT_HEADERS.size());
    }

    private void writeSections(Workbook workbook, CellStyle headerStyle, DocumentPackage documentPackage) {
        var sheet = workbook.createSheet(SECTIONS);
        writeHeader(sheet, headerStyle, SECTION_HEADERS);
        var rowIndex = 1;
        for (var section : documentPackage.sections()) {
            var row = sheet.createRow(rowIndex++);
            write(row, 0, documentPackage.document().documentKey());
            write(row, 1, documentPackage.version().versionKey());
            write(row, 2, section.sectionKey());
            write(row, 3, section.parentSectionKey());
            write(row, 4, section.level());
            write(row, 5, section.nodeType());
            write(row, 6, section.semanticRole());
            write(row, 7, section.title());
            write(row, 8, section.sortOrder());
            write(row, 9, section.sourcePageStart());
            write(row, 10, section.sourcePageEnd());
            write(row, 11, toJson(section.sourceBbox()));
            write(row, 12, section.contentHash());
            write(row, 13, "");
            write(row, 14, "TRUE");
        }
        autosize(sheet, SECTION_HEADERS.size());
    }

    private void writeBlocks(Workbook workbook, CellStyle headerStyle, DocumentPackage documentPackage) {
        var sheet = workbook.createSheet(BLOCKS);
        writeHeader(sheet, headerStyle, BLOCK_HEADERS);
        var rowIndex = 1;
        for (var block : documentPackage.blocks()) {
            var row = sheet.createRow(rowIndex++);
            write(row, 0, documentPackage.version().versionKey());
            write(row, 1, block.blockKey());
            write(row, 2, block.sectionKey());
            write(row, 3, block.seq());
            write(row, 4, block.blockType());
            write(row, 5, textMarkdown(block));
            write(row, 6, block.language());
            write(row, 7, toJson(block.payload()));
            write(row, 8, block.sourcePage());
            write(row, 9, toJson(block.sourceBbox()));
            write(row, 10, block.confidence());
            write(row, 11, block.contentHash());
            write(row, 12, "TRUE");
        }
        autosize(sheet, BLOCK_HEADERS.size());
    }

    private void writeAssets(Workbook workbook, CellStyle headerStyle, DocumentPackage documentPackage) {
        var sheet = workbook.createSheet(ASSETS);
        writeHeader(sheet, headerStyle, ASSET_HEADERS);
        var rowIndex = 1;
        for (var asset : documentPackage.assets()) {
            var row = sheet.createRow(rowIndex++);
            write(row, 0, documentPackage.version().versionKey());
            write(row, 1, asset.assetKey());
            write(row, 2, asset.path());
            write(row, 3, asset.mimeType());
            write(row, 4, asset.sha256());
            write(row, 5, asset.alt());
            write(row, 6, "TRUE");
        }
        autosize(sheet, ASSET_HEADERS.size());
    }

    private void writeReadme(Workbook workbook, CellStyle headerStyle) {
        var sheet = workbook.createSheet("README");
        writeHeader(sheet, headerStyle, List.of("name", "description"));
        write(sheet.createRow(1), 0, "Interview Reader Excel Package");
        write(sheet.getRow(1), 1, "Edit Documents, Sections, Blocks, and Assets, then import the workbook.");
        autosize(sheet, 2);
    }

    private Sheet sheet(Workbook workbook, String name, List<ImportIssueDto> issues) {
        var sheet = workbook.getSheet(name);
        if (sheet == null) {
            issues.add(issue("SHEET_MISSING", "Required sheet is missing: " + name, name + "!A1", null, null));
        }
        return sheet;
    }

    private List<ExcelRow> rows(Sheet sheet) {
        var headerRow = sheet.getRow(0);
        var headers = headers(headerRow);
        var result = new ArrayList<ExcelRow>();
        for (var i = 1; i <= sheet.getLastRowNum(); i++) {
            var row = sheet.getRow(i);
            if (row == null || !hasPrimaryValue(sheet.getSheetName(), headers, row)) {
                continue;
            }
            result.add(new ExcelRow(sheet.getSheetName(), i, headers, row));
        }
        return result;
    }

    private Map<String, Integer> headers(Row row) {
        var result = new LinkedHashMap<String, Integer>();
        if (row == null) {
            return result;
        }
        for (var i = 0; i < row.getLastCellNum(); i++) {
            var value = cellString(row.getCell(i));
            if (value != null && !value.isBlank()) {
                result.put(normalizeHeader(value), i);
            }
        }
        return result;
    }

    private DocumentPackage.SectionInfo section(ExcelRow row, List<ImportIssueDto> issues) {
        var sectionKey = required(row, "section_key", issues, null, null);
        return new DocumentPackage.SectionInfo(
                sectionKey,
                valueOrNull(row, "parent_section_key"),
                integer(row, "level", issues, sectionKey, null),
                value(row, "node_type", "SECTION"),
                valueOrNull(row, "semantic_role"),
                required(row, "title", issues, sectionKey, null),
                integer(row, "sort_order", issues, sectionKey, null),
                valueOrNull(row, "anchor"),
                integerOrNull(row, "source_page_start", issues, sectionKey, null),
                integerOrNull(row, "source_page_end", issues, sectionKey, null),
                jsonOrNull(row, "source_bbox_json", issues, sectionKey, null),
                valueOrNull(row, "content_hash"));
    }

    private void validateSectionRows(List<ExcelRow> rows, List<ImportIssueDto> issues) {
        var byKey = new HashMap<String, ExcelRow>();
        for (var row : rows) {
            var sectionKey = valueOrNull(row, "section_key");
            if (sectionKey != null) {
                byKey.put(sectionKey, row);
            }
        }
        for (var row : rows) {
            var sectionKey = valueOrNull(row, "section_key");
            var parentKey = valueOrNull(row, "parent_section_key");
            if (parentKey == null) {
                continue;
            }
            var parent = byKey.get(parentKey);
            if (parent == null) {
                issues.add(issue("PARENT_SECTION_MISSING", "parent_section_key does not exist: " + parentKey, row.cellRef("parent_section_key"), sectionKey, null));
                continue;
            }
            var level = parseInteger(valueOrNull(row, "level"));
            var parentLevel = parseInteger(valueOrNull(parent, "level"));
            if (level != null && parentLevel != null && level != parentLevel + 1) {
                issues.add(issue("SECTION_LEVEL_JUMP", "child section level must equal parent level + 1", row.cellRef("level"), sectionKey, null));
            }
        }
    }

    private DocumentPackage.BlockInfo block(ExcelRow row, List<ImportIssueDto> issues) {
        var blockKey = required(row, "block_key", issues, valueOrNull(row, "section_key"), null);
        var sectionKey = required(row, "section_key", issues, null, blockKey);
        var blockType = value(row, "block_type", "paragraph");
        return new DocumentPackage.BlockInfo(
                blockKey,
                sectionKey,
                integer(row, "seq", issues, sectionKey, blockKey),
                blockType,
                payload(row, blockType, issues, sectionKey, blockKey),
                value(row, "text_markdown", ""),
                valueOrNull(row, "language"),
                integerOrNull(row, "source_page", issues, sectionKey, blockKey),
                jsonOrNull(row, "source_bbox_json", issues, sectionKey, blockKey),
                decimalOrNull(row, "confidence", issues, sectionKey, blockKey),
                valueOrNull(row, "content_hash"));
    }

    private DocumentPackage.AssetInfo asset(ExcelRow row, List<ImportIssueDto> issues) {
        return new DocumentPackage.AssetInfo(
                required(row, "asset_key", issues, null, null),
                required(row, "path", issues, null, null),
                required(row, "mime_type", issues, null, null),
                required(row, "sha256", issues, null, null),
                valueOrNull(row, "alt"));
    }

    private JsonNode payload(ExcelRow row, String blockType, List<ImportIssueDto> issues, String sectionKey, String blockKey) {
        var payloadJson = valueOrNull(row, "payload_json");
        if (payloadJson != null) {
            return json(row, "payload_json", issues, sectionKey, blockKey);
        }
        var text = value(row, "text_markdown", "");
        return switch (blockType) {
            case "divider" -> objectMapper.createObjectNode();
            case "unordered_list", "ordered_list" -> objectMapper.createObjectNode()
                    .set("items", objectMapper.valueToTree(text.lines().filter(line -> !line.isBlank()).toList()));
            case "code" -> objectMapper.createObjectNode()
                    .put("language", value(row, "language", "text"))
                    .put("text", text);
            case "table", "image", "formula" -> {
                issues.add(issue("PAYLOAD_JSON_REQUIRED", blockType + " blocks require payload_json", row.cellRef("payload_json"), sectionKey, blockKey));
                yield objectMapper.createObjectNode();
            }
            default -> objectMapper.createObjectNode().put("text", text);
        };
    }

    private boolean enabled(ExcelRow row) {
        var enabled = valueOrNull(row, "enabled");
        return !"false".equalsIgnoreCase(enabled) && !"0".equals(enabled);
    }

    private String required(ExcelRow row, String key, List<ImportIssueDto> issues, String sectionKey, String blockKey) {
        var value = valueOrNull(row, key);
        if (value == null) {
            issues.add(issue("CELL_REQUIRED", key + " is required", row.cellRef(key), sectionKey, blockKey));
            return "";
        }
        return value;
    }

    private Integer integer(ExcelRow row, String key, List<ImportIssueDto> issues, String sectionKey, String blockKey) {
        return Objects.requireNonNullElse(integerOrNull(row, key, issues, sectionKey, blockKey), 0);
    }

    private Integer integerOrNull(ExcelRow row, String key, List<ImportIssueDto> issues, String sectionKey, String blockKey) {
        var value = valueOrNull(row, key);
        if (value == null) {
            return null;
        }
        var parsed = parseInteger(value);
        if (parsed != null) {
            return parsed;
        }
        issues.add(issue("CELL_INTEGER_INVALID", key + " must be an integer", row.cellRef(key), sectionKey, blockKey));
        return null;
    }

    private Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private BigDecimal decimalOrNull(ExcelRow row, String key, List<ImportIssueDto> issues, String sectionKey, String blockKey) {
        var value = valueOrNull(row, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            issues.add(issue("CELL_DECIMAL_INVALID", key + " must be a decimal", row.cellRef(key), sectionKey, blockKey));
            return null;
        }
    }

    private JsonNode json(ExcelRow row, String key, List<ImportIssueDto> issues, String sectionKey, String blockKey) {
        var value = valueOrNull(row, key);
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            issues.add(issue("CELL_JSON_INVALID", key + " contains invalid JSON", row.cellRef(key), sectionKey, blockKey));
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode jsonOrNull(ExcelRow row, String key, List<ImportIssueDto> issues, String sectionKey, String blockKey) {
        var value = valueOrNull(row, key);
        return value == null ? null : json(row, key, issues, sectionKey, blockKey);
    }

    private Map<String, Object> readMap(ExcelRow row, String key, List<ImportIssueDto> issues) {
        var value = valueOrNull(row, key);
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            issues.add(issue("CELL_JSON_INVALID", key + " contains invalid JSON", row.cellRef(key), null, null));
            return Map.of();
        }
    }

    private List<String> splitTags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Stream.of(value.split("[,，]")).map(String::trim).filter(tag -> !tag.isBlank()).toList();
    }

    private String value(ExcelRow row, String key, String defaultValue) {
        return Objects.requireNonNullElse(valueOrNull(row, key), defaultValue);
    }

    private String valueOrNull(ExcelRow row, String key) {
        var index = row.headers().get(normalizeHeader(key));
        if (index == null) {
            return null;
        }
        var value = cellString(row.row().getCell(index));
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ImportIssueDto issue(String code, String message, String cellRef, String sectionKey, String blockKey) {
        return new ImportIssueDto("BLOCKING", code, message, null, sectionKey, blockKey, cellRef);
    }

    private void writeHeader(Sheet sheet, CellStyle style, List<String> headers) {
        var row = sheet.createRow(0);
        for (var i = 0; i < headers.size(); i++) {
            var cell = row.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(style);
        }
    }

    private void write(Row row, int column, Object value) {
        if (value == null) {
            return;
        }
        var cell = row.createCell(column);
        switch (value) {
            case Integer integer -> cell.setCellValue(integer);
            case BigDecimal decimal -> cell.setCellValue(decimal.doubleValue());
            default -> cell.setCellValue(String.valueOf(value));
        }
    }

    private String textMarkdown(DocumentPackage.BlockInfo block) {
        if (block.plainText() != null && !block.plainText().isBlank()) {
            return block.plainText();
        }
        var payload = block.payload();
        return payload != null && payload.has("text") ? payload.get("text").asText() : "";
    }

    private String toJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize JSON", exception);
        }
    }

    private void autosize(Sheet sheet, int columns) {
        for (var i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 512, 16000));
        }
    }

    private boolean hasPrimaryValue(String sheetName, Map<String, Integer> headers, Row row) {
        if (ASSETS.equals(sheetName)) {
            var sha = cellValue(headers, row, "sha256");
            if (sha != null && sha.startsWith("填写")) {
                return false;
            }
        }
        var primaryKeys = switch (sheetName) {
            case DOCUMENTS -> List.of("document_key", "version_key", "title");
            case SECTIONS -> List.of("section_key", "title");
            case BLOCKS -> List.of("block_key", "section_key");
            case ASSETS -> List.of("asset_key", "path");
            default -> List.<String>of();
        };
        for (var key : primaryKeys) {
            var index = headers.get(key);
            if (index == null) {
                continue;
            }
            var value = cellString(row.getCell(index));
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String cellValue(Map<String, Integer> headers, Row row, String key) {
        var index = headers.get(key);
        return index == null ? null : cellString(row.getCell(index));
    }

    private String cellString(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> formulaString(cell);
            case BLANK, ERROR, _NONE -> null;
        };
    }

    private String formulaString(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private String normalizeHeader(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    }

    public record ParsedExcelPackage(DocumentPackage documentPackage, List<ImportIssueDto> issues) {
    }

    private record ExcelRow(String sheetName, int rowIndex, Map<String, Integer> headers, Row row) {
        private String cellRef(String key) {
            var index = headers.get(key);
            if (index == null) {
                return sheetName + "!A" + (rowIndex + 1);
            }
            return sheetName + "!" + new CellReference(rowIndex, index).formatAsString();
        }
    }
}
