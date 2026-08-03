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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
@Slf4j
public class DocumentQueryService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();
    private static final int MAX_SEARCH_NODE_CANDIDATES = 500;
    private static final int MAX_SEARCH_BLOCK_CANDIDATES = 2_000;

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
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED));
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
        var progressByDocument = progressByDocument(pageItems.stream().map(DocumentEntity::getId).toList());
        var readableNodesByVersion = readableNodeIdsByVersion(pageItems.stream()
                .map(DocumentEntity::getCurrentVersionId)
                .filter(Objects::nonNull)
                .toList());
        return new DocumentPage(pageItems.stream()
                .map(document -> mapDocumentSummary(
                        document,
                        progressByDocument.get(document.getId()),
                        readableNodesByVersion.getOrDefault(document.getCurrentVersionId(), List.of())))
                .toList(), nextCursor);
    }

    public DocumentSummary getDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.ID.eq(id(documentId))));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
        var readableNodesByVersion = readableNodeIdsByVersion(
                document.getCurrentVersionId() == null ? List.of() : List.of(document.getCurrentVersionId()));
        return mapDocumentSummary(
                document,
                progress(documentId),
                readableNodesByVersion.getOrDefault(document.getCurrentVersionId(), List.of()));
    }

    public DocumentSummary latestReadDocument() {
        var progress = readingProgressMapper.selectOneByQuery(QueryWrapper.create()
                .select(READING_PROGRESS_ENTITY.ALL_COLUMNS)
                .from(READING_PROGRESS_ENTITY)
                .innerJoin(DOCUMENT_ENTITY).on(READING_PROGRESS_ENTITY.DOCUMENT_ID.eq(DOCUMENT_ENTITY.ID))
                .where(READING_PROGRESS_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED))
                .orderBy(READING_PROGRESS_ENTITY.CLIENT_UPDATED_AT.desc(), READING_PROGRESS_ENTITY.UPDATED_AT.desc(), READING_PROGRESS_ENTITY.DOCUMENT_ID.asc())
                .limit(1));
        return progress == null ? null : getDocument(uuid(progress.getDocumentId()));
    }

    public List<TocNode> toc(UUID versionId) {
        ensureReadableVersion(versionId);
        var rows = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(
                        CONTENT_NODE_ENTITY.ID,
                        CONTENT_NODE_ENTITY.PARENT_ID,
                        CONTENT_NODE_ENTITY.TITLE,
                        CONTENT_NODE_ENTITY.LEVEL,
                        CONTENT_NODE_ENTITY.NODE_TYPE,
                        CONTENT_NODE_ENTITY.SEMANTIC_ROLE,
                        CONTENT_NODE_ENTITY.ANCHOR,
                        CONTENT_NODE_ENTITY.SOURCE_PAGE_START,
                        CONTENT_NODE_ENTITY.SORT_ORDER)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(versionId)))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()));
        if (rows.isEmpty()) return List.of();
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
        ensureReadableVersion(versionId);
        var node = node(versionId, nodeId);
        var safeLimit = Math.clamp(limit == null ? 50 : limit, 1, 100);
        var rows = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(
                        CONTENT_BLOCK_ENTITY.ID,
                        CONTENT_BLOCK_ENTITY.BLOCK_KEY,
                        CONTENT_BLOCK_ENTITY.SEQ,
                        CONTENT_BLOCK_ENTITY.BLOCK_TYPE,
                        CONTENT_BLOCK_ENTITY.PAYLOAD,
                        CONTENT_BLOCK_ENTITY.PLAIN_TEXT,
                        CONTENT_BLOCK_ENTITY.SOURCE_PAGE,
                        CONTENT_BLOCK_ENTITY.SOURCE_BBOX,
                        CONTENT_BLOCK_ENTITY.CONFIDENCE)
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
        var nodeQuery = QueryWrapper.create()
                .select(
                        CONTENT_NODE_ENTITY.ID,
                        CONTENT_NODE_ENTITY.VERSION_ID,
                        CONTENT_NODE_ENTITY.PARENT_ID,
                        CONTENT_NODE_ENTITY.TITLE,
                        CONTENT_NODE_ENTITY.PATH,
                        CONTENT_NODE_ENTITY.SEARCH_TEXT)
                .from(CONTENT_NODE_ENTITY)
                .innerJoin(DOCUMENT_VERSION_ENTITY).on(CONTENT_NODE_ENTITY.VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID))
                .innerJoin(DOCUMENT_ENTITY).on(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(DOCUMENT_ENTITY.ID))
                .where(CONTENT_NODE_ENTITY.SEARCH_TEXT.like(needle))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .orderBy(CONTENT_NODE_ENTITY.VERSION_ID.asc(), CONTENT_NODE_ENTITY.PATH.asc())
                .limit(MAX_SEARCH_NODE_CANDIDATES + 1);
        if (documentId != null) {
            nodeQuery.and(DOCUMENT_ENTITY.ID.eq(id(documentId)));
        }
        var nodeCandidates = contentNodeMapper.selectListByQuery(nodeQuery);
        if (nodeCandidates.size() > MAX_SEARCH_NODE_CANDIDATES) {
            log.warn("Reader search node candidate limit reached: queryLength={}, documentScoped={}, limit={}",
                    needle.length(), documentId != null, MAX_SEARCH_NODE_CANDIDATES);
            nodeCandidates = nodeCandidates.subList(0, MAX_SEARCH_NODE_CANDIDATES);
        }
        if (nodeCandidates.isEmpty()) {
            return List.of();
        }

        var nodeIds = nodeCandidates.stream().map(ContentNodeEntity::getId).toList();
        var blockQuery = QueryWrapper.create()
                .select(
                        CONTENT_BLOCK_ENTITY.ID,
                        CONTENT_BLOCK_ENTITY.NODE_ID,
                        CONTENT_BLOCK_ENTITY.SEQ,
                        CONTENT_BLOCK_ENTITY.PLAIN_TEXT)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.NODE_ID.in(nodeIds))
                .and(CONTENT_BLOCK_ENTITY.PLAIN_TEXT.like(needle))
                .orderBy(CONTENT_BLOCK_ENTITY.NODE_ID.asc(), CONTENT_BLOCK_ENTITY.SEQ.asc())
                .limit(MAX_SEARCH_BLOCK_CANDIDATES + 1);
        var matchedBlocks = contentBlockMapper.selectListByQuery(blockQuery);
        if (matchedBlocks.size() > MAX_SEARCH_BLOCK_CANDIDATES) {
            log.warn("Reader search block candidate limit reached: queryLength={}, documentScoped={}, limit={}",
                    needle.length(), documentId != null, MAX_SEARCH_BLOCK_CANDIDATES);
            matchedBlocks = matchedBlocks.subList(0, MAX_SEARCH_BLOCK_CANDIDATES);
        }

        var nodesById = nodeCandidates.stream().collect(
                java.util.stream.Collectors.toMap(
                        ContentNodeEntity::getId,
                        node -> node,
                        (left, right) -> left,
                        LinkedHashMap::new));
        var bodyMatchedNodeIds = matchedBlocks.stream()
                .map(ContentBlockEntity::getNodeId)
                .collect(java.util.stream.Collectors.toSet());
        var titleMatchedNodeIds = nodeCandidates.stream()
                .filter(node -> containsIgnoreCase(node.getTitle(), needle))
                .map(ContentNodeEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        var missingTitleBlockNodeIds = titleMatchedNodeIds.stream()
                .filter(nodeId -> !bodyMatchedNodeIds.contains(nodeId))
                .toList();
        if (!missingTitleBlockNodeIds.isEmpty()) {
            matchedBlocks = new ArrayList<>(matchedBlocks);
            matchedBlocks.addAll(firstBlocksInNodes(missingTitleBlockNodeIds));
        }

        var versionIds = nodesById.values().stream().map(ContentNodeEntity::getVersionId).distinct().toList();
        var versionsById = versionsById(versionIds);
        var documentIds = versionsById.values().stream().map(DocumentVersionEntity::getDocumentId).distinct().toList();
        var documentsById = documentsById(documentIds);
        var nodesWithAncestors = nodesIncludingAncestors(nodesById.values());

        var rankedHits = new ArrayList<RankedSearchHit>();
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
            var score = searchScore(node, block, needle, titleMatchedNodeIds.contains(node.getId()));
            var hit = new SearchHit(
                    uuid(document.getId()),
                    document.getTitle(),
                    uuid(version.getId()),
                    uuid(node.getId()),
                    uuid(block.getId()),
                    node.getTitle(),
                    sectionPath(node, nodesWithAncestors),
                    centeredSnippet(block.getPlainText(), needle),
                    score);
            rankedHits.add(new RankedSearchHit(hit, node.getPath(), block.getSeq()));
        }
        rankedHits.sort(Comparator
                .comparing((RankedSearchHit ranked) -> ranked.hit().score(), Comparator.reverseOrder())
                .thenComparing(ranked -> ranked.hit().documentTitle(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RankedSearchHit::nodePath, Comparator.nullsLast(String::compareTo))
                .thenComparingInt(RankedSearchHit::blockSeq)
                .thenComparing(ranked -> ranked.hit().blockId()));
        return rankedHits.stream().limit(safeLimit).map(RankedSearchHit::hit).toList();
    }

    private List<ContentBlockEntity> firstBlocksInNodes(List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        var rows = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(
                        CONTENT_BLOCK_ENTITY.ID,
                        CONTENT_BLOCK_ENTITY.NODE_ID,
                        CONTENT_BLOCK_ENTITY.SEQ,
                        CONTENT_BLOCK_ENTITY.PLAIN_TEXT)
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
                .select(DOCUMENT_VERSION_ENTITY.ID, DOCUMENT_VERSION_ENTITY.DOCUMENT_ID)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.in(versionIds))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED)));
        var result = new LinkedHashMap<String, DocumentVersionEntity>();
        rows.forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private Map<String, DocumentEntity> documentsById(List<String> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        var rows = documentMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ID, DOCUMENT_ENTITY.TITLE, DOCUMENT_ENTITY.OWNER_ID)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.in(documentIds))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED)));
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
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.eq(id(documentId)))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .forUpdate());
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
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED)));
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
                .select(
                        CONTENT_NODE_ENTITY.ID,
                        CONTENT_NODE_ENTITY.PARENT_ID,
                        CONTENT_NODE_ENTITY.TITLE,
                        CONTENT_NODE_ENTITY.LEVEL,
                        CONTENT_NODE_ENTITY.NODE_TYPE,
                        CONTENT_NODE_ENTITY.SEMANTIC_ROLE,
                        CONTENT_NODE_ENTITY.ANCHOR,
                        CONTENT_NODE_ENTITY.SOURCE_PAGE_START,
                        CONTENT_NODE_ENTITY.SORT_ORDER)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(versionId)))
                .and(CONTENT_NODE_ENTITY.ID.eq(id(nodeId))));
        if (node == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Content node not found");
        }
        return mapMutableTocNode(node).toDto();
    }

    public void ensureReadableVersion(UUID versionId) {
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ID)
                .from(DOCUMENT_VERSION_ENTITY)
                .innerJoin(DOCUMENT_ENTITY).on(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(DOCUMENT_ENTITY.ID))
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId)))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.CURRENT_VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID)));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Published version not found");
        }
    }

    public void ensureReadableNode(UUID versionId, UUID nodeId) {
        ensureReadableVersion(versionId);
        node(versionId, nodeId);
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

    private Map<String, ContentNodeEntity> nodesIncludingAncestors(Collection<ContentNodeEntity> leafNodes) {
        var result = new LinkedHashMap<String, ContentNodeEntity>();
        leafNodes.forEach(node -> result.put(node.getId(), node));
        var versionIds = leafNodes.stream()
                .map(ContentNodeEntity::getVersionId)
                .collect(java.util.stream.Collectors.toSet());
        var pendingParentIds = leafNodes.stream()
                .map(ContentNodeEntity::getParentId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        while (!pendingParentIds.isEmpty()) {
            pendingParentIds.removeAll(result.keySet());
            if (pendingParentIds.isEmpty()) {
                break;
            }
            var parents = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                    .select(
                            CONTENT_NODE_ENTITY.ID,
                            CONTENT_NODE_ENTITY.VERSION_ID,
                            CONTENT_NODE_ENTITY.PARENT_ID,
                            CONTENT_NODE_ENTITY.TITLE)
                    .from(CONTENT_NODE_ENTITY)
                    .where(CONTENT_NODE_ENTITY.ID.in(pendingParentIds))
                    .and(CONTENT_NODE_ENTITY.VERSION_ID.in(versionIds)));
            pendingParentIds = new LinkedHashSet<>();
            for (var parent : parents) {
                result.put(parent.getId(), parent);
                if (parent.getParentId() != null && !result.containsKey(parent.getParentId())) {
                    pendingParentIds.add(parent.getParentId());
                }
            }
        }
        return result;
    }

    private static List<String> sectionPath(
            ContentNodeEntity node,
            Map<String, ContentNodeEntity> nodesById
    ) {
        var titles = new LinkedList<String>();
        var visited = new HashSet<String>();
        var current = node;
        while (current != null && visited.add(current.getId())) {
            titles.addFirst(current.getTitle());
            current = current.getParentId() == null ? null : nodesById.get(current.getParentId());
        }
        return List.copyOf(titles);
    }

    private static BigDecimal searchScore(
            ContentNodeEntity node,
            ContentBlockEntity block,
            String needle,
            boolean titleMatched
    ) {
        if (titleMatched && node.getTitle().equalsIgnoreCase(needle)) {
            return new BigDecimal("4.0");
        }
        if (titleMatched) {
            return new BigDecimal("3.0");
        }
        var text = Objects.requireNonNullElse(block.getPlainText(), "");
        if (text.equalsIgnoreCase(needle)) {
            return new BigDecimal("2.5");
        }
        if (text.regionMatches(true, 0, needle, 0, needle.length())) {
            return new BigDecimal("2.0");
        }
        return BigDecimal.ONE;
    }

    private Map<String, List<String>> readableNodeIdsByVersion(List<String> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        var uniqueVersionIds = versionIds.stream().distinct().toList();
        var rows = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(
                        CONTENT_NODE_ENTITY.ID,
                        CONTENT_NODE_ENTITY.VERSION_ID,
                        CONTENT_NODE_ENTITY.PARENT_ID,
                        CONTENT_NODE_ENTITY.NODE_TYPE,
                        CONTENT_NODE_ENTITY.SEMANTIC_ROLE,
                        CONTENT_NODE_ENTITY.PATH)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.in(uniqueVersionIds))
                .orderBy(CONTENT_NODE_ENTITY.VERSION_ID.asc(), CONTENT_NODE_ENTITY.PATH.asc()));
        var parentIds = rows.stream()
                .map(ContentNodeEntity::getParentId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        var result = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            if (row.getNodeType() != NodeType.QUESTION
                    && row.getSemanticRole() != SemanticRole.QUESTION
                    && parentIds.contains(row.getId())) {
                continue;
            }
            result.computeIfAbsent(row.getVersionId(), ignored -> new ArrayList<>()).add(row.getId());
        }
        return result;
    }

    private DocumentSummary mapDocumentSummary(
            DocumentEntity document,
            ReadingProgressEntity progress,
            List<String> readableNodeIds) {
        var ratio = documentProgressRatio(document, progress, readableNodeIds);
        return new DocumentSummary(
                uuid(document.getId()),
                document.getCode(),
                document.getTitle(),
                document.getDescription(),
                uuid(document.getCurrentVersionId()),
                ratio);
    }

    private BigDecimal documentProgressRatio(
            DocumentEntity document,
            ReadingProgressEntity progress,
            List<String> readableNodeIds) {
        if (progress == null
                || progress.getSectionId() == null
                || document.getCurrentVersionId() == null
                || !document.getCurrentVersionId().equals(progress.getVersionId())
                || readableNodeIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        var sectionIndex = readableNodeIds.indexOf(progress.getSectionId());
        if (sectionIndex < 0) {
            return BigDecimal.ZERO;
        }
        var sectionRatio = Optional.ofNullable(progress.getProgressRatio())
                .orElse(BigDecimal.ZERO)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE);
        return BigDecimal.valueOf(sectionIndex)
                .add(sectionRatio)
                .divide(BigDecimal.valueOf(readableNodeIds.size()), 7, RoundingMode.HALF_UP);
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

    static String centeredSnippet(String text, String needle) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        var maxLength = 140;
        if (text.length() <= maxLength) {
            return text;
        }
        var matchIndex = indexOfIgnoreCase(text, needle);
        if (matchIndex < 0) {
            return text.substring(0, maxLength).stripTrailing() + "…";
        }
        var desiredStart = matchIndex - Math.max(24, (maxLength - needle.length()) / 2);
        var start = Math.max(0, Math.min(desiredStart, text.length() - maxLength));
        var end = Math.min(text.length(), start + maxLength);
        var snippet = text.substring(start, end).strip();
        return (start > 0 ? "…" : "") + snippet + (end < text.length() ? "…" : "");
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        var lastStart = value.length() - needle.length();
        for (var index = 0; index <= lastStart; index++) {
            if (value.regionMatches(true, index, needle, 0, needle.length())) {
                return index;
            }
        }
        return -1;
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

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean containsIgnoreCase(String value, String needle) {
        return lower(value).contains(lower(needle));
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private record RankedSearchHit(SearchHit hit, String nodePath, int blockSeq) {
    }

    private static final class MutableTocNode {
        private final String id;
        private final String parentId;
        private final String title;
        private final int level;
        private final NodeType nodeType;
        private final SemanticRole semanticRole;
        private final String anchor;
        private final Integer sourcePageStart;
        private final int sortOrder;
        private final List<MutableTocNode> children = new ArrayList<>();

        private MutableTocNode(
                String id,
                String parentId,
                String title,
                int level,
                NodeType nodeType,
                SemanticRole semanticRole,
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
