package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentQueryService;
import com.example.interviewreader.importpkg.DocumentBlockContent;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.importpkg.DocumentPackageValidator;
import com.example.interviewreader.persistence.entity.*;
import com.example.interviewreader.persistence.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

import static com.example.interviewreader.persistence.entity.table.AssetEntityTableDef.ASSET_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ImportJobEntityTableDef.IMPORT_JOB_ENTITY;

@Service
public class VersionRevisionService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();
    private static final Set<String> EDITABLE_BLOCK_TYPES = Set.of(
            "paragraph", "heading_note", "unordered_list", "ordered_list", "code", "table", "quote",
            "callout", "formula", "image", "divider", "table_snapshot");
    private static final Set<String> EDITABLE_NODE_TYPES = Set.of(
            "PART", "CHAPTER", "SECTION", "SUBSECTION", "QUESTION", "APPENDIX", "OTHER");

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final AssetMapper assetMapper;
    private final ImportJobMapper importJobMapper;
    private final DocumentPackageValidator validator;
    private final DocumentQueryService documentQueryService;
    private final ObjectMapper objectMapper;

    public VersionRevisionService(
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            ContentNodeMapper contentNodeMapper,
            ContentBlockMapper contentBlockMapper,
            AssetMapper assetMapper,
            ImportJobMapper importJobMapper,
            DocumentPackageValidator validator,
            DocumentQueryService documentQueryService,
            ObjectMapper objectMapper
    ) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.contentNodeMapper = contentNodeMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.assetMapper = assetMapper;
        this.importJobMapper = importJobMapper;
        this.validator = validator;
        this.documentQueryService = documentQueryService;
        this.objectMapper = objectMapper;
    }

    public ManagementDtos.AdminDocumentPage documents(Integer page, Integer size) {
        return documents(null, page, size);
    }

    public ManagementDtos.AdminDocumentPage documents(String query, Integer page, Integer size) {
        var safePage = Math.max(page == null ? 1 : page, 1);
        var safeSize = Math.clamp(size == null ? 20 : size, 1, 100);
        var offset = (safePage - 1) * safeSize;
        var normalizedQuery = query == null ? "" : query.trim();
        var wrapper = QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID));
        if (!normalizedQuery.isBlank()) {
            wrapper.and(DOCUMENT_ENTITY.TITLE.like(normalizedQuery).or(DOCUMENT_ENTITY.CODE.like(normalizedQuery)));
        }
        var documents = documentMapper.selectListByQuery(wrapper
                .orderBy(DOCUMENT_ENTITY.UPDATED_AT.desc(), DOCUMENT_ENTITY.ID.asc())
                .limit(offset, safeSize + 1));
        var hasNext = documents.size() > safeSize;
        var pageItems = hasNext ? documents.subList(0, safeSize) : documents;
        var ids = pageItems.stream().map(row -> row.id).toList();
        var versions = ids.isEmpty() ? List.<DocumentVersionEntity>of() : documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.in(ids)));
        var byDocument = new HashMap<String, List<DocumentVersionEntity>>();
        versions.forEach(version -> byDocument.computeIfAbsent(version.documentId, ignored -> new ArrayList<>()).add(version));
        return new ManagementDtos.AdminDocumentPage(pageItems.stream()
                .map(document -> documentSummary(document, byDocument.getOrDefault(document.id, List.of())))
                .toList(), safePage, safeSize, hasNext);
    }

    public ManagementDtos.AdminDocumentSummary document(UUID documentId) {
        var document = requireDocument(documentId);
        var versions = documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(document.id)));
        return documentSummary(document, versions);
    }

    public List<ManagementDtos.VersionSummary> versions(UUID documentId) {
        requireDocument(documentId);
        return documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .orderBy(DOCUMENT_VERSION_ENTITY.VERSION_NO.desc()))
                .stream().map(this::summary).toList();
    }

    @Transactional
    public ManagementDtos.VersionSummary createRevision(UUID documentId, UUID sourceVersionId) {
        requireDocument(documentId);
        var source = version(documentId, sourceVersionId);
        var draft = new DocumentVersionEntity();
        draft.id = UUID.randomUUID().toString();
        draft.documentId = source.documentId;
        draft.versionNo = nextVersionNo(source.documentId);
        draft.parentVersionId = source.id;
        draft.originImportJobId = source.originImportJobId;
        draft.draftRevision = 0;
        draft.sourceType = source.sourceType;
        draft.sourceFileName = source.sourceFileName;
        draft.sourceFileSha256 = source.sourceFileSha256;
        draft.converterVersion = source.converterVersion;
        draft.schemaVersion = source.schemaVersion;
        draft.status = "DRAFT";
        draft.language = source.language;
        draft.metadata = source.metadata;
        documentVersionMapper.insertSelective(draft);
        replaceContent(draft.id, packageFor(source));
        touchDocument(source.documentId);
        return summary(draft);
    }

    public ManagementDtos.EditableVersion editor(UUID versionId) {
        var version = requireDraft(versionId);
        return new ManagementDtos.EditableVersion(summary(version), packageFor(version));
    }

    @Transactional
    public ManagementDtos.EditableVersion save(UUID versionId, ManagementDtos.SaveDraftRequest request) {
        var version = requireDraft(versionId);
        if (request == null || request.documentPackage() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Document package is required");
        }
        if (request.draftRevision() != version.draftRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft was updated by another session");
        }
        var issues = validator.validate(request.documentPackage());
        if (issues.stream().anyMatch(issue -> "BLOCKING".equals(issue.severity()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Draft contains blocking structure errors");
        }
        replaceContent(version.id, request.documentPackage());
        version.draftRevision++;
        documentVersionMapper.update(version);
        touchDocument(version.documentId);
        return new ManagementDtos.EditableVersion(summary(version), packageFor(version));
    }

    @Transactional
    public void deleteDraft(UUID versionId) {
        var version = requireDraft(versionId);
        var jobs = importJobMapper.selectListByQuery(QueryWrapper.create()
                .select(IMPORT_JOB_ENTITY.ALL_COLUMNS)
                .from(IMPORT_JOB_ENTITY)
                .where(IMPORT_JOB_ENTITY.RESULT_VERSION_ID.eq(version.id)));
        for (var job : jobs) {
            var update = UpdateWrapper.of(ImportJobEntity.class)
                    .set(IMPORT_JOB_ENTITY.RESULT_VERSION_ID, null)
                    .set(IMPORT_JOB_ENTITY.STATUS, "READY")
                    .set(IMPORT_JOB_ENTITY.CURRENT_STAGE, "DRAFT_DISCARDED")
                    .set(IMPORT_JOB_ENTITY.ERROR_CODE, null)
                    .set(IMPORT_JOB_ENTITY.ERROR_MESSAGE, null);
            importJobMapper.updateByQuery(
                    update.toEntity(),
                    false,
                    QueryWrapper.create().where(IMPORT_JOB_ENTITY.ID.eq(job.id))
            );
        }
        deleteContent(version.id);
        documentVersionMapper.deleteById(version.id);
        touchDocument(version.documentId);
    }

    public ManagementDtos.EditorSnapshot editorSnapshot(UUID versionId) {
        var version = requireDraft(versionId);
        var document = requireDocument(uuid(version.documentId));
        var nodes = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(version.id))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()))
                .stream().map(this::editorNode).toList();
        return new ManagementDtos.EditorSnapshot(summary(version),
                new ManagementDtos.EditorDocument(uuid(document.id), document.code, document.title, document.description, version.language), nodes);
    }

    public ManagementDtos.NodeBlocksPage nodeBlocks(UUID versionId, UUID nodeId, String cursor, Integer limit) {
        var version = requireDraft(versionId);
        requireNode(version.id, nodeId);
        var safeLimit = Math.clamp(limit == null ? 40 : limit, 1, 100);
        var afterSeq = decodeBlockCursor(cursor);
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.id))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(id(nodeId)))
                .and(CONTENT_BLOCK_ENTITY.SEQ.gt(afterSeq))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc())
                .limit(safeLimit + 1));
        var hasNext = blocks.size() > safeLimit;
        var page = hasNext ? blocks.subList(0, safeLimit) : blocks;
        return new ManagementDtos.NodeBlocksPage(page.stream().map(this::editorBlock).toList(), hasNext ? Integer.toString(page.getLast().seq) : null);
    }

    @Transactional
    public ManagementDtos.EditorSnapshot updateNode(UUID versionId, UUID nodeId, ManagementDtos.UpdateNodeRequest request) {
        var version = requireDraft(versionId);
        requireRevision(version, request.draftRevision());
        var node = requireNode(version.id, nodeId);
        if (request.title() == null || request.title().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NODE_TITLE_REQUIRED", "节点标题不能为空。");
        }
        node.title = request.title().trim();
        node.nodeType = requiredNodeType(request.nodeType());
        node.semanticRole = blankToNull(request.semanticRole());
        node.anchor = blankToNull(request.anchor()) == null ? slug(node.nodeKey) : request.anchor().trim();
        contentNodeMapper.update(node);
        refreshNodeSearchText(version.id, node.id);
        advanceDraft(version);
        return editorSnapshot(versionId);
    }

    @Transactional
    public ManagementDtos.EditorSnapshot updateStructure(UUID versionId, ManagementDtos.StructureUpdateRequest request) {
        var version = requireDraft(versionId);
        requireRevision(version, request.draftRevision());
        if (request.nodes() == null || request.nodes().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STRUCTURE_REQUIRED", "文档结构不能为空。");
        }
        var persisted = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(version.id)));
        if (persisted.size() != request.nodes().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STRUCTURE_MISMATCH", "结构更新必须包含当前版本的全部节点。");
        }
        var byId = persisted.stream().collect(java.util.stream.Collectors.toMap(node -> node.id, node -> node));
        var requested = new HashMap<String, ManagementDtos.StructureNode>();
        for (var item : request.nodes()) {
            if (item == null || item.id() == null || byId.get(id(item.id())) == null || requested.put(id(item.id()), item) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "STRUCTURE_NODE_INVALID", "结构中包含无效或重复节点。");
            }
            if (item.parentId() != null && !byId.containsKey(id(item.parentId()))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "STRUCTURE_PARENT_INVALID", "目标父节点不存在。");
            }
            if (item.parentId() != null && item.parentId().equals(item.id())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "STRUCTURE_CYCLE", "节点不能成为自己的父节点。");
            }
        }
        assertAcyclic(requested);
        var children = new HashMap<String, List<ContentNodeEntity>>();
        for (var node : persisted) {
            var item = requested.get(node.id);
            node.parentId = item.parentId() == null ? null : id(item.parentId());
            node.sortOrder = item.sortOrder();
            children.computeIfAbsent(Objects.toString(node.parentId, "ROOT"), ignored -> new ArrayList<>()).add(node);
        }
        children.values().forEach(list -> list.sort(Comparator.comparingInt((ContentNodeEntity node) -> node.sortOrder).thenComparing(node -> node.id)));
        applyPaths(children, "ROOT", null, 1);
        persisted.forEach(contentNodeMapper::update);
        advanceDraft(version);
        return editorSnapshot(versionId);
    }

    @Transactional
    public ManagementDtos.EditorBlock updateBlock(UUID versionId, UUID blockId, ManagementDtos.UpdateBlockRequest request) {
        var version = requireDraft(versionId);
        requireRevision(version, request.draftRevision());
        var block = contentBlockMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.id))
                .and(CONTENT_BLOCK_ENTITY.ID.eq(id(blockId))));
        if (block == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "内容块不存在。");
        }
        block.blockType = requiredBlockType(request.blockType());
        block.payload = json(request.payload() == null ? Map.of("text", Objects.requireNonNullElse(request.plainText(), "")) : request.payload());
        block.plainText = Objects.requireNonNullElse(request.plainText(), "");
        block.language = blankToNull(request.language());
        contentBlockMapper.update(block);
        refreshNodeSearchText(version.id, block.nodeId);
        advanceDraft(version);
        return editorBlock(block);
    }
    @Transactional
    public ManagementDtos.EditorBlock createBlock(UUID versionId, UUID nodeId, ManagementDtos.CreateBlockRequest request) {
        var version = requireDraft(versionId);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BLOCK_REQUEST_REQUIRED", "新增内容块不能为空。");
        }
        requireRevision(version, request.draftRevision());
        requireNode(version.id, nodeId);
        var latest = contentBlockMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.id))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(id(nodeId)))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.desc())
                .limit(1));
        var block = new ContentBlockEntity();
        block.id = UUID.randomUUID().toString();
        block.versionId = version.id;
        block.nodeId = id(nodeId);
        block.blockKey = "manual-" + UUID.randomUUID();
        block.seq = latest == null ? 10 : latest.seq + 10;
        block.blockType = requiredBlockType(request.blockType());
        block.payload = json(request.payload() == null
                ? Map.of("text", Objects.requireNonNullElse(request.plainText(), ""))
                : request.payload());
        block.plainText = Objects.requireNonNullElse(request.plainText(), "");
        block.language = blankToNull(request.language());
        block.createdAt = OffsetDateTime.now();
        contentBlockMapper.insertSelective(block);
        refreshNodeSearchText(version.id, block.nodeId);
        advanceDraft(version);
        return editorBlock(block);
    }

    @Transactional
    public ManagementDtos.BlockMutationResult deleteBlock(UUID versionId, UUID blockId, long draftRevision) {
        var version = requireDraft(versionId);
        requireRevision(version, draftRevision);
        var block = contentBlockMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.id))
                .and(CONTENT_BLOCK_ENTITY.ID.eq(id(blockId))));
        if (block == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "内容块不存在。");
        }
        contentBlockMapper.deleteById(block.id);
        resequenceBlocks(version.id, block.nodeId);
        refreshNodeSearchText(version.id, block.nodeId);
        advanceDraft(version);
        return new ManagementDtos.BlockMutationResult(version.draftRevision, 1);
    }

    @Transactional
    public ManagementDtos.BlockMutationResult cleanupEmptyBlocks(UUID versionId, ManagementDtos.BlockCleanupRequest request) {
        var version = requireDraft(versionId);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BLOCK_CLEANUP_REQUEST_REQUIRED", "清理请求不能为空。");
        }
        requireRevision(version, request.draftRevision());
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.id)));
        var affectedNodeIds = new HashSet<String>();
        var removedCount = 0;
        for (var block : blocks) {
            if (DocumentBlockContent.isMeaningful(block.blockType, block.plainText, treeOrNull(block.payload))) {
                continue;
            }
            contentBlockMapper.deleteById(block.id);
            affectedNodeIds.add(block.nodeId);
            removedCount++;
        }
        if (removedCount > 0) {
            affectedNodeIds.forEach(nodeId -> {
                resequenceBlocks(version.id, nodeId);
                refreshNodeSearchText(version.id, nodeId);
            });
            advanceDraft(version);
        }
        return new ManagementDtos.BlockMutationResult(version.draftRevision, removedCount);
    }
    @Transactional
    public void publish(UUID documentId, UUID versionId) {
        requireDocument(documentId);
        documentQueryService.publish(documentId, versionId);
    }

    private ManagementDtos.EditorNode editorNode(ContentNodeEntity node) {
        return new ManagementDtos.EditorNode(uuid(node.id), uuid(node.parentId), node.nodeKey, node.nodeType, node.semanticRole,
                node.title, node.level, node.sortOrder, node.anchor, node.sourcePageStart, node.sourcePageEnd);
    }

    private ManagementDtos.EditorBlock editorBlock(ContentBlockEntity block) {
        var confidence = block.confidence == null ? null : block.confidence.doubleValue();
        return new ManagementDtos.EditorBlock(uuid(block.id), block.blockKey, block.seq, block.blockType, tree(block.payload),
                block.plainText, block.language, block.sourcePage, treeOrNull(block.sourceBbox), confidence);
    }

    private ContentNodeEntity requireNode(String versionId, UUID nodeId) {
        var node = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(versionId))
                .and(CONTENT_NODE_ENTITY.ID.eq(id(nodeId))));
        if (node == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND", "节点不存在。");
        }
        return node;
    }

    private void resequenceBlocks(String versionId, String nodeId) {
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(versionId))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(nodeId))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc(), CONTENT_BLOCK_ENTITY.ID.asc()));
        for (var index = 0; index < blocks.size(); index++) {
            var block = blocks.get(index);
            var sequence = (index + 1) * 10;
            if (block.seq != sequence) {
                block.seq = sequence;
                contentBlockMapper.update(block);
            }
        }
    }
    private void refreshNodeSearchText(String versionId, String nodeId) {
        var node = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(versionId))
                .and(CONTENT_NODE_ENTITY.ID.eq(nodeId)));
        if (node == null) {
            return;
        }
        var text = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(versionId))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(nodeId))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc()))
                .stream().map(block -> Objects.requireNonNullElse(block.plainText, "")).collect(java.util.stream.Collectors.joining("\n"));
        node.searchText = node.title + (text.isBlank() ? "" : "\n" + text);
        contentNodeMapper.update(node);
    }
    private void requireRevision(DocumentVersionEntity version, long requestedRevision) {
        if (requestedRevision != version.draftRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "DRAFT_REVISION_CONFLICT", "草稿已被其他操作更新，请刷新后再试。");
        }
    }

    private void advanceDraft(DocumentVersionEntity version) {
        version.draftRevision++;
        documentVersionMapper.update(version);
        touchDocument(version.documentId);
    }

    private void assertAcyclic(Map<String, ManagementDtos.StructureNode> nodes) {
        for (var nodeId : nodes.keySet()) {
            var seen = new HashSet<String>();
            var current = nodeId;
            while (current != null) {
                if (!seen.add(current)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "STRUCTURE_CYCLE", "结构中不能存在循环引用。");
                }
                var node = nodes.get(current);
                current = node == null || node.parentId() == null ? null : id(node.parentId());
            }
        }
    }

    private void applyPaths(Map<String, List<ContentNodeEntity>> children, String key, String parentPath, int level) {
        var siblings = children.getOrDefault(key, List.of());
        for (var index = 0; index < siblings.size(); index++) {
            var node = siblings.get(index);
            node.sortOrder = (index + 1) * 10;
            node.level = level;
            node.path = parentPath == null ? String.format("%06d", node.sortOrder) : parentPath + "." + String.format("%06d", node.sortOrder);
            applyPaths(children, node.id, node.path, level + 1);
        }
    }

    private int decodeBlockCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try { return Math.max(Integer.parseInt(cursor), 0); }
        catch (NumberFormatException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "内容块游标不合法。"); }
    }

    private static String requiredBlockType(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_REQUIRED", "内容块类型不能为空。");
        }
        var blockType = value.trim().toLowerCase(Locale.ROOT);
        if (!EDITABLE_BLOCK_TYPES.contains(blockType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BLOCK_TYPE_INVALID", "不支持的内容块类型：" + value);
        }
        return blockType;
    }
    private static String requiredNodeType(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_REQUIRED", "节点类型不能为空。");
        }
        var nodeType = value.trim().toUpperCase(Locale.ROOT);
        if (!EDITABLE_NODE_TYPES.contains(nodeType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NODE_TYPE_INVALID", "不支持的节点类型：" + value);
        }
        return nodeType;
    }
    private DocumentPackage packageFor(DocumentVersionEntity version) {
        var document = requireDocument(uuid(version.documentId));
        var nodes = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(version.id))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()));
        var nodeKeys = nodes.stream().collect(java.util.stream.Collectors.toMap(node -> node.id, node -> node.nodeKey));
        var sections = nodes.stream().map(node -> new DocumentPackage.SectionInfo(
                node.nodeKey, node.parentId == null ? null : nodeKeys.get(node.parentId), node.level, node.nodeType,
                node.semanticRole, node.title, node.sortOrder, node.anchor, node.sourcePageStart, node.sourcePageEnd,
                treeOrNull(node.sourceBbox), node.contentHash)).toList();
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.id))
                .orderBy(CONTENT_BLOCK_ENTITY.NODE_ID.asc(), CONTENT_BLOCK_ENTITY.SEQ.asc()))
                .stream().map(block -> new DocumentPackage.BlockInfo(
                        block.blockKey, nodeKeys.get(block.nodeId), block.seq, block.blockType, tree(block.payload), block.plainText,
                        block.language, block.sourcePage, treeOrNull(block.sourceBbox), block.confidence, block.contentHash)).toList();
        var assets = assetMapper.selectListByQuery(QueryWrapper.create()
                .select(ASSET_ENTITY.ALL_COLUMNS)
                .from(ASSET_ENTITY)
                .where(ASSET_ENTITY.VERSION_ID.eq(version.id))
                .orderBy(ASSET_ENTITY.ASSET_KEY.asc()))
                .stream().map(asset -> new DocumentPackage.AssetInfo(asset.assetKey, asset.objectKey, asset.mimeType, asset.sha256, alt(asset.metadata))).toList();
        return new DocumentPackage(version.schemaVersion,
                new DocumentPackage.DocumentInfo(document.code, document.title, document.description, version.language, List.of()),
                new DocumentPackage.VersionInfo("v" + version.versionNo, version.sourceType, version.sourceFileName,
                        version.sourceFileSha256, version.converterVersion, map(version.metadata)),
                sections, blocks, assets);
    }

    private void replaceContent(String versionId, DocumentPackage documentPackage) {
        deleteContent(versionId);
        var sections = new ArrayList<>(documentPackage.sections());
        sections.sort(Comparator.comparing(DocumentPackage.SectionInfo::level)
                .thenComparing(section -> Objects.requireNonNullElse(section.sortOrder(), 0))
                .thenComparing(DocumentPackage.SectionInfo::sectionKey));
        var nodeIds = new HashMap<String, String>();
        var paths = new HashMap<String, String>();
        var textBySection = new HashMap<String, List<String>>();
        documentPackage.blocks().forEach(block -> textBySection.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>()).add(block.plainText()));
        for (var section : sections) {
            var node = new ContentNodeEntity();
            node.id = UUID.randomUUID().toString();
            node.versionId = versionId;
            node.parentId = section.parentSectionKey() == null ? null : nodeIds.get(section.parentSectionKey());
            if (section.parentSectionKey() != null && node.parentId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown parent section: " + section.parentSectionKey());
            }
            node.nodeKey = section.sectionKey();
            node.nodeType = section.nodeType().toUpperCase(Locale.ROOT);
            node.semanticRole = blankToNull(section.semanticRole());
            node.title = section.title();
            node.level = section.level();
            node.sortOrder = section.sortOrder();
            var parentPath = section.parentSectionKey() == null ? null : paths.get(section.parentSectionKey());
            node.path = parentPath == null ? String.format("%06d", node.sortOrder) : parentPath + "." + String.format("%06d", node.sortOrder);
            node.anchor = blankToNull(section.anchor()) == null ? slug(section.sectionKey()) : section.anchor();
            node.sourcePageStart = section.sourcePageStart();
            node.sourcePageEnd = section.sourcePageEnd();
            node.sourceBbox = jsonOrNull(section.sourceBbox());
            node.contentHash = blankToNull(section.contentHash());
            node.searchText = section.title() + "\n" + String.join("\n", textBySection.getOrDefault(section.sectionKey(), List.of()));
            contentNodeMapper.insertSelective(node);
            nodeIds.put(section.sectionKey(), node.id);
            paths.put(section.sectionKey(), node.path);
        }
        for (var block : documentPackage.blocks()) {
            var nodeId = nodeIds.get(block.sectionKey());
            if (nodeId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown block section: " + block.sectionKey());
            }
            var entity = new ContentBlockEntity();
            entity.id = UUID.randomUUID().toString();
            entity.versionId = versionId;
            entity.nodeId = nodeId;
            entity.blockKey = block.blockKey();
            entity.seq = block.seq();
            entity.blockType = block.blockType();
            entity.payload = json(block.payload());
            entity.plainText = Objects.requireNonNullElse(block.plainText(), "");
            entity.language = blankToNull(block.language());
            entity.sourcePage = block.sourcePage();
            entity.sourceBbox = jsonOrNull(block.sourceBbox());
            entity.confidence = block.confidence();
            entity.contentHash = blankToNull(block.contentHash());
            contentBlockMapper.insertSelective(entity);
        }
        for (var asset : documentPackage.assets()) {
            var entity = new AssetEntity();
            entity.id = UUID.randomUUID().toString();
            entity.versionId = versionId;
            entity.assetKey = asset.assetKey();
            entity.objectKey = asset.path();
            entity.originalName = asset.path();
            entity.mimeType = asset.mimeType();
            entity.sha256 = asset.sha256();
            entity.sizeBytes = 0;
            entity.metadata = json(Map.of("alt", Objects.requireNonNullElse(asset.alt(), "")));
            assetMapper.insertSelective(entity);
        }
    }

    private void deleteContent(String versionId) {
        contentBlockMapper.selectListByQuery(QueryWrapper.create().select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS).from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(versionId))).forEach(block -> contentBlockMapper.deleteById(block.id));
        assetMapper.selectListByQuery(QueryWrapper.create().select(ASSET_ENTITY.ALL_COLUMNS).from(ASSET_ENTITY)
                .where(ASSET_ENTITY.VERSION_ID.eq(versionId))).forEach(asset -> assetMapper.deleteById(asset.id));
        contentNodeMapper.selectListByQuery(QueryWrapper.create().select(CONTENT_NODE_ENTITY.ALL_COLUMNS).from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(versionId)).orderBy(CONTENT_NODE_ENTITY.PATH.desc()))
                .forEach(node -> contentNodeMapper.deleteById(node.id));
    }

    private DocumentEntity requireDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create().select(DOCUMENT_ENTITY.ALL_COLUMNS).from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.eq(id(documentId))).and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID)));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
        return document;
    }

    private DocumentVersionEntity version(UUID documentId, UUID versionId) {
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create().select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS).from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId))).and(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId))));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Version not found");
        }
        return version;
    }

    private DocumentVersionEntity requireDraft(UUID versionId) {
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create().select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS).from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId))));
        if (version == null || !"DRAFT".equals(version.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "Only draft versions can be edited");
        }
        requireDocument(uuid(version.documentId));
        return version;
    }

    private int nextVersionNo(String documentId) {
        return documentVersionMapper.selectListByQuery(QueryWrapper.create().select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS).from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId))).stream().mapToInt(version -> version.versionNo).max().orElse(0) + 1;
    }

    private void touchDocument(String documentId) {
        var document = documentMapper.selectOneById(documentId);
        document.updatedAt = OffsetDateTime.now();
        documentMapper.update(document);
    }

    private ManagementDtos.AdminDocumentSummary documentSummary(DocumentEntity document, List<DocumentVersionEntity> versions) {
        return new ManagementDtos.AdminDocumentSummary(
                uuid(document.id), document.code, document.title, document.status, uuid(document.currentVersionId),
                versions.size(), versions.stream().filter(version -> "DRAFT".equals(version.status)).count(), document.updatedAt);
    }

    private ManagementDtos.VersionSummary summary(DocumentVersionEntity version) {
        return new ManagementDtos.VersionSummary(uuid(version.id), version.versionNo, uuid(version.parentVersionId), uuid(version.originImportJobId),
                version.sourceType, version.sourceFileName, version.status, version.draftRevision, version.publishedAt, version.createdAt);
    }

    private JsonNode tree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored JSON", exception);
        }
    }

    private JsonNode treeOrNull(String value) {
        return value == null || value.isBlank() ? null : tree(value);
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return objectMapper.convertValue(tree(value), new TypeReference<Map<String, Object>>() { });
    }

    private String alt(String metadata) {
        var value = treeOrNull(metadata);
        return value == null ? "" : value.path("alt").asText("");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize JSON", exception);
        }
    }

    private String jsonOrNull(JsonNode value) {
        return value == null || value.isNull() ? null : json(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String slug(String value) {
        var slug = (value == null ? "section" : value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "section" : slug;
    }

    private static String id(UUID value) {
        return value.toString();
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}