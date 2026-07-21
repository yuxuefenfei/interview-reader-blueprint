package com.example.interviewreader.management;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 文档级资料接口 DTO。 */
public final class DocumentMetadataDtos {
    private DocumentMetadataDtos() {
    }

    public record DocumentMetadata(
            UUID documentId,
            String code,
            String title,
            String description,
            List<String> tags,
            long metadataRevision,
            long duplicateTitleCount
    ) {
    }

    public record UpdateDocumentMetadataRequest(
            @NotNull @PositiveOrZero Long metadataRevision,
            @NotBlank @Size(max = 500) String title,
            @Size(max = 5_000) String description,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 50) String> tags
    ) {
    }
}