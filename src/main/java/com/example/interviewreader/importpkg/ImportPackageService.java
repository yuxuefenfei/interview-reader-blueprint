package com.example.interviewreader.importpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.common.Hashes;
import com.example.interviewreader.excelpkg.ExcelPackageService;
import com.example.interviewreader.markdownpkg.MarkdownPackageService;
import com.example.interviewreader.pdfpkg.PdfPackageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportPackageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DocumentPackageValidator validator;
    private final ExcelPackageService excelPackageService;
    private final MarkdownPackageService markdownPackageService;
    private final PdfPackageService pdfPackageService;
    private final String converterVersion;

    public ImportPackageService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DocumentPackageValidator validator,
            ExcelPackageService excelPackageService,
            MarkdownPackageService markdownPackageService,
            PdfPackageService pdfPackageService,
            @Value("${interview-reader.converter-version}") String converterVersion
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.excelPackageService = excelPackageService;
        this.markdownPackageService = markdownPackageService;
        this.pdfPackageService = pdfPackageService;
        this.converterVersion = converterVersion;
    }

    @Transactional
    public ImportJobDto createImportJob(String sourceType, MultipartFile file) {
        var normalizedSourceType = sourceType.toUpperCase(Locale.ROOT);
        if (!Set.of("JSON_PACKAGE", "EXCEL", "MARKDOWN", "PDF").contains(normalizedSourceType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MVP currently supports JSON_PACKAGE, EXCEL, MARKDOWN and PDF imports");
        }
        var fileBytes = readBytes(file);
        var sourceSha256 = Hashes.sha256(fileBytes);
        var importFingerprint = Hashes.sha256(sourceSha256 + ":" + normalizedSourceType + ":" + converterVersion);
        var existingJob = findImportJobByFingerprint(importFingerprint);
        if (existingJob != null) {
            return existingJob;
        }
        var jobId = UUID.randomUUID();
        var statistics = Map.of("bytes", fileBytes.length);

        var sourceFileName = normalizedFileName(file.getOriginalFilename(), defaultFileName(normalizedSourceType));
        var parsed = parseSource(normalizedSourceType, fileBytes, sourceFileName, sourceSha256);
        var documentPackage = parsed.documentPackage();
        var issues = parsed.issues();

        var status = issues.stream().anyMatch(issue -> "BLOCKING".equals(issue.severity())) ? "REVIEW_REQUIRED" : "READY";
        jdbc.update("""
                INSERT INTO import_job(
                    id, owner_id, source_type, source_object_key, source_sha256, converter_version,
                    import_fingerprint, status, progress, current_stage, statistics,
                    raw_extraction_json, normalized_object_key, started_at, finished_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                jobId,
                AppConstants.LOCAL_USER_ID,
                normalizedSourceType,
                sourceFileName,
                sourceSha256,
                converterVersion,
                importFingerprint,
                status,
                100,
                "VALIDATING",
                toJson(statistics),
                toJsonOrNull(parsed.rawExtraction()),
                documentPackage == null ? "{}" : toJson(documentPackage));

        for (var issue : issues) {
            insertIssue(jobId, issue);
        }
        return getImportJob(jobId);
    }

    public ImportJobDto getImportJob(UUID jobId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, status, current_stage, progress, statistics, error_code, error_message
                    FROM import_job
                    WHERE id = ?
                    """, this::mapJob, jobId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import job not found");
        }
    }

    public List<ImportIssueDto> listIssues(UUID jobId) {
        return jdbc.query("""
                SELECT severity, issue_code, message, source_page, section_key, block_key, cell_ref
                FROM import_issue
                WHERE job_id = ?
                ORDER BY created_at, issue_code
                """, (rs, rowNum) -> new ImportIssueDto(
                rs.getString("severity"),
                rs.getString("issue_code"),
                rs.getString("message"),
                (Integer) rs.getObject("source_page"),
                rs.getString("section_key"),
                rs.getString("block_key"),
                rs.getString("cell_ref")), jobId);
    }

    public JsonNode rawExtraction(UUID jobId) {
        return snapshot(jobId, """
                SELECT raw_extraction_json
                FROM import_job
                WHERE owner_id = ? AND id = ?
                """, "raw extraction");
    }

    public JsonNode normalizedPackage(UUID jobId) {
        return snapshot(jobId, """
                SELECT normalized_object_key
                FROM import_job
                WHERE owner_id = ? AND id = ?
                """, "normalized package");
    }

    @Transactional
    public DocumentVersionDto commit(UUID jobId) {
        var job = loadJobForCommit(jobId);
        if ("IMPORTED".equals(job.status()) && job.resultVersionId() != null) {
            return getVersion(job.resultVersionId());
        }
        if (!"READY".equals(job.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only READY import jobs can be committed");
        }

        var documentPackage = readPackage(job.normalizedJson());
        var issues = validator.validate(documentPackage);
        if (!issues.isEmpty()) {
            issues.forEach(issue -> insertIssue(jobId, issue));
            jdbc.update("UPDATE import_job SET status = ?, current_stage = ? WHERE id = ?", "REVIEW_REQUIRED", "VALIDATING", jobId);
            throw new ApiException(HttpStatus.CONFLICT, "Import package has blocking validation issues");
        }

        var documentId = findDocumentId(documentPackage.document().documentKey());
        if (documentId == null) {
            documentId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO document(id, owner_id, code, title, description, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    documentId,
                    AppConstants.LOCAL_USER_ID,
                    documentPackage.document().documentKey(),
                    documentPackage.document().title(),
                    documentPackage.document().description(),
                    "DRAFT");
        } else {
            jdbc.update("""
                    UPDATE document
                    SET title = ?, description = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, documentPackage.document().title(), documentPackage.document().description(), documentId);
        }

        var versionId = UUID.randomUUID();
        var versionNo = nextVersionNo(documentId);
        jdbc.update("""
                INSERT INTO document_version(
                    id, document_id, version_no, source_type, source_file_name, source_file_sha256,
                    converter_version, schema_version, status, language, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                documentId,
                versionNo,
                documentPackage.version().sourceType().toUpperCase(Locale.ROOT),
                documentPackage.version().sourceFileName(),
                documentPackage.version().sourceSha256(),
                Objects.requireNonNullElse(documentPackage.version().converterVersion(), converterVersion),
                documentPackage.schemaVersion(),
                "DRAFT",
                Objects.requireNonNullElse(documentPackage.document().language(), "zh-CN"),
                toJson(Objects.requireNonNullElse(documentPackage.version().metadata(), Map.of())));

        insertSections(versionId, documentPackage);
        insertBlocks(versionId, documentPackage);
        insertAssets(versionId, documentPackage);
        upsertTags(documentId, documentPackage.document().tags());

        jdbc.update("UPDATE document SET current_version_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", versionId, documentId);
        jdbc.update("UPDATE import_job SET status = ?, result_version_id = ?, current_stage = ? WHERE id = ?", "IMPORTED", versionId, "COMMITTED", jobId);
        return new DocumentVersionDto(versionId, documentId, versionNo, "DRAFT", documentPackage.schemaVersion());
    }

    private void insertSections(UUID versionId, DocumentPackage documentPackage) {
        var sections = new ArrayList<>(documentPackage.sections());
        sections.sort(Comparator
                .comparing(DocumentPackage.SectionInfo::level)
                .thenComparing(section -> Objects.requireNonNullElse(section.sortOrder(), 0))
                .thenComparing(DocumentPackage.SectionInfo::sectionKey));
        var nodeIds = new HashMap<String, UUID>();
        var paths = new HashMap<String, String>();
        var blockTextBySection = blockTextBySection(documentPackage.blocks());

        for (var section : sections) {
            var id = UUID.randomUUID();
            var parentId = isBlank(section.parentSectionKey()) ? null : nodeIds.get(section.parentSectionKey());
            if (!isBlank(section.parentSectionKey()) && parentId == null) {
                throw new ApiException(HttpStatus.CONFLICT, "Parent section was not inserted: " + section.parentSectionKey());
            }
            var parentPath = isBlank(section.parentSectionKey()) ? null : paths.get(section.parentSectionKey());
            var pathPart = String.format("%06d", section.sortOrder());
            var path = parentPath == null ? pathPart : parentPath + "." + pathPart;
            var anchor = isBlank(section.anchor()) ? slug(section.sectionKey()) : section.anchor();
            var searchText = section.title() + "\n" + String.join("\n", blockTextBySection.getOrDefault(section.sectionKey(), List.of()));

            jdbc.update("""
                    INSERT INTO content_node(
                        id, version_id, parent_id, node_key, node_type, semantic_role, title, level,
                        path, sort_order, anchor, source_page_start, source_page_end, source_bbox,
                        content_hash, search_text
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    versionId,
                    parentId,
                    section.sectionKey(),
                    section.nodeType().toUpperCase(Locale.ROOT),
                    emptyToNull(section.semanticRole()),
                    section.title(),
                    section.level(),
                    path,
                    section.sortOrder(),
                    anchor,
                    section.sourcePageStart(),
                    section.sourcePageEnd(),
                    toJsonOrNull(section.sourceBbox()),
                    emptyToNull(section.contentHash()),
                    searchText);
            nodeIds.put(section.sectionKey(), id);
            paths.put(section.sectionKey(), path);
        }
    }

    private void insertBlocks(UUID versionId, DocumentPackage documentPackage) {
        var nodeIds = jdbc.query("""
                SELECT node_key, id
                FROM content_node
                WHERE version_id = ?
                """, rs -> {
            var result = new HashMap<String, UUID>();
            while (rs.next()) {
                result.put(rs.getString("node_key"), rs.getObject("id", UUID.class));
            }
            return result;
        }, versionId);

        for (var block : documentPackage.blocks()) {
            jdbc.update("""
                    INSERT INTO content_block(
                        id, version_id, node_id, block_key, seq, block_type, payload, plain_text,
                        language, source_page, source_bbox, confidence, content_hash
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    versionId,
                    nodeIds.get(block.sectionKey()),
                    block.blockKey(),
                    block.seq(),
                    block.blockType(),
                    toJson(block.payload()),
                    Objects.requireNonNullElse(block.plainText(), ""),
                    emptyToNull(block.language()),
                    block.sourcePage(),
                    toJsonOrNull(block.sourceBbox()),
                    block.confidence(),
                    emptyToNull(block.contentHash()));
        }
    }

    private void insertAssets(UUID versionId, DocumentPackage documentPackage) {
        for (var asset : documentPackage.assets()) {
            jdbc.update("""
                    INSERT INTO asset(
                        id, version_id, asset_key, object_key, original_name, mime_type,
                        sha256, size_bytes, metadata
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    versionId,
                    asset.assetKey(),
                    asset.path(),
                    asset.path(),
                    asset.mimeType(),
                    asset.sha256().toLowerCase(Locale.ROOT),
                    0,
                    toJson(Map.of("alt", Objects.requireNonNullElse(asset.alt(), ""))));
        }
    }

    private void upsertTags(UUID documentId, List<String> tags) {
        if (tags == null) {
            return;
        }
        for (var tag : tags.stream().filter(value -> !isBlank(value)).distinct().toList()) {
            var normalized = tag.trim().toLowerCase(Locale.ROOT);
            var tagId = findTagId(normalized);
            if (tagId == null) {
                tagId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO tag(id, owner_id, name, normalized_name)
                        VALUES (?, ?, ?, ?)
                        """, tagId, AppConstants.LOCAL_USER_ID, tag.trim(), normalized);
            }
            var existingLinks = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM document_tag
                    WHERE document_id = ? AND tag_id = ?
                    """, Integer.class, documentId, tagId);
            if (existingLinks == null || existingLinks == 0) {
                jdbc.update("""
                        INSERT INTO document_tag(document_id, tag_id)
                        VALUES (?, ?)
                        """, documentId, tagId);
            }
        }
    }

    private Map<String, List<String>> blockTextBySection(List<DocumentPackage.BlockInfo> blocks) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var block : blocks) {
            result.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>())
                    .add(Objects.requireNonNullElse(block.plainText(), ""));
        }
        return result;
    }

    private UUID findDocumentId(String documentKey) {
        var ids = jdbc.query("""
                SELECT id
                FROM document
                WHERE owner_id = ? AND code = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), AppConstants.LOCAL_USER_ID, documentKey);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private UUID findTagId(String normalized) {
        var ids = jdbc.query("""
                SELECT id
                FROM tag
                WHERE owner_id = ? AND normalized_name = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), AppConstants.LOCAL_USER_ID, normalized);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private ImportJobDto findImportJobByFingerprint(String fingerprint) {
        var jobs = jdbc.query("""
                SELECT id, status, current_stage, progress, statistics, error_code, error_message
                FROM import_job
                WHERE owner_id = ? AND import_fingerprint = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, this::mapJob, AppConstants.LOCAL_USER_ID, fingerprint);
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    private int nextVersionNo(UUID documentId) {
        var current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0)
                FROM document_version
                WHERE document_id = ?
                """, Integer.class, documentId);
        return current == null ? 1 : current + 1;
    }

    private ImportJobRow loadJobForCommit(UUID jobId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, status, result_version_id, normalized_object_key
                    FROM import_job
                    WHERE id = ?
                    """, (rs, rowNum) -> new ImportJobRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getObject("result_version_id", UUID.class),
                    rs.getString("normalized_object_key")), jobId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import job not found");
        }
    }

    private DocumentVersionDto getVersion(UUID versionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, document_id, version_no, status, schema_version
                    FROM document_version
                    WHERE id = ?
                    """, (rs, rowNum) -> new DocumentVersionDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("document_id", UUID.class),
                    rs.getInt("version_no"),
                    rs.getString("status"),
                    rs.getString("schema_version")), versionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document version not found");
        }
    }

    private DocumentPackage readPackage(String json) {
        try {
            return objectMapper.readValue(json, DocumentPackage.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Staged JSON package is invalid");
        }
    }

    private JsonNode snapshot(UUID jobId, String sql, String label) {
        var rows = jdbc.query(sql, (rs, rowNum) -> rs.getString(1), AppConstants.LOCAL_USER_ID, jobId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import job not found");
        }
        var json = rows.getFirst();
        if (isBlank(json) || "{}".equals(json)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import job has no " + label + " snapshot");
        }
        return readTree(json);
    }

    private ImportJobDto mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new ImportJobDto(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("current_stage"),
                rs.getInt("progress"),
                readMap(rs.getString("statistics")),
                rs.getString("error_code"),
                rs.getString("error_message"));
    }

    private void insertIssue(UUID jobId, ImportIssueDto issue) {
        jdbc.update("""
                INSERT INTO import_issue(
                    id, job_id, severity, issue_code, message, source_page, section_key, block_key, cell_ref, details
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                jobId,
                issue.severity(),
                issue.issueCode(),
                issue.message(),
                issue.sourcePage(),
                issue.sectionKey(),
                issue.blockKey(),
                issue.cellRef(),
                "{}");
    }

    private ParsedSource parseSource(String sourceType, byte[] fileBytes, String sourceFileName, String sourceSha256) {
        if ("EXCEL".equals(sourceType)) {
            var parsed = excelPackageService.parse(fileBytes);
            var issues = new ArrayList<>(parsed.issues());
            if (parsed.documentPackage() != null) {
                issues.addAll(validator.validate(parsed.documentPackage()));
            }
            return new ParsedSource(parsed.documentPackage(), issues, null);
        }
        if ("MARKDOWN".equals(sourceType)) {
            var documentPackage = markdownPackageService.parse(fileBytes, sourceFileName, sourceSha256, converterVersion);
            return new ParsedSource(documentPackage, validator.validate(documentPackage), null);
        }
        if ("PDF".equals(sourceType)) {
            var parsed = pdfPackageService.parse(fileBytes, sourceFileName, sourceSha256, converterVersion);
            var issues = new ArrayList<>(parsed.issues());
            if (parsed.documentPackage() != null) {
                issues.addAll(validator.validate(parsed.documentPackage()));
            }
            return new ParsedSource(parsed.documentPackage(), issues, parsed.rawExtraction());
        }
        var json = new String(fileBytes, StandardCharsets.UTF_8);
        try {
            var documentPackage = objectMapper.readValue(json, DocumentPackage.class);
            return new ParsedSource(documentPackage, validator.validate(documentPackage), null);
        } catch (JsonProcessingException exception) {
            return new ParsedSource(null, List.of(new ImportIssueDto("BLOCKING", "JSON_INVALID", exception.getOriginalMessage(), null, null, null)), null);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read uploaded file");
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Stored import snapshot is invalid");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize JSON", exception);
        }
    }

    private String toJsonOrNull(Object value) {
        return value == null ? null : toJson(value);
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String normalizedFileName(String fileName, String fallback) {
        if (isBlank(fileName)) {
            return fallback;
        }
        var slashIndex = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        var name = slashIndex >= 0 ? fileName.substring(slashIndex + 1) : fileName;
        return repairUtf8Mojibake(name.strip());
    }

    private static String repairUtf8Mojibake(String value) {
        var likelyLatin1Mojibake = value.chars().anyMatch(character -> character >= 0x0080 && character <= 0x00FF);
        if (!likelyLatin1Mojibake) {
            return value;
        }
        var bytes = new byte[value.length()];
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character > 0x00FF) {
                return value;
            }
            bytes[index] = (byte) character;
        }
        var repaired = new String(bytes, StandardCharsets.UTF_8);
        return repaired.indexOf('\uFFFD') >= 0 ? value : repaired;
    }

    private static String defaultFileName(String sourceType) {
        return switch (sourceType) {
            case "EXCEL" -> "document-package.xlsx";
            case "MARKDOWN" -> "document.md";
            case "PDF" -> "document.pdf";
            default -> "document-package.json";
        };
    }

    private record ImportJobRow(UUID id, String status, UUID resultVersionId, String normalizedJson) {
    }

    private record ParsedSource(DocumentPackage documentPackage, List<ImportIssueDto> issues, Object rawExtraction) {
    }
}
