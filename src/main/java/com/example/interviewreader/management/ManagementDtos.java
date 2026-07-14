package com.example.interviewreader.management;

import com.example.interviewreader.importpkg.DocumentPackage;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ManagementDtos {
    private ManagementDtos() {
    }

    public record AdminDocumentPage(List<AdminDocumentSummary> items, int page, int size, boolean hasNext) {
    }

    public record AdminDocumentSummary(
            UUID id, String code, String title, String status, UUID currentVersionId,
            long versionCount, long draftCount, OffsetDateTime updatedAt
    ) {
    }

    public record VersionSummary(
            UUID id, int versionNo, UUID parentVersionId, UUID originImportJobId,
            String sourceType, String sourceFileName, String status, long draftRevision,
            OffsetDateTime publishedAt, OffsetDateTime createdAt
    ) {
    }

    /** Legacy complete-package response retained for API compatibility only. */
    public record EditableVersion(VersionSummary version, DocumentPackage documentPackage) {
    }

    public record SaveDraftRequest(long draftRevision, DocumentPackage documentPackage) {
    }

    public record EditorSnapshot(VersionSummary version, EditorDocument document, List<EditorNode> nodes) {
    }

    public record EditorDocument(UUID id, String code, String title, String description, String language) {
    }

    public record EditorNode(
            UUID id, UUID parentId, String nodeKey, String nodeType, String semanticRole,
            String title, int level, int sortOrder, String anchor, Integer sourcePageStart, Integer sourcePageEnd
    ) {
    }

    public record NodeBlocksPage(List<EditorBlock> items, String nextCursor) {
    }

    public record EditorBlock(
            UUID id, String blockKey, int seq, String blockType, JsonNode payload,
            String plainText, String language, Integer sourcePage, JsonNode sourceBbox, Double confidence
    ) {
    }

    public record UpdateNodeRequest(long draftRevision, String title, String nodeType, String semanticRole, String anchor) {
    }

    public record StructureUpdateRequest(long draftRevision, List<StructureNode> nodes) {
    }

    public record StructureNode(UUID id, UUID parentId, int sortOrder) {
    }

    public record UpdateBlockRequest(long draftRevision, String blockType, JsonNode payload, String plainText, String language) {
    }
}