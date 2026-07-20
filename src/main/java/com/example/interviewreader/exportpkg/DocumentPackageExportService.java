package com.example.interviewreader.exportpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.importpkg.DocumentPackage;
import com.example.interviewreader.persistence.entity.*;
import com.example.interviewreader.persistence.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.interviewreader.persistence.entity.table.AssetEntityTableDef.ASSET_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentTagEntityTableDef.DOCUMENT_TAG_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.TagEntityTableDef.TAG_ENTITY;

@Service
@RequiredArgsConstructor
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

    public DocumentPackage exportJsonPackage(UUID documentId, UUID versionId) {
        var header = loadHeader(documentId, versionId);
        var nodes = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(versionId)))
                .orderBy(CONTENT_NODE_ENTITY.PATH.asc()));
        var nodesById = nodes.stream().collect(Collectors.toMap(node -> node.getId(), Function.identity()));
        var tagIds = documentTagMapper.selectListByQuery(QueryWrapper.create()
                        .select(DOCUMENT_TAG_ENTITY.ALL_COLUMNS)
                        .from(DOCUMENT_TAG_ENTITY)
                        .where(DOCUMENT_TAG_ENTITY.DOCUMENT_ID.eq(id(documentId))))
                .stream()
                .map(DocumentTagEntity::getTagId)
                .distinct()
                .toList();
        // 标签数量不应放大 SQL 次数；关联与标签各读取一次，并保持原有的名称排序契约。
        var tags = tagIds.isEmpty()
                ? List.<String>of()
                : tagMapper.selectListByQuery(QueryWrapper.create()
                                .select(TAG_ENTITY.ALL_COLUMNS)
                                .from(TAG_ENTITY)
                                .where(TAG_ENTITY.ID.in(tagIds)))
                        .stream()
                        .map(TagEntity::getName)
                        .sorted()
                        .toList();
        var sections = nodes.stream()
                .map(node -> mapSection(node, nodesById.get(node.getParentId())))
                .toList();
        var blocks = contentBlockMapper.selectListByQuery(QueryWrapper.create()
                        .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                        .from(CONTENT_BLOCK_ENTITY)
                        .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(id(versionId))))
                .stream()
                .sorted(Comparator
                        .comparing((ContentBlockEntity block) -> nodesById.get(block.getNodeId()).getPath())
                        .thenComparingInt(ContentBlockEntity::getSeq))
                .map(block -> mapBlock(block, nodesById.get(block.getNodeId())))
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
                document.getCode(),
                document.getTitle(),
                document.getDescription(),
                version.getVersionNo(),
                version.getSourceType(),
                version.getSourceFileName(),
                version.getSourceFileSha256(),
                version.getConverterVersion(),
                version.getSchemaVersion(),
                version.getLanguage(),
                readMap(version.getMetadata()));
    }

    private DocumentPackage.SectionInfo mapSection(ContentNodeEntity node, ContentNodeEntity parent) {
        return new DocumentPackage.SectionInfo(
                node.getNodeKey(),
                parent == null ? null : parent.getNodeKey(),
                node.getLevel(),
                node.getNodeType(),
                node.getSemanticRole(),
                node.getTitle(),
                node.getSortOrder(),
                node.getAnchor(),
                node.getSourcePageStart(),
                node.getSourcePageEnd(),
                readTreeOrNull(node.getSourceBbox()),
                node.getContentHash());
    }

    private DocumentPackage.BlockInfo mapBlock(ContentBlockEntity block, ContentNodeEntity node) {
        return new DocumentPackage.BlockInfo(
                block.getBlockKey(),
                node.getNodeKey(),
                block.getSeq(),
                block.getBlockType(),
                readTreeOrNull(block.getPayload()),
                block.getPlainText(),
                block.getLanguage(),
                block.getSourcePage(),
                readTreeOrNull(block.getSourceBbox()),
                block.getConfidence(),
                block.getContentHash());
    }

    private DocumentPackage.AssetInfo mapAsset(AssetEntity asset) {
        var metadata = readTreeOrNull(asset.getMetadata());
        var alt = metadata == null || metadata.get("alt") == null ? null : metadata.get("alt").asText();
        return new DocumentPackage.AssetInfo(
                asset.getAssetKey(),
                asset.getObjectKey(),
                asset.getMimeType(),
                asset.getSha256().toLowerCase(Locale.ROOT),
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
