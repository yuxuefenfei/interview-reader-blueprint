package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentDtos.ContentBlock;
import com.example.interviewreader.document.DocumentDtos.DocumentSummary;
import com.example.interviewreader.document.DocumentDtos.NodeContent;
import com.example.interviewreader.document.DocumentDtos.ReadingProgress;
import com.example.interviewreader.document.DocumentDtos.SearchHit;
import com.example.interviewreader.document.DocumentDtos.TocNode;
import com.example.interviewreader.persistence.entity.ContentBlockEntity;
import com.example.interviewreader.persistence.entity.ContentNodeEntity;
import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.example.interviewreader.persistence.entity.DocumentVersionEntity;
import com.example.interviewreader.persistence.entity.ReadingProgressEntity;
import com.example.interviewreader.persistence.mapper.ContentBlockMapper;
import com.example.interviewreader.persistence.mapper.ContentNodeMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.example.interviewreader.persistence.mapper.ReadingProgressMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ReadingProgressEntityTableDef.READING_PROGRESS_ENTITY;

@Service
public class DocumentQueryService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final ReadingProgressMapper readingProgressMapper;
    private final ObjectMapper objectMapper;

    public DocumentQueryService(
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            ContentNodeMapper contentNodeMapper,
            ContentBlockMapper contentBlockMapper,
            ReadingProgressMapper readingProgressMapper,
            ObjectMapper objectMapper
    ) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.contentNodeMapper = contentNodeMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.readingProgressMapper = readingProgressMapper;
        this.objectMapper = objectMapper;
    }

    public List<DocumentSummary> listDocuments(String query) {
        var normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var documents = documentMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .orderBy(DOCUMENT_ENTITY.UPDATED_AT.desc(), DOCUMENT_ENTITY.TITLE.asc()));
        return documents.stream()
                .filter(document -> normalizedQuery.isBlank()
                        || lower(document.title).contains(normalizedQuery)
                        || lower(document.code).contains(normalizedQuery))
                .map(this::mapDocumentSummary)
                .toList();
    }

    public DocumentSummary getDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.ID.eq(id(documentId))));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
        return mapDocumentSummary(document);
    }

    @Transactional
    public void publish(UUID documentId, UUID versionId) {
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId)))
                .and(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId))));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Version not found");
        }
        var previousVersionId = previousPublishedVersionId(documentId, versionId);
        var now = OffsetDateTime.now();
        for (var published : publishedVersions(documentId)) {
            if (!published.id.equals(id(versionId))) {
                published.status = "RETIRED";
                documentVersionMapper.update(published);
            }
        }
        version.status = "PUBLISHED";
        version.publishedAt = now;
        documentVersionMapper.update(version);

        var document = documentMapper.selectOneById(id(documentId));
        if (document != null) {
            document.status = "PUBLISHED";
            document.currentVersionId = id(versionId);
            document.updatedAt = now;
            documentMapper.update(document);
        }
        migrateReadingProgress(documentId, previousVersionId, versionId);
    }

    public List<TocNode> toc(UUID versionId) {
        var rows = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(versionId)))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()));
        if (rows.isEmpty()) {
            ensureVersionExists(versionId);
            return List.of();
        }
        var byId = new LinkedHashMap<String, MutableTocNode>();
        var mutableRows = rows.stream().map(this::mapMutableTocNode).toList();
        mutableRows.forEach(row -> byId.put(row.id, row));
        var roots = new ArrayList<MutableTocNode>();
        for (var row : mutableRows) {
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
        var safeLimit = Math.clamp(limit == null ? 50 : limit, 1, 100);
        var rows = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(id(versionId)))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(id(nodeId)))
                .and(CONTENT_BLOCK_ENTITY.SEQ.gt(afterSeq == null ? 0 : afterSeq))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc())
                .limit(safeLimit + 1));
        Integer nextAfterSeq = null;
        if (rows.size() > safeLimit) {
            nextAfterSeq = rows.get(safeLimit - 1).seq;
            rows = rows.subList(0, safeLimit);
        }
        return new NodeContent(node, rows.stream().map(this::mapContentBlock).toList(), nextAfterSeq);
    }

    public List<SearchHit> search(String q, UUID documentId, Integer limit) {
        if (q == null || q.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "q is required");
        }
        var safeLimit = Math.clamp(limit == null ? 20 : limit, 1, 100);
        var needle = q.trim().toLowerCase(Locale.ROOT);
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc()));
        var hits = new ArrayList<SearchHit>();
        for (var block : blocks) {
            var node = contentNodeMapper.selectOneById(block.nodeId);
            var version = node == null ? null : documentVersionMapper.selectOneById(block.versionId);
            var document = version == null ? null : documentMapper.selectOneById(version.documentId);
            if (node == null || version == null || document == null || !LOCAL_USER_ID.equals(document.ownerId)) {
                continue;
            }
            if (documentId != null && !id(documentId).equals(document.id)) {
                continue;
            }
            if (!lower(block.plainText).contains(needle) && !lower(node.title).contains(needle)) {
                continue;
            }
            hits.add(new SearchHit(
                    uuid(document.id),
                    uuid(version.id),
                    uuid(node.id),
                    uuid(block.id),
                    node.title,
                    List.of(node.title),
                    snippet(block.plainText),
                    BigDecimal.ONE));
            if (hits.size() >= safeLimit) {
                break;
            }
        }
        return hits;
    }

    public ReadingProgress getProgress(UUID documentId) {
        var progress = progress(documentId);
        return progress == null ? null : mapReadingProgress(progress);
    }

    @Transactional
    public ReadingProgress upsertProgress(UUID documentId, ReadingProgress progress) {
        var existing = progress(documentId);
        if (existing == null) {
            var entity = new ReadingProgressEntity();
            entity.id = UUID.randomUUID().toString();
            entity.userId = LOCAL_USER_ID;
            entity.documentId = id(documentId);
            entity.versionId = id(progress.versionId());
            entity.sectionId = id(progress.sectionId());
            entity.blockId = id(progress.blockId());
            entity.charOffset = progress.charOffset();
            entity.blockViewportOffset = progress.blockViewportOffset();
            entity.progressRatio = progress.progressRatio();
            entity.clientUpdatedAt = progress.clientUpdatedAt();
            entity.deviceId = progress.deviceId();
            entity.revision = 1;
            readingProgressMapper.insertSelective(entity);
        } else {
            existing.versionId = id(progress.versionId());
            existing.sectionId = id(progress.sectionId());
            existing.blockId = id(progress.blockId());
            existing.charOffset = progress.charOffset();
            existing.blockViewportOffset = progress.blockViewportOffset();
            existing.progressRatio = progress.progressRatio();
            existing.clientUpdatedAt = progress.clientUpdatedAt();
            existing.deviceId = progress.deviceId();
            existing.revision++;
            existing.updatedAt = OffsetDateTime.now();
            readingProgressMapper.update(existing);
        }
        return getProgress(documentId);
    }

    private TocNode node(UUID versionId, UUID nodeId) {
        var node = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(versionId)))
                .and(CONTENT_NODE_ENTITY.ID.eq(id(nodeId))));
        if (node == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Content node not found");
        }
        return mapMutableTocNode(node).toDto();
    }

    private void ensureVersionExists(UUID versionId) {
        if (documentVersionMapper.selectOneById(id(versionId)) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Version not found");
        }
    }

    private UUID previousPublishedVersionId(UUID documentId, UUID nextVersionId) {
        var versions = documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED")));
        return versions.stream()
                .filter(version -> !version.id.equals(id(nextVersionId)))
                .max(Comparator
                        .comparing((DocumentVersionEntity version) -> version.publishedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(version -> version.versionNo))
                .map(version -> uuid(version.id))
                .orElse(null);
    }

    private List<DocumentVersionEntity> publishedVersions(UUID documentId) {
        return documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED")));
    }

    private void migrateReadingProgress(UUID documentId, UUID previousVersionId, UUID nextVersionId) {
        if (previousVersionId == null || previousVersionId.equals(nextVersionId)) {
            return;
        }
        var rows = readingProgressMapper.selectListByQuery(QueryWrapper.create()
                .select(READING_PROGRESS_ENTITY.ALL_COLUMNS)
                .from(READING_PROGRESS_ENTITY)
                .where(READING_PROGRESS_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(READING_PROGRESS_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .and(READING_PROGRESS_ENTITY.VERSION_ID.eq(id(previousVersionId))));

        for (var row : rows) {
            findProgressTarget(row, nextVersionId).ifPresent(target -> updateMigratedProgress(row, target, nextVersionId));
        }
    }

    private Optional<ProgressTarget> findProgressTarget(ReadingProgressEntity row, UUID nextVersionId) {
        return targetByBlockKey(row.blockId, nextVersionId)
                .or(() -> targetByContentHash(row.blockId, nextVersionId))
                .or(() -> targetBySectionPathAndFirstText(row.sectionId, nextVersionId))
                .or(() -> targetBySectionKey(row.sectionId, nextVersionId))
                .or(() -> firstBlockInVersion(nextVersionId))
                .or(() -> firstSectionInVersion(nextVersionId));
    }

    private void updateMigratedProgress(ReadingProgressEntity row, ProgressTarget target, UUID nextVersionId) {
        row.versionId = id(nextVersionId);
        row.sectionId = target.sectionId();
        row.blockId = target.blockId();
        row.charOffset = target.resetPosition() ? 0 : row.charOffset;
        row.blockViewportOffset = target.resetPosition() ? 0 : row.blockViewportOffset;
        row.progressRatio = target.documentStart() ? BigDecimal.ZERO : row.progressRatio;
        row.revision++;
        row.updatedAt = OffsetDateTime.now();
        readingProgressMapper.update(row);
    }

    private Optional<ProgressTarget> targetByBlockKey(String oldBlockId, UUID nextVersionId) {
        if (oldBlockId == null) {
            return Optional.empty();
        }
        var oldBlock = contentBlockMapper.selectOneById(oldBlockId);
        if (oldBlock == null) {
            return Optional.empty();
        }
        return firstBlock(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(id(nextVersionId)))
                .and(CONTENT_BLOCK_ENTITY.BLOCK_KEY.eq(oldBlock.blockKey))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc()), false, false);
    }

    private Optional<ProgressTarget> targetByContentHash(String oldBlockId, UUID nextVersionId) {
        if (oldBlockId == null) {
            return Optional.empty();
        }
        var oldBlock = contentBlockMapper.selectOneById(oldBlockId);
        if (oldBlock == null || oldBlock.contentHash == null || oldBlock.contentHash.isBlank()) {
            return Optional.empty();
        }
        return firstBlock(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(id(nextVersionId)))
                .and(CONTENT_BLOCK_ENTITY.CONTENT_HASH.eq(oldBlock.contentHash))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc()), false, false);
    }

    private Optional<ProgressTarget> targetBySectionPathAndFirstText(String oldSectionId, UUID nextVersionId) {
        if (oldSectionId == null) {
            return Optional.empty();
        }
        var oldNode = contentNodeMapper.selectOneById(oldSectionId);
        var oldFirstBlock = oldNode == null ? null : firstBlockInNode(oldNode.id).orElse(null);
        var oldPrefix = textPrefix(oldFirstBlock == null ? null : oldFirstBlock.plainText);
        if (oldNode == null || oldPrefix.isBlank()) {
            return Optional.empty();
        }
        var candidateNode = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(nextVersionId)))
                .and(CONTENT_NODE_ENTITY.PATH.eq(oldNode.path)));
        var candidateBlock = candidateNode == null ? null : firstBlockInNode(candidateNode.id).orElse(null);
        if (candidateNode == null || !oldPrefix.equals(textPrefix(candidateBlock == null ? null : candidateBlock.plainText))) {
            return Optional.empty();
        }
        return Optional.of(new ProgressTarget(candidateNode.id, candidateBlock == null ? null : candidateBlock.id, true, false));
    }

    private Optional<ProgressTarget> targetBySectionKey(String oldSectionId, UUID nextVersionId) {
        if (oldSectionId == null) {
            return Optional.empty();
        }
        var oldNode = contentNodeMapper.selectOneById(oldSectionId);
        if (oldNode == null) {
            return Optional.empty();
        }
        var node = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(nextVersionId)))
                .and(CONTENT_NODE_ENTITY.NODE_KEY.eq(oldNode.nodeKey)));
        if (node == null) {
            return Optional.empty();
        }
        var block = firstBlockInNode(node.id).orElse(null);
        return Optional.of(new ProgressTarget(node.id, block == null ? null : block.id, true, false));
    }

    private Optional<ProgressTarget> firstBlockInVersion(UUID nextVersionId) {
        var nodes = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(nextVersionId)))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()));
        for (var node : nodes) {
            var block = firstBlockInNode(node.id).orElse(null);
            if (block != null) {
                return Optional.of(new ProgressTarget(node.id, block.id, true, true));
            }
        }
        return Optional.empty();
    }

    private Optional<ProgressTarget> firstSectionInVersion(UUID nextVersionId) {
        var node = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(nextVersionId)))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc())
                .limit(1));
        return node == null ? Optional.empty() : Optional.of(new ProgressTarget(node.id, null, true, true));
    }

    private Optional<ProgressTarget> firstBlock(QueryWrapper query, boolean resetPosition, boolean documentStart) {
        var block = contentBlockMapper.selectOneByQuery(query.limit(1));
        if (block == null) {
            return Optional.empty();
        }
        return Optional.of(new ProgressTarget(block.nodeId, block.id, resetPosition, documentStart));
    }

    private Optional<ContentBlockEntity> firstBlockInNode(String nodeId) {
        return Optional.ofNullable(contentBlockMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.NODE_ID.eq(nodeId))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc())
                .limit(1)));
    }

    private ReadingProgressEntity progress(UUID documentId) {
        return readingProgressMapper.selectOneByQuery(QueryWrapper.create()
                .select(READING_PROGRESS_ENTITY.ALL_COLUMNS)
                .from(READING_PROGRESS_ENTITY)
                .where(READING_PROGRESS_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(READING_PROGRESS_ENTITY.DOCUMENT_ID.eq(id(documentId))));
    }

    private DocumentSummary mapDocumentSummary(DocumentEntity document) {
        var progress = progress(uuid(document.id));
        var ratio = progress == null ? BigDecimal.ZERO : progress.progressRatio;
        return new DocumentSummary(
                uuid(document.id),
                document.code,
                document.title,
                document.description,
                uuid(document.currentVersionId),
                ratio);
    }

    private MutableTocNode mapMutableTocNode(ContentNodeEntity entity) {
        return new MutableTocNode(
                entity.id,
                entity.parentId,
                entity.title,
                entity.level,
                entity.nodeType,
                entity.semanticRole,
                entity.anchor,
                entity.sourcePageStart,
                entity.sortOrder);
    }

    private ContentBlock mapContentBlock(ContentBlockEntity entity) {
        return new ContentBlock(
                uuid(entity.id),
                entity.blockKey,
                entity.seq,
                entity.blockType,
                readTree(entity.payload),
                entity.plainText,
                entity.sourcePage,
                readNullableTree(entity.sourceBbox),
                entity.confidence);
    }

    private ReadingProgress mapReadingProgress(ReadingProgressEntity entity) {
        return new ReadingProgress(
                uuid(entity.versionId),
                uuid(entity.sectionId),
                uuid(entity.blockId),
                entity.charOffset,
                entity.blockViewportOffset,
                entity.progressRatio,
                entity.clientUpdatedAt,
                entity.deviceId,
                entity.revision);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored JSON", exception);
        }
    }

    private JsonNode readNullableTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return readTree(json);
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 140 ? text : text.substring(0, 140);
    }

    private static String textPrefix(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        var normalized = text.strip();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static final class MutableTocNode {
        private final String id;
        private final String parentId;
        private final String title;
        private final int level;
        private final String nodeType;
        private final String semanticRole;
        private final String anchor;
        private final Integer sourcePageStart;
        private final int sortOrder;
        private final List<MutableTocNode> children = new ArrayList<>();

        private MutableTocNode(
                String id,
                String parentId,
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
                    uuid(id),
                    uuid(parentId),
                    title,
                    level,
                    nodeType,
                    semanticRole,
                    anchor,
                    sourcePageStart,
                    children.stream().map(MutableTocNode::toDto).toList());
        }
    }

    private record ProgressTarget(String sectionId, String blockId, boolean resetPosition, boolean documentStart) {
    }
}
