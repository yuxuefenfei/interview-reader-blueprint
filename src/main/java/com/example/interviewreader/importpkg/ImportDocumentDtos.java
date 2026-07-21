package com.example.interviewreader.importpkg;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 导入任务中的文档级资料预览、编辑与冲突决议 DTO。 */
public final class ImportDocumentDtos {
    private ImportDocumentDtos() {
    }

    public record ExistingDocumentMatch(UUID id, String code, String title, String status) {
    }

    public record ImportDocumentPreview(
            String documentKey,
            String title,
            String description,
            List<String> tags,
            boolean editable,
            ExistingDocumentMatch matchingDocument,
            String suggestedDocumentKey,
            long duplicateTitleCount
    ) {
    }

    public record UpdateImportDocumentMetadataRequest(
            @NotBlank @Size(max = 500) String title,
            @Size(max = 5_000) String description,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 50) String> tags
    ) {
    }

    public record CommitImportRequest(ImportResolution resolution) {
    }
}
