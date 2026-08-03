package com.example.interviewreader.importpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.persistence.entity.AssetEntity;
import com.example.interviewreader.persistence.entity.ContentBlockEntity;
import com.example.interviewreader.persistence.entity.ContentNodeEntity;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.example.interviewreader.persistence.mapper.ContentBlockMapper;
import com.example.interviewreader.persistence.mapper.ContentNodeMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentPackageWriter {
    private static final int WRITE_BATCH_SIZE = 250;

    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final AssetMapper assetMapper;
    private final ObjectMapper objectMapper;

    public void writeNewVersion(String versionId, DocumentPackage documentPackage) {
        var sections = new ArrayList<>(documentPackage.sections());
        sections.sort(Comparator
                .comparing(DocumentPackage.SectionInfo::level)
                .thenComparing(section -> Objects.requireNonNullElse(section.sortOrder(), 0))
                .thenComparing(DocumentPackage.SectionInfo::sectionKey));

        var nodeIds = new HashMap<String, String>();
        var paths = new HashMap<String, String>();
        var blockTextBySection = blockTextBySection(documentPackage.blocks());
        var nodes = new ArrayList<ContentNodeEntity>(sections.size());
        for (var section : sections) {
            var parentKey = blankToNull(section.parentSectionKey());
            var parentId = parentKey == null ? null : nodeIds.get(parentKey);
            if (parentKey != null && parentId == null) {
                throw invalidReference("Unknown parent section: " + parentKey);
            }
            var sortOrder = Objects.requireNonNullElse(section.sortOrder(), 0);
            var parentPath = parentKey == null ? null : paths.get(parentKey);
            var pathPart = String.format("%06d", sortOrder);
            var path = parentPath == null ? pathPart : parentPath + "." + pathPart;
            var node = new ContentNodeEntity();
            node.setId(UUID.randomUUID().toString());
            node.setVersionId(versionId);
            node.setParentId(parentId);
            node.setNodeKey(section.sectionKey());
            node.setNodeType(section.nodeType());
            node.setSemanticRole(section.semanticRole());
            node.setTitle(section.title());
            node.setLevel(section.level());
            node.setPath(path);
            node.setSortOrder(sortOrder);
            node.setAnchor(blankToNull(section.anchor()) == null ? opaqueAnchor() : section.anchor());
            node.setSourcePageStart(section.sourcePageStart());
            node.setSourcePageEnd(section.sourcePageEnd());
            node.setSourceBbox(jsonOrNull(section.sourceBbox()));
            node.setContentHash(blankToNull(section.contentHash()));
            node.setSearchText(section.title() + "\n"
                    + String.join("\n", blockTextBySection.getOrDefault(section.sectionKey(), List.of())));
            nodes.add(node);
            nodeIds.put(section.sectionKey(), node.getId());
            paths.put(section.sectionKey(), path);
        }
        if (!nodes.isEmpty()) contentNodeMapper.insertBatchSelective(nodes, WRITE_BATCH_SIZE);

        var blocks = new ArrayList<ContentBlockEntity>(documentPackage.blocks().size());
        for (var block : documentPackage.blocks()) {
            var nodeId = nodeIds.get(block.sectionKey());
            if (nodeId == null) {
                throw invalidReference("Unknown block section: " + block.sectionKey());
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
            blocks.add(entity);
        }
        if (!blocks.isEmpty()) contentBlockMapper.insertBatchSelective(blocks, WRITE_BATCH_SIZE);

        var assets = new ArrayList<AssetEntity>(documentPackage.assets().size());
        for (var asset : documentPackage.assets()) {
            var entity = new AssetEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setVersionId(versionId);
            entity.setAssetKey(asset.assetKey());
            entity.setObjectKey(asset.path());
            entity.setOriginalName(asset.path());
            entity.setMimeType(asset.mimeType());
            entity.setSha256(asset.sha256() == null ? null : asset.sha256().toLowerCase(Locale.ROOT));
            entity.setSizeBytes(0);
            entity.setMetadata(json(Map.of(
                    "alt", Objects.requireNonNullElse(asset.alt(), ""),
                    "editorImage", asset.assetKey() != null && asset.assetKey().startsWith("editor-image-"))));
            assets.add(entity);
        }
        if (!assets.isEmpty()) assetMapper.insertBatchSelective(assets, WRITE_BATCH_SIZE);
    }

    private static Map<String, List<String>> blockTextBySection(List<DocumentPackage.BlockInfo> blocks) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var block : blocks) {
            result.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>())
                    .add(Objects.requireNonNullElse(block.plainText(), ""));
        }
        return result;
    }

    private ApiException invalidReference(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_PACKAGE_REFERENCE", message);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize document package", exception);
        }
    }

    private String jsonOrNull(JsonNode value) {
        return value == null || value.isNull() ? null : json(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String opaqueAnchor() {
        return "sec_" + UUID.randomUUID();
    }
}
