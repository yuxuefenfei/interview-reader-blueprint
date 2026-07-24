package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.config.UploadProperties;
import com.example.interviewreader.document.DocumentQueryService;
import com.example.interviewreader.document.DocumentVersionStatus;
import com.example.interviewreader.document.BlockType;
import com.example.interviewreader.document.NodeType;
import com.example.interviewreader.importpkg.DocumentBlockContent;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.importpkg.DocumentPackageValidator;
import com.example.interviewreader.importpkg.ImportIssueSeverity;
import com.example.interviewreader.importpkg.ImportJobStatus;
import com.example.interviewreader.importpkg.ImportStage;
import com.example.interviewreader.importpkg.SourceFileStorage;
import com.example.interviewreader.persistence.entity.*;
import com.example.interviewreader.persistence.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.util.*;

import static com.example.interviewreader.persistence.entity.table.AssetEntityTableDef.ASSET_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentDeletionJobEntityTableDef.DOCUMENT_DELETION_JOB_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ImportJobEntityTableDef.IMPORT_JOB_ENTITY;

@Service
@RequiredArgsConstructor
public class VersionRevisionService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final AssetMapper assetMapper;
    private final ImportJobMapper importJobMapper;
    private final DocumentPackageValidator validator;
    private final DocumentDeletionJobMapper deletionJobMapper;
    private final DocumentQueryService documentQueryService;
    private final ObjectMapper objectMapper;
    private final SourceFileStorage storage;
    private final UploadProperties uploadProperties;

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
        var ids = pageItems.stream().map(DocumentEntity::getId).toList();
        var versions = ids.isEmpty() ? List.<DocumentVersionEntity>of() : documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.in(ids)));
        var byDocument = new HashMap<String, List<DocumentVersionEntity>>();
        versions.forEach(version -> byDocument.computeIfAbsent(version.getDocumentId(), ignored -> new ArrayList<>()).add(version));
        var deletionByDocument = ids.isEmpty() ? Map.<String, DocumentDeletionJobEntity>of() : deletionJobMapper.selectListByQuery(QueryWrapper.create()
                .where(com.example.interviewreader.persistence.entity.table.DocumentDeletionJobEntityTableDef.DOCUMENT_DELETION_JOB_ENTITY.DOCUMENT_ID.in(ids)))
                .stream().collect(java.util.stream.Collectors.toMap(DocumentDeletionJobEntity::getDocumentId, job -> job));
        return new ManagementDtos.AdminDocumentPage(pageItems.stream()
                .map(document -> documentSummary(document, byDocument.getOrDefault(document.getId(), List.of()), deletionByDocument.get(document.getId())))
                .toList(), safePage, safeSize, hasNext);
    }

    public ManagementDtos.AdminDocumentSummary document(UUID documentId) {
        var document = requireDocument(documentId);
        var versions = documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(document.getId())));
        return documentSummary(document, versions, deleteJob(document.getId()));
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
        DocumentLifecycleService.rejectLocked(requireDocumentForUpdate(documentId));
        var source = version(documentId, sourceVersionId);
        var draft = new DocumentVersionEntity();
        draft.setId(UUID.randomUUID().toString());
        draft.setDocumentId(source.getDocumentId());
        draft.setVersionNo(nextVersionNo(source.getDocumentId()));
        draft.setParentVersionId(source.getId());
        draft.setParentVersionNo(source.getVersionNo());
        draft.setOriginImportJobId(source.getOriginImportJobId());
        draft.setDraftRevision(0);
        draft.setSourceType(source.getSourceType());
        draft.setSourceFileName(source.getSourceFileName());
        draft.setSourceFileSha256(source.getSourceFileSha256());
        draft.setConverterVersion(source.getConverterVersion());
        draft.setSchemaVersion(source.getSchemaVersion());
        draft.setStatus(DocumentVersionStatus.DRAFT);
        draft.setLanguage(source.getLanguage());
        draft.setMetadata(source.getMetadata());
        documentVersionMapper.insertSelective(draft);
        replaceContent(draft.getId(), packageFor(source));
        touchDocument(source.getDocumentId());
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
        if (request.draftRevision() != version.getDraftRevision()) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft was updated by another session");
        }
        var issues = validator.validate(request.documentPackage());
        if (issues.stream().anyMatch(issue -> issue.severity() == ImportIssueSeverity.BLOCKING)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Draft contains blocking structure errors");
        }
        replaceContent(version.getId(), request.documentPackage());
        advanceDraft(version);
        return new ManagementDtos.EditableVersion(summary(version), packageFor(version));
    }

    @Transactional
    public void deleteDraft(UUID versionId) {
        var version = requireDraft(versionId);
        var removedObjectKeys = editorImageObjectKeys(version.getId());
        resetImportJobs(version.getId());
        deleteContent(version.getId());
        documentVersionMapper.deleteById(version.getId());
        touchDocument(version.getDocumentId());
        deleteObjectsIfUnreferencedAfterCommit(removedObjectKeys);
    }


    public ManagementDtos.EditorSnapshot editorSnapshot(UUID versionId) {
        var version = requireDraft(versionId);
        var document = requireDocument(uuid(version.getDocumentId()));
        var nodes = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(version.getId()))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()))
                .stream().map(this::editorNode).toList();
        return new ManagementDtos.EditorSnapshot(summary(version),
                new ManagementDtos.EditorDocument(uuid(document.getId()), document.getCode(), document.getTitle(), document.getDescription(), version.getLanguage()), nodes);
    }

    public ManagementDtos.NodeBlocksPage nodeBlocks(UUID versionId, UUID nodeId, String cursor, Integer limit) {
        var version = requireDraft(versionId);
        requireNode(version.getId(), nodeId);
        var safeLimit = Math.clamp(limit == null ? 40 : limit, 1, 100);
        var afterSeq = decodeBlockCursor(cursor);
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.getId()))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(id(nodeId)))
                .and(CONTENT_BLOCK_ENTITY.SEQ.gt(afterSeq))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.asc())
                .limit(safeLimit + 1));
        var hasNext = blocks.size() > safeLimit;
        var page = hasNext ? blocks.subList(0, safeLimit) : blocks;
        return new ManagementDtos.NodeBlocksPage(page.stream().map(this::editorBlock).toList(), hasNext ? Integer.toString(page.getLast().getSeq()) : null);
    }

    @Transactional
    public ManagementDtos.EditorSnapshot updateNode(UUID versionId, UUID nodeId, ManagementDtos.UpdateNodeRequest request) {
        var version = requireDraft(versionId);
        requireRevision(version, request.draftRevision());
        var node = requireNode(version.getId(), nodeId);
        if (request.title() == null || request.title().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NODE_TITLE_REQUIRED", "节点标题不能为空。");
        }
        node.setTitle(request.title().trim());
        node.setNodeType(requiredNodeType(request.nodeType()));
        node.setSemanticRole(request.semanticRole());
        node.setAnchor(blankToNull(request.anchor()) == null ? slug(node.getNodeKey()) : request.anchor().trim());
        contentNodeMapper.update(node);
        refreshNodeSearchText(version.getId(), node.getId());
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
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(version.getId())));
        if (persisted.size() != request.nodes().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STRUCTURE_MISMATCH", "结构更新必须包含当前版本的全部节点。");
        }
        var byId = persisted.stream().collect(java.util.stream.Collectors.toMap(ContentNodeEntity::getId, node -> node));
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
            var item = requested.get(node.getId());
            node.setParentId(item.parentId() == null ? null : id(item.parentId()));
            node.setSortOrder(item.sortOrder());
            children.computeIfAbsent(Objects.toString(node.getParentId(), "ROOT"), ignored -> new ArrayList<>()).add(node);
        }
        children.values().forEach(list -> list.sort(Comparator.comparingInt(ContentNodeEntity::getSortOrder).thenComparing(ContentNodeEntity::getId)));
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
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.getId()))
                .and(CONTENT_BLOCK_ENTITY.ID.eq(id(blockId))));
        if (block == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "内容块不存在。");
        }
        block.setBlockType(requiredBlockType(request.blockType()));
        block.setPayload(json(request.payload() == null ? Map.of("text", Objects.requireNonNullElse(request.plainText(), "")) : request.payload()));
        block.setPlainText(Objects.requireNonNullElse(request.plainText(), ""));
        block.setLanguage(blankToNull(request.language()));
        contentBlockMapper.update(block);
        refreshNodeSearchText(version.getId(), block.getNodeId());
        advanceDraft(version);
        cleanupUnusedEditorImages(version.getId());
        return editorBlock(block);
    }

    /**
     * Stores a verified image and attaches it to one image block in the same draft-revision update.
     * This avoids a temporary, unreferenced upload when a browser refreshes between two requests.
     */
    @Transactional
    public ManagementDtos.ImageBlockUploadResult replaceBlockImage(
            UUID versionId, UUID blockId, long draftRevision, MultipartFile file,
            String alt, boolean decorative, String caption
    ) {
        var version = requireDraft(versionId);
        requireRevision(version, draftRevision);
        var block = requireBlock(version.getId(), blockId);
        if (block.getBlockType() != BlockType.IMAGE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_BLOCK_REQUIRED", "请先将内容块类型切换为图片。");
        }
        var image = readVerifiedImage(file);
        var normalizedAlt = normalizeImageText(alt, "图片替代文本", 300);
        var normalizedCaption = normalizeImageText(caption, "图片说明", 1_000);
        if (!decorative && normalizedAlt.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_ALT_REQUIRED", "非装饰性图片必须填写替代文本。");
        }
        if (decorative && !normalizedAlt.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_ALT_DECORATIVE", "装饰性图片不能填写替代文本。");
        }

        var assetKey = "editor-image-" + UUID.randomUUID();
        var objectKey = storage.save(image.bytes(), image.sha256(), assetKey + image.extension());
        deleteObjectOnRollback(objectKey);

        var asset = new AssetEntity();
        asset.setId(UUID.randomUUID().toString());
        asset.setVersionId(version.getId());
        asset.setAssetKey(assetKey);
        asset.setObjectKey(objectKey);
        asset.setOriginalName(safeOriginalName(file.getOriginalFilename(), assetKey + image.extension()));
        asset.setMimeType(image.mimeType());
        asset.setSha256(image.sha256());
        asset.setSizeBytes((long) image.bytes().length);
        asset.setWidthPx(image.width());
        asset.setHeightPx(image.height());
        asset.setMetadata(json(Map.of("editorImage", true)));
        assetMapper.insertSelective(asset);

        var payload = objectMapper.createObjectNode();
        payload.put("assetKey", assetKey);
        payload.put("alt", normalizedAlt);
        payload.put("decorative", decorative);
        if (!normalizedCaption.isBlank()) {
            payload.put("caption", normalizedCaption);
        }
        block.setPayload(json(payload));
        block.setPlainText(normalizedAlt);
        block.setLanguage(null);
        contentBlockMapper.update(block);
        refreshNodeSearchText(version.getId(), block.getNodeId());
        advanceDraft(version);
        cleanupUnusedEditorImages(version.getId());
        return new ManagementDtos.ImageBlockUploadResult(editorBlock(block), version.getDraftRevision());
    }

    public StoredImage draftImage(UUID versionId, String assetKey) {
        var version = requireDraft(versionId);
        var asset = requireImageAsset(version.getId(), assetKey);
        var source = storage.load(asset.getObjectKey());
        return new StoredImage(asset.getMimeType(), asset.getSha256(), source.bytes());
    }
    @Transactional
    public ManagementDtos.EditorBlock createBlock(UUID versionId, UUID nodeId, ManagementDtos.CreateBlockRequest request) {
        var version = requireDraft(versionId);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BLOCK_REQUEST_REQUIRED", "新增内容块不能为空。");
        }
        requireRevision(version, request.draftRevision());
        requireNode(version.getId(), nodeId);
        var latest = contentBlockMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.getId()))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(id(nodeId)))
                .orderBy(CONTENT_BLOCK_ENTITY.SEQ.desc())
                .limit(1));
        var block = new ContentBlockEntity();
        block.setId(UUID.randomUUID().toString());
        block.setVersionId(version.getId());
        block.setNodeId(id(nodeId));
        block.setBlockKey("manual-" + UUID.randomUUID());
        block.setSeq(latest == null ? 10 : latest.getSeq() + 10);
        block.setBlockType(requiredBlockType(request.blockType()));
        block.setPayload(json(request.payload() == null
                ? Map.of("text", Objects.requireNonNullElse(request.plainText(), ""))
                : request.payload()));
        block.setPlainText(Objects.requireNonNullElse(request.plainText(), ""));
        block.setLanguage(blankToNull(request.language()));
        block.setCreatedAt(OffsetDateTime.now());
        contentBlockMapper.insertSelective(block);
        refreshNodeSearchText(version.getId(), block.getNodeId());
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
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.getId()))
                .and(CONTENT_BLOCK_ENTITY.ID.eq(id(blockId))));
        if (block == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "内容块不存在。");
        }
        contentBlockMapper.deleteById(block.getId());
        resequenceBlocks(version.getId(), block.getNodeId());
        refreshNodeSearchText(version.getId(), block.getNodeId());
        advanceDraft(version);
        cleanupUnusedEditorImages(version.getId());
        return new ManagementDtos.BlockMutationResult(version.getDraftRevision(), 1);
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
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.getId())));
        var affectedNodeIds = new HashSet<String>();
        var removedCount = 0;
        for (var block : blocks) {
            if (DocumentBlockContent.isMeaningful(block.getBlockType(), block.getPlainText(), treeOrNull(block.getPayload()))) {
                continue;
            }
            contentBlockMapper.deleteById(block.getId());
            affectedNodeIds.add(block.getNodeId());
            removedCount++;
        }
        if (removedCount > 0) {
            affectedNodeIds.forEach(nodeId -> {
                resequenceBlocks(version.getId(), nodeId);
                refreshNodeSearchText(version.getId(), nodeId);
            });
            advanceDraft(version);
            cleanupUnusedEditorImages(version.getId());
        }
        return new ManagementDtos.BlockMutationResult(version.getDraftRevision(), removedCount);
    }
    @Transactional
    public void publish(UUID documentId, UUID versionId) {
        DocumentLifecycleService.rejectLocked(requireDocumentForUpdate(documentId));
        documentQueryService.publish(documentId, versionId);
    }

    private ManagementDtos.EditorNode editorNode(ContentNodeEntity node) {
        return new ManagementDtos.EditorNode(uuid(node.getId()), uuid(node.getParentId()), node.getNodeKey(), node.getNodeType(), node.getSemanticRole(),
                node.getTitle(), node.getLevel(), node.getSortOrder(), node.getAnchor(), node.getSourcePageStart(), node.getSourcePageEnd());
    }

    private ManagementDtos.EditorBlock editorBlock(ContentBlockEntity block) {
        var confidence = block.getConfidence() == null ? null : block.getConfidence().doubleValue();
        return new ManagementDtos.EditorBlock(uuid(block.getId()), block.getBlockKey(), block.getSeq(), block.getBlockType(), tree(block.getPayload()),
                block.getPlainText(), block.getLanguage(), block.getSourcePage(), treeOrNull(block.getSourceBbox()), confidence);
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

    private ContentBlockEntity requireBlock(String versionId, UUID blockId) {
        var block = contentBlockMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(versionId))
                .and(CONTENT_BLOCK_ENTITY.ID.eq(id(blockId))));
        if (block == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "内容块不存在。");
        }
        return block;
    }

    private AssetEntity requireImageAsset(String versionId, String assetKey) {
        if (assetKey == null || assetKey.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "图片不存在。");
        }
        var asset = assetMapper.selectOneByQuery(QueryWrapper.create()
                .select(ASSET_ENTITY.ALL_COLUMNS)
                .from(ASSET_ENTITY)
                .where(ASSET_ENTITY.VERSION_ID.eq(versionId))
                .and(ASSET_ENTITY.ASSET_KEY.eq(assetKey)));
        if (asset == null || asset.getMimeType() == null || !asset.getMimeType().startsWith("image/")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "图片不存在。");
        }
        return asset;
    }

    private VerifiedImage readVerifiedImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_FILE_REQUIRED", "请选择 PNG、JPEG 或 WebP 图片。");
        }
        if (file.getSize() > uploadProperties.maxSize().toBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "图片不能超过 " + uploadProperties.displayMaxSize() + "。");
        }
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_READ_FAILED", "无法读取上传的图片。");
        }
        var kind = imageKind(bytes);
        if (kind == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_TYPE_INVALID", "仅支持内容有效的 PNG、JPEG 或 WebP 图片。");
        }
        var dimensions = imageDimensions(bytes, kind);
        if (kind != ImageKind.WEBP && dimensions[0] == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_CONTENT_INVALID", "图片内容损坏或格式无效。");
        }
        return new VerifiedImage(bytes, sha256(bytes), kind.mimeType(), kind.extension(), dimensions[0], dimensions[1]);
    }

    private ImageKind imageKind(byte[] bytes) {
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return ImageKind.PNG;
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) {
            return ImageKind.JPEG;
        }
        if (bytes.length >= 20
                && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
                && littleEndianUInt32(bytes, 4) == bytes.length - 8L
                && Set.of("VP8 ", "VP8L", "VP8X").contains(new String(bytes, 12, 4, StandardCharsets.US_ASCII))) {
            return ImageKind.WEBP;
        }
        return null;
    }

    private static long littleEndianUInt32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xff)
                | (((long) bytes[offset + 1] & 0xff) << 8)
                | (((long) bytes[offset + 2] & 0xff) << 16)
                | (((long) bytes[offset + 3] & 0xff) << 24);
    }

    private Integer[] imageDimensions(byte[] bytes, ImageKind kind) {
        try (var input = new java.io.ByteArrayInputStream(bytes)) {
            var image = javax.imageio.ImageIO.read(input);
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new Integer[]{image.getWidth(), image.getHeight()};
            }
        } catch (IOException ignored) {
            // Header validation above remains authoritative for WebP when no ImageIO WebP reader is installed.
        }
        return new Integer[]{null, null};
    }

    private List<String> editorImageObjectKeys(String versionId) {
        return assetMapper.selectListByQuery(QueryWrapper.create()
                        .select(ASSET_ENTITY.ALL_COLUMNS)
                        .from(ASSET_ENTITY)
                        .where(ASSET_ENTITY.VERSION_ID.eq(versionId)))
                .stream()
                .filter(this::isEditorImage)
                .map(AssetEntity::getObjectKey)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Removes assets created by this editor only after no image block in the draft references them. */
    private void cleanupUnusedEditorImages(String versionId) {
        var referencedKeys = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                        .select(CONTENT_BLOCK_ENTITY.PAYLOAD)
                        .from(CONTENT_BLOCK_ENTITY)
                        .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(versionId)))
                .stream()
                .map(ContentBlockEntity::getPayload)
                .map(this::treeOrNull)
                .filter(Objects::nonNull)
                .map(payload -> payload.path("assetKey").asText(""))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        var removedObjectKeys = new ArrayList<String>();
        for (var asset : assetMapper.selectListByQuery(QueryWrapper.create()
                .select(ASSET_ENTITY.ALL_COLUMNS)
                .from(ASSET_ENTITY)
                .where(ASSET_ENTITY.VERSION_ID.eq(versionId)))) {
            if (!isEditorImage(asset) || referencedKeys.contains(asset.getAssetKey())) {
                continue;
            }
            assetMapper.deleteById(asset.getId());
            if (asset.getObjectKey() != null) {
                removedObjectKeys.add(asset.getObjectKey());
            }
        }
        deleteObjectsIfUnreferencedAfterCommit(removedObjectKeys);
    }

    private boolean isEditorImage(AssetEntity asset) {
        var metadata = treeOrNull(asset.getMetadata());
        return metadata != null && metadata.path("editorImage").asBoolean(false);
    }

    private void deleteObjectOnRollback(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.deleteIfManaged(objectKey);
                }
            }
        });
    }

    private void deleteObjectsIfUnreferencedAfterCommit(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        var keys = objectKeys.stream().filter(Objects::nonNull).filter(key -> !key.isBlank()).distinct().toList();
        if (keys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (var objectKey : keys) {
                    if (!objectKeyReferenced(objectKey)) {
                        storage.deleteIfManaged(objectKey);
                    }
                }
            }
        });
    }

    private boolean objectKeyReferenced(String objectKey) {
        if (assetMapper.selectCountByQuery(QueryWrapper.create().from(ASSET_ENTITY)
                .where(ASSET_ENTITY.OBJECT_KEY.eq(objectKey))) > 0) {
            return true;
        }
        return importJobMapper.selectCountByQuery(QueryWrapper.create().from(IMPORT_JOB_ENTITY)
                .where(IMPORT_JOB_ENTITY.SOURCE_OBJECT_KEY.eq(objectKey)
                        .or(IMPORT_JOB_ENTITY.RAW_EXTRACTION_OBJECT_KEY.eq(objectKey))
                        .or(IMPORT_JOB_ENTITY.NORMALIZED_OBJECT_KEY.eq(objectKey)))) > 0;
    }

    private static String normalizeImageText(String value, String field, int maxLength) {
        var normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_TEXT_TOO_LONG", field + "不能超过 " + maxLength + " 个字符。");
        }
        return normalized;
    }

    private static String safeOriginalName(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        var normalized = value.replace('\\', '/');
        var lastSlash = normalized.lastIndexOf('/');
        return (lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized).substring(0,
                Math.min(lastSlash >= 0 ? normalized.length() - lastSlash - 1 : normalized.length(), 255));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record StoredImage(String mimeType, String sha256, byte[] bytes) {
    }

    private record VerifiedImage(byte[] bytes, String sha256, String mimeType, String extension, Integer width, Integer height) {
    }

    private enum ImageKind {
        PNG("image/png", ".png"), JPEG("image/jpeg", ".jpg"), WEBP("image/webp", ".webp");
        private final String mimeType;
        private final String extension;
        ImageKind(String mimeType, String extension) { this.mimeType = mimeType; this.extension = extension; }
        String mimeType() { return mimeType; }
        String extension() { return extension; }
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
            if (block.getSeq() != sequence) {
                block.setSeq(sequence);
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
                .stream().map(block -> Objects.requireNonNullElse(block.getPlainText(), "")).collect(java.util.stream.Collectors.joining("\n"));
        node.setSearchText(node.getTitle() + (text.isBlank() ? "" : "\n" + text));
        contentNodeMapper.update(node);
    }
    private void requireRevision(DocumentVersionEntity version, long requestedRevision) {
        if (requestedRevision != version.getDraftRevision()) {
            throw new ApiException(HttpStatus.CONFLICT, "DRAFT_REVISION_CONFLICT", "草稿已被其他操作更新，请刷新后再试。");
        }
    }

    private void advanceDraft(DocumentVersionEntity version) {
        var expectedRevision = version.getDraftRevision();
        var nextRevision = expectedRevision + 1;
        var update = UpdateWrapper.of(DocumentVersionEntity.class)
                .set(DOCUMENT_VERSION_ENTITY.DRAFT_REVISION, nextRevision);
        var updated = documentVersionMapper.updateByQuery(
                update.toEntity(),
                false,
                QueryWrapper.create()
                        .where(DOCUMENT_VERSION_ENTITY.ID.eq(version.getId()))
                        .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.DRAFT))
                        .and(DOCUMENT_VERSION_ENTITY.DRAFT_REVISION.eq(expectedRevision)));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "DRAFT_REVISION_CONFLICT", "草稿已被其他操作更新，请刷新后再试。");
        }
        version.setDraftRevision(nextRevision);
        touchDocument(version.getDocumentId());
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
            node.setSortOrder((index + 1) * 10);
            node.setLevel(level);
            node.setPath(parentPath == null ? String.format("%06d", node.getSortOrder()) : parentPath + "." + String.format("%06d", node.getSortOrder()));
            applyPaths(children, node.getId(), node.getPath(), level + 1);
        }
    }

    private int decodeBlockCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try { return Math.max(Integer.parseInt(cursor), 0); }
        catch (NumberFormatException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "内容块游标不合法。"); }
    }

    private static BlockType requiredBlockType(BlockType value) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_REQUIRED", "内容块类型不能为空。");
        }
        return value;
    }
    private static NodeType requiredNodeType(NodeType value) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_REQUIRED", "节点类型不能为空。");
        }
        return value;
    }
    private DocumentPackage packageFor(DocumentVersionEntity version) {
        var document = requireDocument(uuid(version.getDocumentId()));
        var nodes = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(version.getId()))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()));
        var nodeKeys = nodes.stream().collect(java.util.stream.Collectors.toMap(ContentNodeEntity::getId, ContentNodeEntity::getNodeKey));
        var sections = nodes.stream().map(node -> new DocumentPackage.SectionInfo(
                node.getNodeKey(), node.getParentId() == null ? null : nodeKeys.get(node.getParentId()), node.getLevel(), node.getNodeType(),
                node.getSemanticRole(), node.getTitle(), node.getSortOrder(), node.getAnchor(), node.getSourcePageStart(), node.getSourcePageEnd(),
                treeOrNull(node.getSourceBbox()), node.getContentHash())).toList();
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(version.getId()))
                .orderBy(CONTENT_BLOCK_ENTITY.NODE_ID.asc(), CONTENT_BLOCK_ENTITY.SEQ.asc()))
                .stream().map(block -> new DocumentPackage.BlockInfo(
                        block.getBlockKey(), nodeKeys.get(block.getNodeId()), block.getSeq(), block.getBlockType(), tree(block.getPayload()), block.getPlainText(),
                        block.getLanguage(), block.getSourcePage(), treeOrNull(block.getSourceBbox()), block.getConfidence(), block.getContentHash())).toList();
        var assets = assetMapper.selectListByQuery(QueryWrapper.create()
                .select(ASSET_ENTITY.ALL_COLUMNS)
                .from(ASSET_ENTITY)
                .where(ASSET_ENTITY.VERSION_ID.eq(version.getId()))
                .orderBy(ASSET_ENTITY.ASSET_KEY.asc()))
                .stream().map(asset -> new DocumentPackage.AssetInfo(asset.getAssetKey(), asset.getObjectKey(), asset.getMimeType(), asset.getSha256(), alt(asset.getMetadata()))).toList();
        return new DocumentPackage(version.getSchemaVersion(),
                new DocumentPackage.DocumentInfo(document.getCode(), document.getTitle(), document.getDescription(), version.getLanguage(), List.of()),
                new DocumentPackage.VersionInfo("v" + version.getVersionNo(), version.getSourceType(), version.getSourceFileName(),
                        version.getSourceFileSha256(), version.getConverterVersion(), map(version.getMetadata())),
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
            node.setId(UUID.randomUUID().toString());
            node.setVersionId(versionId);
            node.setParentId(section.parentSectionKey() == null ? null : nodeIds.get(section.parentSectionKey()));
            if (section.parentSectionKey() != null && node.getParentId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown parent section: " + section.parentSectionKey());
            }
            node.setNodeKey(section.sectionKey());
            node.setNodeType(section.nodeType());
            node.setSemanticRole(section.semanticRole());
            node.setTitle(section.title());
            node.setLevel(section.level());
            node.setSortOrder(section.sortOrder());
            var parentPath = section.parentSectionKey() == null ? null : paths.get(section.parentSectionKey());
            node.setPath(parentPath == null ? String.format("%06d", node.getSortOrder()) : parentPath + "." + String.format("%06d", node.getSortOrder()));
            node.setAnchor(blankToNull(section.anchor()) == null ? slug(section.sectionKey()) : section.anchor());
            node.setSourcePageStart(section.sourcePageStart());
            node.setSourcePageEnd(section.sourcePageEnd());
            node.setSourceBbox(jsonOrNull(section.sourceBbox()));
            node.setContentHash(blankToNull(section.contentHash()));
            node.setSearchText(section.title() + "\n" + String.join("\n", textBySection.getOrDefault(section.sectionKey(), List.of())));
            contentNodeMapper.insertSelective(node);
            nodeIds.put(section.sectionKey(), node.getId());
            paths.put(section.sectionKey(), node.getPath());
        }
        for (var block : documentPackage.blocks()) {
            var nodeId = nodeIds.get(block.sectionKey());
            if (nodeId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown block section: " + block.sectionKey());
            }
            var entity = new ContentBlockEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setVersionId(versionId);
            entity.setNodeId(nodeId);
            entity.setBlockKey(block.blockKey());
            entity.setSeq(block.seq());
            entity.setBlockType(block.blockType());
            entity.setPayload(json(block.payload()));
            entity.setPlainText(Objects.requireNonNullElse(block.plainText(), ""));
            entity.setLanguage(blankToNull(block.language()));
            entity.setSourcePage(block.sourcePage());
            entity.setSourceBbox(jsonOrNull(block.sourceBbox()));
            entity.setConfidence(block.confidence());
            entity.setContentHash(blankToNull(block.contentHash()));
            contentBlockMapper.insertSelective(entity);
        }
        for (var asset : documentPackage.assets()) {
            var entity = new AssetEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setVersionId(versionId);
            entity.setAssetKey(asset.assetKey());
            entity.setObjectKey(asset.path());
            entity.setOriginalName(asset.path());
            entity.setMimeType(asset.mimeType());
            entity.setSha256(asset.sha256());
            entity.setSizeBytes(0);
            entity.setMetadata(json(Map.of(
                    "alt", Objects.requireNonNullElse(asset.alt(), ""),
                    "editorImage", asset.assetKey() != null && asset.assetKey().startsWith("editor-image-"))));
            assetMapper.insertSelective(entity);
        }
    }

    private void resetImportJobs(String versionId) {
        var jobs = importJobMapper.selectListByQuery(QueryWrapper.create()
                .select(IMPORT_JOB_ENTITY.ALL_COLUMNS)
                .from(IMPORT_JOB_ENTITY)
                .where(IMPORT_JOB_ENTITY.RESULT_VERSION_ID.eq(versionId)));
        for (var job : jobs) {
            var update = UpdateWrapper.of(ImportJobEntity.class)
                    .set(IMPORT_JOB_ENTITY.RESULT_VERSION_ID, null)
                    .set(IMPORT_JOB_ENTITY.STATUS, ImportJobStatus.READY)
                    .set(IMPORT_JOB_ENTITY.CURRENT_STAGE, ImportStage.DRAFT_DISCARDED)
                    .set(IMPORT_JOB_ENTITY.ERROR_CODE, null)
                    .set(IMPORT_JOB_ENTITY.ERROR_MESSAGE, null);
            importJobMapper.updateByQuery(
                    update.toEntity(),
                    false,
                    QueryWrapper.create().where(IMPORT_JOB_ENTITY.ID.eq(job.getId()))
            );
        }
    }

    private void deleteContent(String versionId) {
        // 先解除叶子表引用，再整批删除节点；节点自关联由两套数据库迁移中的 ON DELETE CASCADE 保证。
        contentBlockMapper.deleteByQuery(QueryWrapper.create()
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(versionId)));
        assetMapper.deleteByQuery(QueryWrapper.create()
                .where(ASSET_ENTITY.VERSION_ID.eq(versionId)));
        contentNodeMapper.deleteByQuery(QueryWrapper.create()
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(versionId)));
    }

    private DocumentEntity requireDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create().select(DOCUMENT_ENTITY.ALL_COLUMNS).from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.eq(id(documentId))).and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID)));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
        return document;
    }

    private DocumentEntity requireDocumentForUpdate(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.eq(id(documentId)))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .forUpdate());
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
        if (version == null || version.getStatus() != DocumentVersionStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "Only draft versions can be edited");
        }
        DocumentLifecycleService.rejectLocked(requireDocument(uuid(version.getDocumentId())));
        return version;
    }

    private int nextVersionNo(String documentId) {
        // createRevision 已锁定所属文档行，同一文档的版本号分配因此会按事务串行执行。
        var latest = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.VERSION_NO)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId))
                .orderBy(DOCUMENT_VERSION_ENTITY.VERSION_NO.desc())
                .limit(1));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private void touchDocument(String documentId) {
        var document = documentMapper.selectOneById(documentId);
        document.setUpdatedAt(OffsetDateTime.now());
        documentMapper.update(document);
    }

    private DocumentDeletionJobEntity deleteJob(String documentId) {
        return deletionJobMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_DELETION_JOB_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_DELETION_JOB_ENTITY)
                .where(DOCUMENT_DELETION_JOB_ENTITY.DOCUMENT_ID.eq(documentId))
                .and(DOCUMENT_DELETION_JOB_ENTITY.OWNER_ID.eq(LOCAL_USER_ID)));
    }

    private ManagementDtos.AdminDocumentSummary documentSummary(DocumentEntity document, List<DocumentVersionEntity> versions, DocumentDeletionJobEntity deletionJob) {
        return new ManagementDtos.AdminDocumentSummary(
                uuid(document.getId()), document.getCode(), document.getTitle(), document.getStatus(), uuid(document.getCurrentVersionId()),
                versions.size(), versions.stream().filter(version -> version.getStatus() == DocumentVersionStatus.DRAFT).count(), document.getUpdatedAt(),
                deletionJob == null ? null : DocumentLifecycleService.summary(deletionJob));
    }

    private ManagementDtos.VersionSummary summary(DocumentVersionEntity version) {
        return new ManagementDtos.VersionSummary(uuid(version.getId()), version.getVersionNo(), uuid(version.getParentVersionId()), version.getParentVersionNo(), uuid(version.getOriginImportJobId()),
                version.getSourceType(), version.getSourceFileName(), version.getStatus(), version.getDraftRevision(), version.getPublishedAt(), version.getCreatedAt());
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
        return objectMapper.convertValue(tree(value), new TypeReference<>() {
        });
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
