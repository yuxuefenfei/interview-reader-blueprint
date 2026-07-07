package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentDtos.ContentBlock;
import com.example.interviewreader.document.DocumentDtos.DocumentSummary;
import com.example.interviewreader.document.DocumentDtos.NodeContent;
import com.example.interviewreader.document.DocumentDtos.ReadingProgress;
import com.example.interviewreader.document.DocumentDtos.SearchHit;
import com.example.interviewreader.document.DocumentDtos.TocNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentQueryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DocumentQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<DocumentSummary> listDocuments(String query) {
        var normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        if (normalizedQuery.isBlank()) {
            return jdbc.query("""
                    SELECT d.id, d.code, d.title, d.description, d.current_version_id,
                           COALESCE(rp.progress_ratio, 0) AS progress_ratio
                    FROM document d
                    LEFT JOIN reading_progress rp ON rp.document_id = d.id AND rp.user_id = ?
                    WHERE d.owner_id = ?
                    ORDER BY d.updated_at DESC, d.title
                    """, this::mapDocumentSummary, AppConstants.LOCAL_USER_ID, AppConstants.LOCAL_USER_ID);
        }
        var like = "%" + normalizedQuery + "%";
        return jdbc.query("""
                SELECT d.id, d.code, d.title, d.description, d.current_version_id,
                       COALESCE(rp.progress_ratio, 0) AS progress_ratio
                FROM document d
                LEFT JOIN reading_progress rp ON rp.document_id = d.id AND rp.user_id = ?
                WHERE d.owner_id = ? AND (LOWER(d.title) LIKE ? OR LOWER(d.code) LIKE ?)
                ORDER BY d.updated_at DESC, d.title
                """, this::mapDocumentSummary, AppConstants.LOCAL_USER_ID, AppConstants.LOCAL_USER_ID, like, like);
    }

    public DocumentSummary getDocument(UUID documentId) {
        try {
            return jdbc.queryForObject("""
                    SELECT d.id, d.code, d.title, d.description, d.current_version_id,
                           COALESCE(rp.progress_ratio, 0) AS progress_ratio
                    FROM document d
                    LEFT JOIN reading_progress rp ON rp.document_id = d.id AND rp.user_id = ?
                    WHERE d.owner_id = ? AND d.id = ?
                    """, this::mapDocumentSummary, AppConstants.LOCAL_USER_ID, AppConstants.LOCAL_USER_ID, documentId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }

    @Transactional
    public void publish(UUID documentId, UUID versionId) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM document_version
                WHERE id = ? AND document_id = ?
                """, Integer.class, versionId, documentId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Version not found");
        }
        var previousVersionId = previousPublishedVersionId(documentId, versionId);
        jdbc.update("""
                UPDATE document_version
                SET status = 'RETIRED'
                WHERE document_id = ? AND status = 'PUBLISHED' AND id <> ?
                """, documentId, versionId);
        jdbc.update("""
                UPDATE document_version
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, versionId);
        jdbc.update("""
                UPDATE document
                SET status = 'PUBLISHED', current_version_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, versionId, documentId);
        migrateReadingProgress(documentId, previousVersionId, versionId);
    }

    public List<TocNode> toc(UUID versionId) {
        var rows = jdbc.query("""
                SELECT id, parent_id, title, level, node_type, semantic_role, anchor, source_page_start, sort_order
                FROM content_node
                WHERE version_id = ?
                ORDER BY path
                """, this::mapMutableTocNode, versionId);
        if (rows.isEmpty()) {
            ensureVersionExists(versionId);
            return List.of();
        }
        var byId = new LinkedHashMap<UUID, MutableTocNode>();
        rows.forEach(row -> byId.put(row.id, row));
        var roots = new ArrayList<MutableTocNode>();
        for (var row : rows) {
            if (row.parentId == null) {
                roots.add(row);
            } else {
                var parent = byId.get(row.parentId);
                if (parent != null) {
                    parent.children.add(row);
                }
            }
        }
        roots.sort(Comparator.comparingInt(node -> node.sortOrder));
        return roots.stream().map(MutableTocNode::toDto).toList();
    }

    public NodeContent content(UUID versionId, UUID nodeId, Integer afterSeq, Integer limit) {
        var node = node(versionId, nodeId);
        var safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 100));
        var rows = jdbc.query("""
                SELECT id, block_key, seq, block_type, payload, plain_text, source_page, confidence
                FROM content_block
                WHERE version_id = ? AND node_id = ? AND seq > ?
                ORDER BY seq
                LIMIT ?
                """, this::mapContentBlock, versionId, nodeId, afterSeq == null ? 0 : afterSeq, safeLimit + 1);
        Integer nextAfterSeq = null;
        if (rows.size() > safeLimit) {
            nextAfterSeq = rows.get(safeLimit - 1).seq();
            rows = rows.subList(0, safeLimit);
        }
        return new NodeContent(node, rows, nextAfterSeq);
    }

    public List<SearchHit> search(String q, UUID documentId, Integer limit) {
        if (q == null || q.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "q is required");
        }
        var safeLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        var like = "%" + q.trim().toLowerCase() + "%";
        var params = new ArrayList<Object>();
        params.add(AppConstants.LOCAL_USER_ID);
        params.add(like);
        params.add(like);

        var documentFilter = "";
        if (documentId != null) {
            documentFilter = " AND d.id = ?";
            params.add(documentId);
        }
        params.add(safeLimit);

        return jdbc.query("""
                SELECT d.id AS document_id, v.id AS version_id, n.id AS node_id, b.id AS block_id,
                       n.title, b.plain_text
                FROM content_block b
                JOIN content_node n ON n.id = b.node_id
                JOIN document_version v ON v.id = b.version_id
                JOIN document d ON d.id = v.document_id
                WHERE d.owner_id = ?
                  AND (LOWER(b.plain_text) LIKE ? OR LOWER(n.title) LIKE ?)
                """ + documentFilter + """
                ORDER BY d.updated_at DESC, n.path, b.seq
                LIMIT ?
                """, this::mapSearchHit, params.toArray());
    }

    public ReadingProgress getProgress(UUID documentId) {
        var rows = jdbc.query("""
                SELECT version_id, section_id, block_id, char_offset, block_viewport_offset,
                       progress_ratio, client_updated_at, device_id, revision
                FROM reading_progress
                WHERE user_id = ? AND document_id = ?
                """, this::mapReadingProgress, AppConstants.LOCAL_USER_ID, documentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public ReadingProgress upsertProgress(UUID documentId, ReadingProgress progress) {
        var existingId = jdbc.query("""
                SELECT id
                FROM reading_progress
                WHERE user_id = ? AND document_id = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), AppConstants.LOCAL_USER_ID, documentId);
        if (existingId.isEmpty()) {
            jdbc.update("""
                    INSERT INTO reading_progress(
                        id, user_id, document_id, version_id, section_id, block_id, char_offset,
                        block_viewport_offset, progress_ratio, client_updated_at, device_id, revision
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """,
                    UUID.randomUUID(),
                    AppConstants.LOCAL_USER_ID,
                    documentId,
                    progress.versionId(),
                    progress.sectionId(),
                    progress.blockId(),
                    progress.charOffset(),
                    progress.blockViewportOffset(),
                    progress.progressRatio(),
                    progress.clientUpdatedAt(),
                    progress.deviceId());
        } else {
            jdbc.update("""
                    UPDATE reading_progress
                    SET version_id = ?, section_id = ?, block_id = ?, char_offset = ?,
                        block_viewport_offset = ?, progress_ratio = ?, client_updated_at = ?,
                        device_id = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    progress.versionId(),
                    progress.sectionId(),
                    progress.blockId(),
                    progress.charOffset(),
                    progress.blockViewportOffset(),
                    progress.progressRatio(),
                    progress.clientUpdatedAt(),
                    progress.deviceId(),
                    existingId.getFirst());
        }
        return getProgress(documentId);
    }

    private TocNode node(UUID versionId, UUID nodeId) {
        try {
            var row = jdbc.queryForObject("""
                    SELECT id, parent_id, title, level, node_type, semantic_role, anchor, source_page_start, sort_order
                    FROM content_node
                    WHERE version_id = ? AND id = ?
                    """, this::mapMutableTocNode, versionId, nodeId);
            return row.toDto();
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Content node not found");
        }
    }

    private void ensureVersionExists(UUID versionId) {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE id = ?", Integer.class, versionId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Version not found");
        }
    }

    private UUID previousPublishedVersionId(UUID documentId, UUID nextVersionId) {
        var rows = jdbc.query("""
                SELECT id
                FROM document_version
                WHERE document_id = ? AND status = 'PUBLISHED' AND id <> ?
                ORDER BY published_at DESC, version_no DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), documentId, nextVersionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void migrateReadingProgress(UUID documentId, UUID previousVersionId, UUID nextVersionId) {
        if (previousVersionId == null || previousVersionId.equals(nextVersionId)) {
            return;
        }
        var rows = jdbc.query("""
                SELECT id, section_id, block_id, char_offset, block_viewport_offset, progress_ratio
                FROM reading_progress
                WHERE user_id = ? AND document_id = ? AND version_id = ?
                """, (rs, rowNum) -> new ProgressMigrationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("section_id", UUID.class),
                        rs.getObject("block_id", UUID.class),
                        rs.getInt("char_offset"),
                        rs.getInt("block_viewport_offset"),
                        rs.getBigDecimal("progress_ratio")),
                AppConstants.LOCAL_USER_ID,
                documentId,
                previousVersionId);

        for (var row : rows) {
            findProgressTarget(row, nextVersionId).ifPresent(target -> updateMigratedProgress(row, target, nextVersionId));
        }
    }

    private Optional<ProgressTarget> findProgressTarget(ProgressMigrationRow row, UUID nextVersionId) {
        return targetByBlockKey(row.blockId(), nextVersionId)
                .or(() -> targetByContentHash(row.blockId(), nextVersionId))
                .or(() -> targetBySectionPathAndFirstText(row.sectionId(), nextVersionId))
                .or(() -> targetBySectionKey(row.sectionId(), nextVersionId))
                .or(() -> firstBlockInVersion(nextVersionId))
                .or(() -> firstSectionInVersion(nextVersionId));
    }

    private void updateMigratedProgress(ProgressMigrationRow row, ProgressTarget target, UUID nextVersionId) {
        var charOffset = target.resetPosition() ? 0 : row.charOffset();
        var blockViewportOffset = target.resetPosition() ? 0 : row.blockViewportOffset();
        var progressRatio = target.documentStart() ? BigDecimal.ZERO : row.progressRatio();
        jdbc.update("""
                UPDATE reading_progress
                SET version_id = ?, section_id = ?, block_id = ?, char_offset = ?,
                    block_viewport_offset = ?, progress_ratio = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                nextVersionId,
                target.sectionId(),
                target.blockId(),
                charOffset,
                blockViewportOffset,
                progressRatio,
                row.id());
    }

    private Optional<ProgressTarget> targetByBlockKey(UUID oldBlockId, UUID nextVersionId) {
        if (oldBlockId == null) {
            return Optional.empty();
        }
        return firstTarget("""
                SELECT n.id AS section_id, b.id AS block_id
                FROM content_block old_block
                JOIN content_block b ON b.version_id = ? AND b.block_key = old_block.block_key
                JOIN content_node n ON n.id = b.node_id
                WHERE old_block.id = ?
                ORDER BY b.seq
                LIMIT 1
                """, false, false, nextVersionId, oldBlockId);
    }

    private Optional<ProgressTarget> targetByContentHash(UUID oldBlockId, UUID nextVersionId) {
        if (oldBlockId == null) {
            return Optional.empty();
        }
        return firstTarget("""
                SELECT n.id AS section_id, b.id AS block_id
                FROM content_block old_block
                JOIN content_block b ON b.version_id = ? AND b.content_hash = old_block.content_hash
                JOIN content_node n ON n.id = b.node_id
                WHERE old_block.id = ? AND old_block.content_hash IS NOT NULL AND old_block.content_hash <> ''
                ORDER BY n.path, b.seq
                LIMIT 1
                """, false, false, nextVersionId, oldBlockId);
    }

    private Optional<ProgressTarget> targetBySectionPathAndFirstText(UUID oldSectionId, UUID nextVersionId) {
        if (oldSectionId == null) {
            return Optional.empty();
        }
        var anchors = jdbc.query("""
                SELECT n.path, b.plain_text
                FROM content_node n
                LEFT JOIN content_block b ON b.node_id = n.id
                WHERE n.id = ?
                ORDER BY b.seq
                LIMIT 1
                """, (rs, rowNum) -> new SectionAnchor(rs.getString("path"), textPrefix(rs.getString("plain_text"))), oldSectionId);
        if (anchors.isEmpty() || anchors.getFirst().firstTextPrefix().isBlank()) {
            return Optional.empty();
        }
        var anchor = anchors.getFirst();
        var candidates = jdbc.query("""
                SELECT n.id AS section_id, b.id AS block_id, b.plain_text
                FROM content_node n
                LEFT JOIN content_block b ON b.node_id = n.id
                WHERE n.version_id = ? AND n.path = ?
                ORDER BY b.seq
                LIMIT 1
                """, (rs, rowNum) -> new SectionTextTarget(
                rs.getObject("section_id", UUID.class),
                rs.getObject("block_id", UUID.class),
                textPrefix(rs.getString("plain_text"))), nextVersionId, anchor.path());
        if (candidates.isEmpty() || !anchor.firstTextPrefix().equals(candidates.getFirst().firstTextPrefix())) {
            return Optional.empty();
        }
        var candidate = candidates.getFirst();
        return Optional.of(new ProgressTarget(candidate.sectionId(), candidate.blockId(), true, false));
    }

    private Optional<ProgressTarget> targetBySectionKey(UUID oldSectionId, UUID nextVersionId) {
        if (oldSectionId == null) {
            return Optional.empty();
        }
        return firstTarget("""
                SELECT n.id AS section_id, b.id AS block_id
                FROM content_node old_node
                JOIN content_node n ON n.version_id = ? AND n.node_key = old_node.node_key
                LEFT JOIN content_block b ON b.node_id = n.id
                WHERE old_node.id = ?
                ORDER BY b.seq
                LIMIT 1
                """, true, false, nextVersionId, oldSectionId);
    }

    private Optional<ProgressTarget> firstBlockInVersion(UUID nextVersionId) {
        return firstTarget("""
                SELECT n.id AS section_id, b.id AS block_id
                FROM content_block b
                JOIN content_node n ON n.id = b.node_id
                WHERE b.version_id = ?
                ORDER BY n.path, b.seq
                LIMIT 1
                """, true, true, nextVersionId);
    }

    private Optional<ProgressTarget> firstSectionInVersion(UUID nextVersionId) {
        return firstTarget("""
                SELECT id AS section_id, CAST(NULL AS UUID) AS block_id
                FROM content_node
                WHERE version_id = ?
                ORDER BY path
                LIMIT 1
                """, true, true, nextVersionId);
    }

    private Optional<ProgressTarget> firstTarget(String sql, boolean resetPosition, boolean documentStart, Object... args) {
        var targets = jdbc.query(sql, (rs, rowNum) -> new ProgressTarget(
                rs.getObject("section_id", UUID.class),
                rs.getObject("block_id", UUID.class),
                resetPosition,
                documentStart), args);
        return targets.isEmpty() ? Optional.empty() : Optional.of(targets.getFirst());
    }

    private static String textPrefix(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        var normalized = text.strip();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private DocumentSummary mapDocumentSummary(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentSummary(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getObject("current_version_id", UUID.class),
                rs.getBigDecimal("progress_ratio"));
    }

    private MutableTocNode mapMutableTocNode(ResultSet rs, int rowNum) throws SQLException {
        return new MutableTocNode(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("title"),
                rs.getInt("level"),
                rs.getString("node_type"),
                rs.getString("semantic_role"),
                rs.getString("anchor"),
                (Integer) rs.getObject("source_page_start"),
                rs.getInt("sort_order"));
    }

    private ContentBlock mapContentBlock(ResultSet rs, int rowNum) throws SQLException {
        return new ContentBlock(
                rs.getObject("id", UUID.class),
                rs.getString("block_key"),
                rs.getInt("seq"),
                rs.getString("block_type"),
                readTree(rs.getString("payload")),
                rs.getString("plain_text"),
                (Integer) rs.getObject("source_page"),
                rs.getBigDecimal("confidence"));
    }

    private SearchHit mapSearchHit(ResultSet rs, int rowNum) throws SQLException {
        var title = rs.getString("title");
        var plainText = rs.getString("plain_text");
        return new SearchHit(
                rs.getObject("document_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getObject("node_id", UUID.class),
                rs.getObject("block_id", UUID.class),
                title,
                List.of(title),
                snippet(plainText),
                BigDecimal.ONE);
    }

    private ReadingProgress mapReadingProgress(ResultSet rs, int rowNum) throws SQLException {
        return new ReadingProgress(
                rs.getObject("version_id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getObject("block_id", UUID.class),
                rs.getInt("char_offset"),
                rs.getInt("block_viewport_offset"),
                rs.getBigDecimal("progress_ratio"),
                rs.getObject("client_updated_at", OffsetDateTime.class),
                rs.getString("device_id"),
                rs.getLong("revision"));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored JSON", exception);
        }
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 140 ? text : text.substring(0, 140);
    }

    private static final class MutableTocNode {
        private final UUID id;
        private final UUID parentId;
        private final String title;
        private final int level;
        private final String nodeType;
        private final String semanticRole;
        private final String anchor;
        private final Integer sourcePageStart;
        private final int sortOrder;
        private final List<MutableTocNode> children = new ArrayList<>();

        private MutableTocNode(
                UUID id,
                UUID parentId,
                String title,
                int level,
                String nodeType,
                String semanticRole,
                String anchor,
                Integer sourcePageStart,
                int sortOrder
        ) {
            this.id = id;
            this.parentId = parentId;
            this.title = title;
            this.level = level;
            this.nodeType = nodeType;
            this.semanticRole = semanticRole;
            this.anchor = anchor;
            this.sourcePageStart = sourcePageStart;
            this.sortOrder = sortOrder;
        }

        private TocNode toDto() {
            children.sort(Comparator.comparingInt(node -> node.sortOrder));
            return new TocNode(
                    id,
                    parentId,
                    title,
                    level,
                    nodeType,
                    semanticRole,
                    anchor,
                    sourcePageStart,
                    children.stream().map(MutableTocNode::toDto).toList());
        }
    }

    private record ProgressMigrationRow(
            UUID id,
            UUID sectionId,
            UUID blockId,
            int charOffset,
            int blockViewportOffset,
            BigDecimal progressRatio
    ) {
    }

    private record ProgressTarget(UUID sectionId, UUID blockId, boolean resetPosition, boolean documentStart) {
    }

    private record SectionAnchor(String path, String firstTextPrefix) {
    }

    private record SectionTextTarget(UUID sectionId, UUID blockId, String firstTextPrefix) {
    }
}
