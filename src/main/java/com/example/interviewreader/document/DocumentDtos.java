package com.example.interviewreader.document;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DocumentDtos {
    private DocumentDtos() {
    }

    public record DocumentSummary(
            UUID id,
            String code,
            String title,
            String description,
            UUID currentVersionId,
            BigDecimal progressRatio
    ) {
    }

    public record DocumentPage(
            List<DocumentSummary> items,
            String nextCursor
    ) {
    }

    public record TocNode(
            UUID id,
            UUID parentId,
            String title,
            int level,
            NodeType nodeType,
            SemanticRole semanticRole,
            String anchor,
            Integer sourcePageStart,
            List<TocNode> children
    ) {
    }

    public record ContentBlock(
            UUID id,
            String blockKey,
            int seq,
            BlockType blockType,
            JsonNode payload,
            String plainText,
            Integer sourcePage,
            JsonNode sourceBbox,
            BigDecimal confidence
    ) {
    }

    public record NodeContent(
            TocNode node,
            List<ContentBlock> blocks,
            Integer nextAfterSeq
    ) {
    }

    public record SearchHit(
            UUID documentId,
            UUID versionId,
            UUID nodeId,
            UUID blockId,
            String title,
            List<String> sectionPath,
            String snippet,
            BigDecimal score
    ) {
    }

    public record ReadingProgress(
            @NotNull UUID versionId,
            UUID sectionId,
            UUID blockId,
            @Min(0) int charOffset,
            @Min(0) int blockViewportOffset,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal progressRatio,
            OffsetDateTime clientUpdatedAt,
            @Size(max = 200) String deviceId,
            @Min(0) long revision
    ) {
    }

    public record BookmarkRequest(
            @NotNull UUID documentId,
            @NotNull UUID versionId,
            @NotNull UUID sectionId,
            @NotNull UUID blockId,
            @Size(max = 500) String title
    ) {
    }

    public record BookmarkDto(
            UUID id,
            UUID documentId,
            UUID versionId,
            UUID sectionId,
            UUID blockId,
            String title,
            OffsetDateTime createdAt
    ) {
    }

    public record NoteRequest(
            @NotNull UUID documentId,
            @NotNull UUID versionId,
            @NotNull UUID sectionId,
            UUID blockId,
            String selectedText,
            @NotBlank String body
    ) {
    }

    public record NoteDto(
            UUID id,
            UUID documentId,
            UUID versionId,
            UUID sectionId,
            UUID blockId,
            String selectedText,
            String body,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ReviewStateRequest(
            @NotNull UUID documentId,
            @NotBlank String mastery
    ) {
    }

    public record ReviewStateDto(
            UUID id,
            UUID documentId,
            UUID nodeId,
            String mastery,
            OffsetDateTime dueAt,
            Integer intervalDays,
            int repetitions,
            OffsetDateTime updatedAt
    ) {
    }

    public record ReviewQueueItem(
            UUID documentId,
            UUID versionId,
            UUID nodeId,
            String title,
            List<String> sectionPath,
            NodeType nodeType,
            SemanticRole semanticRole,
            Integer sourcePageStart,
            String mastery,
            OffsetDateTime dueAt,
            Integer intervalDays,
            int repetitions
    ) {
    }
}
