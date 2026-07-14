package com.example.interviewreader.management;

import com.example.interviewreader.importpkg.DocumentPackage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ManagementDtos {
    private ManagementDtos() {
    }

    public record AdminDocumentPage(List<AdminDocumentSummary> items, int page, int size, boolean hasNext) {
    }

    public record AdminDocumentSummary(
            UUID id,
            String code,
            String title,
            String status,
            UUID currentVersionId,
            long versionCount,
            long draftCount,
            OffsetDateTime updatedAt
    ) {
    }

    public record VersionSummary(
            UUID id,
            int versionNo,
            UUID parentVersionId,
            UUID originImportJobId,
            String sourceType,
            String sourceFileName,
            String status,
            long draftRevision,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt
    ) {
    }

    public record EditableVersion(VersionSummary version, DocumentPackage documentPackage) {
    }

    public record SaveDraftRequest(long draftRevision, DocumentPackage documentPackage) {
    }
}