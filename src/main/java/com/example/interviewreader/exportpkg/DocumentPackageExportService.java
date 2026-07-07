package com.example.interviewreader.exportpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DocumentPackageExportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DocumentPackageExportService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public DocumentPackage exportJsonPackage(UUID documentId, UUID versionId) {
        var header = loadHeader(documentId, versionId);
        var tags = jdbc.query("""
                SELECT t.name
                FROM tag t
                JOIN document_tag dt ON dt.tag_id = t.id
                WHERE dt.document_id = ?
                ORDER BY t.name
                """, (rs, rowNum) -> rs.getString("name"), documentId);
        var sections = jdbc.query("""
                SELECT n.node_key, p.node_key AS parent_node_key, n.level, n.node_type, n.semantic_role,
                       n.title, n.sort_order, n.anchor, n.source_page_start, n.source_page_end,
                       n.source_bbox, n.content_hash
                FROM content_node n
                LEFT JOIN content_node p ON p.id = n.parent_id
                WHERE n.version_id = ?
                ORDER BY n.path
                """, this::mapSection, versionId);
        var blocks = jdbc.query("""
                SELECT b.block_key, n.node_key AS section_key, b.seq, b.block_type, b.payload,
                       b.plain_text, b.language, b.source_page, b.source_bbox, b.confidence, b.content_hash
                FROM content_block b
                JOIN content_node n ON n.id = b.node_id
                WHERE b.version_id = ?
                ORDER BY n.path, b.seq
                """, this::mapBlock, versionId);
        var assets = jdbc.query("""
                SELECT asset_key, object_key, mime_type, sha256, metadata
                FROM asset
                WHERE version_id = ?
                ORDER BY asset_key
                """, this::mapAsset, versionId);

        return new DocumentPackage(
                header.schemaVersion(),
                new DocumentPackage.DocumentInfo(
                        header.documentKey(),
                        header.title(),
                        header.description(),
                        header.language(),
                        tags),
                new DocumentPackage.VersionInfo(
                        "v" + header.versionNo(),
                        header.sourceType(),
                        header.sourceFileName(),
                        header.sourceSha256(),
                        header.converterVersion(),
                        header.metadata()),
                sections,
                blocks,
                assets);
    }

    private Header loadHeader(UUID documentId, UUID versionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT d.code, d.title, d.description, v.version_no, v.source_type, v.source_file_name,
                           v.source_file_sha256, v.converter_version, v.schema_version, v.language, v.metadata
                    FROM document d
                    JOIN document_version v ON v.document_id = d.id
                    WHERE d.owner_id = ? AND d.id = ? AND v.id = ?
                    """, this::mapHeader, AppConstants.LOCAL_USER_ID, documentId, versionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document version not found");
        }
    }

    private Header mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new Header(
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getInt("version_no"),
                rs.getString("source_type"),
                rs.getString("source_file_name"),
                rs.getString("source_file_sha256"),
                rs.getString("converter_version"),
                rs.getString("schema_version"),
                rs.getString("language"),
                readMap(rs.getString("metadata")));
    }

    private DocumentPackage.SectionInfo mapSection(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentPackage.SectionInfo(
                rs.getString("node_key"),
                rs.getString("parent_node_key"),
                rs.getInt("level"),
                rs.getString("node_type"),
                rs.getString("semantic_role"),
                rs.getString("title"),
                rs.getInt("sort_order"),
                rs.getString("anchor"),
                (Integer) rs.getObject("source_page_start"),
                (Integer) rs.getObject("source_page_end"),
                readTreeOrNull(rs.getString("source_bbox")),
                rs.getString("content_hash"));
    }

    private DocumentPackage.BlockInfo mapBlock(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentPackage.BlockInfo(
                rs.getString("block_key"),
                rs.getString("section_key"),
                rs.getInt("seq"),
                rs.getString("block_type"),
                readTreeOrNull(rs.getString("payload")),
                rs.getString("plain_text"),
                rs.getString("language"),
                (Integer) rs.getObject("source_page"),
                readTreeOrNull(rs.getString("source_bbox")),
                rs.getBigDecimal("confidence"),
                rs.getString("content_hash"));
    }

    private DocumentPackage.AssetInfo mapAsset(ResultSet rs, int rowNum) throws SQLException {
        var metadata = readTreeOrNull(rs.getString("metadata"));
        var alt = metadata == null || metadata.get("alt") == null ? null : metadata.get("alt").asText();
        return new DocumentPackage.AssetInfo(
                rs.getString("asset_key"),
                rs.getString("object_key"),
                rs.getString("mime_type"),
                rs.getString("sha256").toLowerCase(Locale.ROOT),
                alt);
    }

    private JsonNode readTreeOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored JSON", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored metadata JSON", exception);
        }
    }

    private record Header(
            String documentKey,
            String title,
            String description,
            int versionNo,
            String sourceType,
            String sourceFileName,
            String sourceSha256,
            String converterVersion,
            String schemaVersion,
            String language,
            Map<String, Object> metadata
    ) {
    }
}
