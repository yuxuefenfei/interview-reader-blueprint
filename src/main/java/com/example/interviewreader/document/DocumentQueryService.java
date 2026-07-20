package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentDtos.*;
import com.example.interviewreader.persistence.entity.*;
import com.example.interviewreader.persistence.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ReadingProgressEntityTableDef.READING_PROGRESS_ENTITY;

@Service
@RequiredArgsConstructor
public class DocumentQueryService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final ReadingProgressMapper readingProgressMapper;
    private final ObjectMapper objectMapper;

    public DocumentPage listDocuments(String query, String cursor, Integer limit) {
        var normalizedQuery = query == null ? "" : query.trim();
        var safeLimit = Math.clamp(limit == null ? 16 : limit, 1, 100);
        var pageCursor = decodeDocumentCursor(cursor);
        var wrapper = QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq("PUBLISHED"));
        if (!normalizedQuery.isBlank()) {
            wrapper.and(DOCUMENT_ENTITY.TITLE.like(normalizedQuery)
                    .or(DOCUMENT_ENTITY.CODE.like(normalizedQuery)));
        }
        if (pageCursor != null) {
            wrapper.and(DOCUMENT_ENTITY.UPDATED_AT.lt(pageCursor.updatedAt())
                    .or(DOCUMENT_ENTITY.UPDATED_AT.eq(pageCursor.updatedAt())
                            .and(DOCUMENT_ENTITY.ID.gt(pageCursor.documentId()))));
        }
        var documents = documentMapper.selectListByQuery(wrapper
                .orderBy(DOCUMENT_ENTITY.UPDATED_AT.desc(), DOCUMENT_ENTITY.ID.asc())
                .limit(safeLimit + 1));
        var hasNext = documents.size() > safeLimit;
        var pageItems = hasNext ? documents.subList(0, safeLimit) : documents;
        var nextCursor = hasNext ? encodeDocumentCursor(pageItems.getLast()) : null;
        var progressByDocument = progressByDocument(pageItems.stream().map(document -> document.getId()).toList());
        return new DocumentPage(pageItems.stream()
                .map(document -> mapDocumentSummary(document, progressByDocument.get(document.getId())))
                .toList(), nextCursor);
    }

    public DocumentSummary getDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq("PUBLISHED"))
                .and(DOCUMENT_ENTITY.ID.eq(id(documentId))));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
        return mapDocumentSummary(document, progress(documentId));
    }

    @Transactional
    public void publish(UUID documentId, UUID versionId) {
        var targetDocument = documentMapper.selectOneById(id(documentId));
        if (targetDocument == null) throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        if ("DELETING".equals(targetDocument.getStatus()) || "DELETE_FAILED".equals(targetDocument.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_DELETION_LOCKED", "Document is locked by permanent deletion");
        }
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId)))
                .and(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId))));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Version not found");
        }
        if (!"DRAFT".equals(version.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a draft version can be published");
        }
        var previousVersionId = previousPublishedVersionId(documentId, versionId);
        var now = OffsetDateTime.now();
        for (var published : publishedVersions(documentId)) {
            if (!published.getId().equals(id(versionId))) {
                published.setStatus("RETIRED");
                documentVersionMapper.update(published);
            }
        }
        version.setStatus("PUBLISHED");
        version.setPublishedAt(now);
        documentVersionMapper.update(version);

        {
            targetDocument.setStatus("PUBLISHED");
            targetDocument.setCurrentVersionId(id(versionId));
            targetDocument.setUpdatedAt(now);
            documentMapper.update(targetDocument);
        }
        migrateReadingProgress(documentId, previousVersionId, versionId);
    }

    public List<TocNode> toc(UUID versionId) {
        ensurePublishedVersion(versionId);
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
        ensurePublishedVersion(versionId);
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
            nextAfterSeq = rows.get(safeLimit - 1).getSeq();
            rows = rows.subList(0, safeLimit);
        }
        return new NodeContent(node, rows.stream().map(this::mapContentBlock).toList(), nextAfterSeq);
    }

    public List<SearchHit> search(String q, UUID documentId, Integer limit) {
        if (q == null || q.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "q is required");
        }
        var safeLimit = Math.clamp(limit == null ? 20 : limit, 1, 100);
        var needle = q.trim();
        var searchLimit = documentId == null ? safeLimit * 4 : safeLimit * 8;
        var blockQuery = QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .innerJoin(CONTENT_NODE_ENTITY).on(CONTENT_BLOCK_ENTITY.NODE_ID.eq(CONTENT_NODE_ENTITY.ID))
                .innerJoin(DOCUMENT_VERSION_ENTITY).on(CONTENT_NODE_ENTITY.VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID))
                .innerJoin(DOCUMENT_ENTITY).on(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(DOCUMENT_ENTITY.ID))
                .where(CONTENT_BLOCK_ENTITY.PLAIN_TEXT.like(needle))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED"))
                .and(DOCUMENT_ENTITY.STATUS.eq("PUBLISHED"))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .orderBy(CONTENT_BLOCK_ENTITY.VERSION_ID.asc(), CONTENT_BLOCK_ENTITY.NODE_ID.asc(), CONTENT_BLOCK_ENTITY.SEQ.asc())
                .limit(searchLimit);
        var nodeQuery = QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .innerJoin(DOCUMENT_VERSION_ENTITY).on(CONTENT_NODE_ENTITY.VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID))
                .innerJoin(DOCUMENT_ENTITY).on(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(DOCUMENT_ENTITY.ID))
                .where(CONTENT_NODE_ENTITY.TITLE.like(needle))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED"))
                .and(DOCUMENT_ENTITY.STATUS.eq("PUBLISHED"))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc())
                .limit(searchLimit);
        if (documentId != null) {
            blockQuery.and(DOCUMENT_ENTITY.ID.eq(id(documentId)));
            nodeQuery.and(DOCUMENT_ENTITY.ID.eq(id(documentId)));
        }
        var matchedBlocks = contentBlockMapper.selectListByQuery(blockQuery);
        var titleMatchedNodes = contentNodeMapper.selectListByQuery(nodeQuery);

        var nodeIds = new ArrayList<String>();
        matchedBlocks.stream().map(block -> block.getNodeId()).distinct().forEach(nodeIds::add);
        titleMatchedNodes.stream().map(node -> node.getId()).filter(id -> !nodeIds.contains(id)).forEach(nodeIds::add);
        if (nodeIds.isEmpty()) {
            return List.of();
        }

        var nodesById = nodesById(nodeIds);
        var bodyMatchedNodeIds = matchedBlocks.stream()
                .map(block -> block.getNodeId())
                .collect(java.util.stream.Collectors.toSet());
        var missingTitleBlockNodeIds = titleMatchedNodes.stream()
                .map(node -> node.getId())
                .filter(nodeId -> !bodyMatchedNodeIds.contains(nodeId))
                .toList();
        if (!missingTitleBlockNodeIds.isEmpty()) {
            matchedBlocks = new ArrayList<>(matchedBlocks);
            matchedBlocks.addAll(firstBlocksInNodes(missingTitleBlockNodeIds));
        }

        var versionIds = nodesById.values().stream().map(node -> node.getVersionId()).distinct().toList();
        var versionsById = versionsById(versionIds);
        var documentIds = versionsById.values().stream().map(version -> version.getDocumentId()).distinct().toList();
        var documentsById = documentsById(documentIds);

        var hits = new ArrayList<SearchHit>();
        for (var block : matchedBlocks) {
            var node = nodesById.get(block.getNodeId());
            var version = node == null ? null : versionsById.get(node.getVersionId());
            var document = version == null ? null : documentsById.get(version.getDocumentId());
            if (node == null || version == null || document == null || !LOCAL_USER_ID.equals(document.getOwnerId())) {
                continue;
            }
            if (documentId != null && !id(documentId).equals(document.getId())) {
                continue;
            }
            if (!containsIgnoreCase(block.getPlainText(), needle) && !containsIgnoreCase(node.getTitle(), needle)) {
                continue;
            }
            hits.add(new SearchHit(
                    uuid(document.getId()),
                    uuid(version.getId()),
                    uuid(node.getId()),
                    uuid(block.getId()),
                    node.getTitle(),
                    List.of(node.getTitle()),
                    snippet(block.getPlainText()),
                    BigDecimal.ONE));
            if (hits.size() >= safeLimit) {
                break;
            }
        }
        return hits;
    }

    private Map<String, ContentNodeEntity> nodesById(List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        var rows = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.ID.in(nodeIds)));
        var result = new LinkedHashMap<String, ContentNodeEntity>();
        rows.forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private List<ContentBlockEntity> firstBlocksInNodes(List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        var rows = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.NODE_ID.in(nodeIds))
                .orderBy(CONTENT_BLOCK_ENTITY.NODE_ID.asc(), CONTENT_BLOCK_ENTITY.SEQ.asc()));
        var firstBlocks = new LinkedHashMap<String, ContentBlockEntity>();
        for (var row : rows) {
            firstBlocks.putIfAbsent(row.getNodeId(), row);
        }
        return List.copyOf(firstBlocks.values());
    }

    private Map<String, DocumentVersionEntity> versionsById(List<String> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        var rows = documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.in(versionIds))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED")));
        var result = new LinkedHashMap<String, DocumentVersionEntity>();
        rows.forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private Map<String, DocumentEntity> documentsById(List<String> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        var rows = documentMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.in(documentIds))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq("PUBLISHED")));
        var result = new LinkedHashMap<String, DocumentEntity>();
        rows.forEach(row -> result.put(row.getId(), row));
        return result;
    }

    public ReadingProgress getProgress(UUID documentId) {
        var progress = progress(documentId);
        return progress == null ? null : mapReadingProgress(progress);
    }

    @Transactional
    public ReadingProgress upsertProgress(UUID documentId, ReadingProgress progress) {
        var document = documentMapper.selectOwnedForUpdate(id(documentId), LOCAL_USER_ID);
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
        validateReadingPosition(documentId, progress);
        var existing = progress(documentId);
        var clientUpdatedAt = progress.clientUpdatedAt() == null ? OffsetDateTime.now() : progress.clientUpdatedAt();
        if (existing == null) {
            var entity = new ReadingProgressEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setUserId(LOCAL_USER_ID);
            entity.setDocumentId(id(documentId));
            entity.setVersionId(id(progress.versionId()));
            entity.setSectionId(id(progress.sectionId()));
            entity.setBlockId(id(progress.blockId()));
            entity.setCharOffset(progress.charOffset());
            entity.setBlockViewportOffset(progress.blockViewportOffset());
            entity.setProgressRatio(progress.progressRatio());
            entity.setClientUpdatedAt(clientUpdatedAt);
            entity.setDeviceId(progress.deviceId());
            entity.setRevision(1);
            readingProgressMapper.insertSelective(entity);
        } else {
            // 离线队列可能乱序重放；旧的客户端时间不得覆盖服务器已经接受的新阅读位置。
            if (existing.getClientUpdatedAt() != null && !clientUpdatedAt.isAfter(existing.getClientUpdatedAt())) {
                return mapReadingProgress(existing);
            }
            existing.setVersionId(id(progress.versionId()));
            existing.setSectionId(id(progress.sectionId()));
            existing.setBlockId(id(progress.blockId()));
            existing.setCharOffset(progress.charOffset());
            existing.setBlockViewportOffset(progress.blockViewportOffset());
            existing.setProgressRatio(progress.progressRatio());
            existing.setClientUpdatedAt(clientUpdatedAt);
            existing.setDeviceId(progress.deviceId());
            existing.setRevision(existing.getRevision() + 1);
            existing.setUpdatedAt(OffsetDateTime.now());
            readingProgressMapper.update(existing);
        }
        return getProgress(documentId);
    }

    private void validateReadingPosition(UUID documentId, ReadingProgress progress) {
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ID)
                .from(DOCUMENT_VERSION_ENTITY)
                .innerJoin(DOCUMENT_ENTITY).on(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(DOCUMENT_ENTITY.ID))
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(id(progress.versionId())))
                .and(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED"))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq("PUBLISHED")));
        if (version == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "READING_VERSION_INVALID", "阅读版本不属于目标文档或尚未发布。");
        }
        if (progress.sectionId() != null) {
            var section = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                    .select(CONTENT_NODE_ENTITY.ID)
                    .from(CONTENT_NODE_ENTITY)
                    .where(CONTENT_NODE_ENTITY.ID.eq(id(progress.sectionId())))
                    .and(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(progress.versionId()))));
            if (section == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "READING_SECTION_INVALID", "阅读章节不属于目标版本。");
            }
        }
        if (progress.blockId() != null) {
            var blockQuery = QueryWrapper.create()
                    .select(CONTENT_BLOCK_ENTITY.ID)
                    .from(CONTENT_BLOCK_ENTITY)
                    .where(CONTENT_BLOCK_ENTITY.ID.eq(id(progress.blockId())))
                    .and(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(id(progress.versionId())));
            if (progress.sectionId() != null) {
                blockQuery.and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(id(progress.sectionId())));
            }
            if (contentBlockMapper.selectOneByQuery(blockQuery) == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "READING_BLOCK_INVALID", "阅读内容块不属于目标版本或章节。");
            }
        }
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

    private void ensurePublishedVersion(UUID versionId) {
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ID)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId)))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED")));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Published version not found");
        }
    }

    private UUID previousPublishedVersionId(UUID documentId, UUID nextVersionId) {
        var versions = documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED")));
        return versions.stream()
                .filter(version -> !version.getId().equals(id(nextVersionId)))
                .max(Comparator
                        .comparing((DocumentVersionEntity version) -> version.getPublishedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(version -> version.getVersionNo()))
                .map(version -> uuid(version.getId()))
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
                .and(READING_PROGRESS_ENTITY.DOCUMENT_ID.eq(id(documentId))));
        for (var row : rows) {
            var update = UpdateWrapper.of(ReadingProgressEntity.class)
                    .set(READING_PROGRESS_ENTITY.VERSION_ID, id(nextVersionId))
                    .set(READING_PROGRESS_ENTITY.SECTION_ID, null)
                    .set(READING_PROGRESS_ENTITY.BLOCK_ID, null)
                    .set(READING_PROGRESS_ENTITY.CHAR_OFFSET, 0)
                    .set(READING_PROGRESS_ENTITY.BLOCK_VIEWPORT_OFFSET, 0)
                    .set(READING_PROGRESS_ENTITY.PROGRESS_RATIO, BigDecimal.ZERO)
                    .set(READING_PROGRESS_ENTITY.REVISION, row.getRevision() + 1)
                    .set(READING_PROGRESS_ENTITY.UPDATED_AT, OffsetDateTime.now());
            readingProgressMapper.updateByQuery(
                    update.toEntity(),
                    false,
                    QueryWrapper.create().where(READING_PROGRESS_ENTITY.ID.eq(row.getId()))
            );
        }
    }
    private ReadingProgressEntity progress(UUID documentId) {
        return readingProgressMapper.selectOneByQuery(QueryWrapper.create()
                .select(READING_PROGRESS_ENTITY.ALL_COLUMNS)
                .from(READING_PROGRESS_ENTITY)
                .where(READING_PROGRESS_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(READING_PROGRESS_ENTITY.DOCUMENT_ID.eq(id(documentId))));
    }

    private Map<String, ReadingProgressEntity> progressByDocument(List<String> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, ReadingProgressEntity>();
        readingProgressMapper.selectListByQuery(QueryWrapper.create()
                        .select(READING_PROGRESS_ENTITY.ALL_COLUMNS)
                        .from(READING_PROGRESS_ENTITY)
                        .where(READING_PROGRESS_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                        .and(READING_PROGRESS_ENTITY.DOCUMENT_ID.in(documentIds)))
                .forEach(progress -> result.put(progress.getDocumentId(), progress));
        return result;
    }

    private DocumentSummary mapDocumentSummary(DocumentEntity document, ReadingProgressEntity progress) {
        var ratio = progress == null ? BigDecimal.ZERO : progress.getProgressRatio();
        return new DocumentSummary(
                uuid(document.getId()),
                document.getCode(),
                document.getTitle(),
                document.getDescription(),
                uuid(document.getCurrentVersionId()),
                ratio);
    }

    private MutableTocNode mapMutableTocNode(ContentNodeEntity entity) {
        return new MutableTocNode(
                entity.getId(),
                entity.getParentId(),
                entity.getTitle(),
                entity.getLevel(),
                entity.getNodeType(),
                entity.getSemanticRole(),
                entity.getAnchor(),
                entity.getSourcePageStart(),
                entity.getSortOrder());
    }

    private ContentBlock mapContentBlock(ContentBlockEntity entity) {
        return new ContentBlock(
                uuid(entity.getId()),
                entity.getBlockKey(),
                entity.getSeq(),
                entity.getBlockType(),
                readTree(entity.getPayload()),
                entity.getPlainText(),
                entity.getSourcePage(),
                readNullableTree(entity.getSourceBbox()),
                entity.getConfidence());
    }

    private ReadingProgress mapReadingProgress(ReadingProgressEntity entity) {
        return new ReadingProgress(
                uuid(entity.getVersionId()),
                uuid(entity.getSectionId()),
                uuid(entity.getBlockId()),
                entity.getCharOffset(),
                entity.getBlockViewportOffset(),
                entity.getProgressRatio(),
                entity.getClientUpdatedAt(),
                entity.getDeviceId(),
                entity.getRevision());
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

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String encodeDocumentCursor(DocumentEntity document) {
        var raw = document.getUpdatedAt().toInstant() + "|" + document.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private DocumentCursor decodeDocumentCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var separator = raw.indexOf('|');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("cursor shape");
            }
            return new DocumentCursor(OffsetDateTime.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid document cursor");
        }
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return lower(value).contains(lower(needle));
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

    private record DocumentCursor(OffsetDateTime updatedAt, String documentId) {
    }
}
