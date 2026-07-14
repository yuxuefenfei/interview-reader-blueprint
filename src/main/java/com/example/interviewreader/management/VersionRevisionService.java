package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentQueryService;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.importpkg.DocumentPackageValidator;
import com.example.interviewreader.importpkg.ImportIssueDto;
import com.example.interviewreader.persistence.entity.AssetEntity;
import com.example.interviewreader.persistence.entity.ContentBlockEntity;
import com.example.interviewreader.persistence.entity.ContentNodeEntity;
import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.example.interviewreader.persistence.entity.DocumentVersionEntity;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.example.interviewreader.persistence.mapper.ContentBlockMapper;
import com.example.interviewreader.persistence.mapper.ContentNodeMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.interviewreader.persistence.entity.table.AssetEntityTableDef.ASSET_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;

@Service
public class VersionRevisionService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final AssetMapper assetMapper;
    private final DocumentPackageValidator validator;
    private final DocumentQueryService documentQueryService;
    private final ObjectMapper objectMapper;

    public VersionRevisionService(
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            ContentNodeMapper contentNodeMapper,
            ContentBlockMapper contentBlockMapper,
            AssetMapper assetMapper,
            DocumentPackageValidator validator,
            DocumentQueryService documentQueryService,
            ObjectMapper objectMapper
    ) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.contentNodeMapper = contentNodeMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.assetMapper = assetMapper;
        this.validator = validator;
        this.documentQueryService = documentQueryService;
        this.objectMapper = objectMapper;
    }

    public ManagementDtos.AdminDocumentPage documents(Integer page, Integer size) {
        var safePage = Math.max(page == null ? 1 : page, 1);
        var safeSize = Math.clamp(size == null ? 20 : size, 1, 100);
        var offset = (safePage - 1) * safeSize;
        var documents = documentMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
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
        return new ManagementDtos.AdminDocumentPage(pageItems.stream().map(document -> {
            var documentVersions = byDocument.getOrDefault(document.id, List.of());
            return new ManagementDtos.AdminDocumentSummary(
                    uuid(document.id), document.code, document.title, document.status, uuid(document.currentVersionId),
                    documentVersions.size(), documentVersions.stream().filter(version -> "DRAFT".equals(version.status)).count(), document.updatedAt);
        }).toList(), safePage, safeSize, hasNext);
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
        deleteContent(version.id);
        documentVersionMapper.deleteById(version.id);
        touchDocument(version.documentId);
    }

    @Transactional
    public void publish(UUID documentId, UUID versionId) {
        requireDocument(documentId);
        documentQueryService.publish(documentId, versionId);
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