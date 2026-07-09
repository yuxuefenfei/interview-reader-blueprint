package com.example.interviewreader.exportpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.persistence.entity.AssetEntity;
import com.example.interviewreader.persistence.entity.ContentBlockEntity;
import com.example.interviewreader.persistence.entity.ContentNodeEntity;
import com.example.interviewreader.persistence.entity.DocumentVersionEntity;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.example.interviewreader.persistence.mapper.ContentBlockMapper;
import com.example.interviewreader.persistence.mapper.ContentNodeMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentTagMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.example.interviewreader.persistence.mapper.TagMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static com.example.interviewreader.persistence.entity.table.AssetEntityTableDef.ASSET_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentTagEntityTableDef.DOCUMENT_TAG_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.TagEntityTableDef.TAG_ENTITY;

@Service
public class DocumentPackageExportService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final AssetMapper assetMapper;
    private final DocumentTagMapper documentTagMapper;
    private final TagMapper tagMapper;
    private final ObjectMapper objectMapper;

    public DocumentPackageExportService(
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            ContentNodeMapper contentNodeMapper,
            ContentBlockMapper contentBlockMapper,
            AssetMapper assetMapper,
            DocumentTagMapper documentTagMapper,
            TagMapper tagMapper,
            ObjectMapper objectMapper
    ) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.contentNodeMapper = contentNodeMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.assetMapper = assetMapper;
        this.documentTagMapper = documentTagMapper;
        this.tagMapper = tagMapper;
        this.objectMapper = objectMapper;
    }

    public DocumentPackage exportJsonPackage(UUID documentId, UUID versionId) {
        var header = loadHeader(documentId, versionId);
        var nodes = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(versionId)))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()));
        var nodesById = nodes.stream().collect(Collectors.toMap(node -> node.id, Function.identity()));
        var tags = documentTagMapper.selectListByQuery(QueryWrapper.create()
                        .select(DOCUMENT_TAG_ENTITY.ALL_COLUMNS)
                        .from(DOCUMENT_TAG_ENTITY)
                        .where(DOCUMENT_TAG_ENTITY.DOCUMENT_ID.eq(id(documentId))))
                .stream()
                .map(link -> tagMapper.selectOneByQuery(QueryWrapper.create()
                        .select(TAG_ENTITY.ALL_COLUMNS)
                        .from(TAG_ENTITY)
                        .where(TAG_ENTITY.ID.eq(link.tagId))))
                .filter(tag -> tag != null)
                .map(tag -> tag.name)
                .sorted()
                .toList();
        var sections = nodes.stream()
                .map(node -> mapSection(node, nodesById.get(node.parentId)))
                .toList();
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                        .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                        .from(CONTENT_BLOCK_ENTITY)
                        .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(id(versionId))))
                .stream()
                .sorted(Comparator
                        .comparing((ContentBlockEntity block) -> nodesById.get(block.nodeId).path)
                        .thenComparingInt(block -> block.seq))
                .map(block -> mapBlock(block, nodesById.get(block.nodeId)))
                .toList();
        var assets = assetMapper.selectListByQuery(QueryWrapper.create()
                        .select(ASSET_ENTITY.ALL_COLUMNS)
                        .from(ASSET_ENTITY)
                        .where(ASSET_ENTITY.VERSION_ID.eq(id(versionId)))
                        .orderBy(ASSET_ENTITY.ASSET_KEY.asc()))
                .stream()
                .map(this::mapAsset)
                .toList();

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
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.ID.eq(id(documentId))));
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .and(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId))));
        if (document == null || version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document version not found");
        }
        return new Header(
                document.code,
                document.title,
                document.description,
                version.versionNo,
                version.sourceType,
                version.sourceFileName,
                version.sourceFileSha256,
                version.converterVersion,
                version.schemaVersion,
                version.language,
                readMap(version.metadata));
    }

    private DocumentPackage.SectionInfo mapSection(ContentNodeEntity node, ContentNodeEntity parent) {
        return new DocumentPackage.SectionInfo(
                node.nodeKey,
                parent == null ? null : parent.nodeKey,
                node.level,
                node.nodeType,
                node.semanticRole,
                node.title,
                node.sortOrder,
                node.anchor,
                node.sourcePageStart,
                node.sourcePageEnd,
                readTreeOrNull(node.sourceBbox),
                node.contentHash);
    }

    private DocumentPackage.BlockInfo mapBlock(ContentBlockEntity block, ContentNodeEntity node) {
        return new DocumentPackage.BlockInfo(
                block.blockKey,
                node.nodeKey,
                block.seq,
                block.blockType,
                readTreeOrNull(block.payload),
                block.plainText,
                block.language,
                block.sourcePage,
                readTreeOrNull(block.sourceBbox),
                block.confidence,
                block.contentHash);
    }

    private DocumentPackage.AssetInfo mapAsset(AssetEntity asset) {
        var metadata = readTreeOrNull(asset.metadata);
        var alt = metadata == null || metadata.get("alt") == null ? null : metadata.get("alt").asText();
        return new DocumentPackage.AssetInfo(
                asset.assetKey,
                asset.objectKey,
                asset.mimeType,
                asset.sha256.toLowerCase(Locale.ROOT),
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

    private static String id(UUID value) {
        return value == null ? null : value.toString();
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
