package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentDtos.BookmarkDto;
import com.example.interviewreader.document.DocumentDtos.BookmarkRequest;
import com.example.interviewreader.document.DocumentDtos.NoteDto;
import com.example.interviewreader.document.DocumentDtos.NoteRequest;
import com.example.interviewreader.document.DocumentDtos.ReviewStateDto;
import com.example.interviewreader.document.DocumentDtos.ReviewStateRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InteractionService {
    private final JdbcTemplate jdbc;

    public InteractionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public BookmarkDto createBookmark(BookmarkRequest request) {
        verifyDocumentVersion(request.documentId(), request.versionId());
        verifySection(request.versionId(), request.sectionId());
        verifyBlock(request.versionId(), request.sectionId(), request.blockId());

        var existing = jdbc.query("""
                SELECT id
                FROM bookmark
                WHERE user_id = ? AND version_id = ? AND block_id = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), AppConstants.LOCAL_USER_ID, request.versionId(), request.blockId());
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE bookmark
                    SET document_id = ?, section_id = ?, title = ?
                    WHERE id = ?
                    """, request.documentId(), request.sectionId(), request.title(), existing.getFirst());
            return getBookmark(existing.getFirst());
        }

        var bookmarkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookmark(id, user_id, document_id, version_id, section_id, block_id, title)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                bookmarkId,
                AppConstants.LOCAL_USER_ID,
                request.documentId(),
                request.versionId(),
                request.sectionId(),
                request.blockId(),
                request.title());
        return getBookmark(bookmarkId);
    }

    @Transactional
    public void deleteBookmark(UUID bookmarkId) {
        jdbc.update("DELETE FROM bookmark WHERE id = ? AND user_id = ?", bookmarkId, AppConstants.LOCAL_USER_ID);
    }

    @Transactional
    public NoteDto createNote(NoteRequest request) {
        verifyDocumentVersion(request.documentId(), request.versionId());
        verifySection(request.versionId(), request.sectionId());
        if (request.blockId() != null) {
            verifyBlock(request.versionId(), request.sectionId(), request.blockId());
        }

        var noteId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO note(id, user_id, document_id, version_id, section_id, block_id, selected_text, body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                noteId,
                AppConstants.LOCAL_USER_ID,
                request.documentId(),
                request.versionId(),
                request.sectionId(),
                request.blockId(),
                request.selectedText(),
                request.body());
        return getNote(noteId);
    }

    @Transactional
    public ReviewStateDto upsertReviewState(UUID nodeId, ReviewStateRequest request) {
        verifyNodeBelongsToDocument(request.documentId(), nodeId);
        var mastery = normalizeMastery(request.mastery());
        var intervalDays = intervalDays(mastery);
        var dueAt = intervalDays == null ? null : OffsetDateTime.now().plusDays(intervalDays);

        var existing = jdbc.query("""
                SELECT id, repetitions
                FROM review_state
                WHERE user_id = ? AND node_id = ?
                """, (rs, rowNum) -> new ExistingReviewState(rs.getObject("id", UUID.class), rs.getInt("repetitions")), AppConstants.LOCAL_USER_ID, nodeId);
        if (existing.isEmpty()) {
            var reviewStateId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO review_state(id, user_id, document_id, node_id, mastery, due_at, interval_days, repetitions)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    reviewStateId,
                    AppConstants.LOCAL_USER_ID,
                    request.documentId(),
                    nodeId,
                    mastery,
                    dueAt,
                    intervalDays,
                    "UNKNOWN".equals(mastery) ? 0 : 1);
            return getReviewState(reviewStateId);
        }

        var nextRepetitions = "UNKNOWN".equals(mastery) ? 0 : existing.getFirst().repetitions() + 1;
        jdbc.update("""
                UPDATE review_state
                SET document_id = ?, mastery = ?, due_at = ?, interval_days = ?, repetitions = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                request.documentId(),
                mastery,
                dueAt,
                intervalDays,
                nextRepetitions,
                existing.getFirst().id());
        return getReviewState(existing.getFirst().id());
    }

    private BookmarkDto getBookmark(UUID bookmarkId) {
        return jdbc.queryForObject("""
                SELECT id, document_id, version_id, section_id, block_id, title, created_at
                FROM bookmark
                WHERE id = ? AND user_id = ?
                """, this::mapBookmark, bookmarkId, AppConstants.LOCAL_USER_ID);
    }

    private NoteDto getNote(UUID noteId) {
        return jdbc.queryForObject("""
                SELECT id, document_id, version_id, section_id, block_id, selected_text, body, created_at, updated_at
                FROM note
                WHERE id = ? AND user_id = ?
                """, this::mapNote, noteId, AppConstants.LOCAL_USER_ID);
    }

    private ReviewStateDto getReviewState(UUID reviewStateId) {
        return jdbc.queryForObject("""
                SELECT id, document_id, node_id, mastery, due_at, interval_days, repetitions, updated_at
                FROM review_state
                WHERE id = ? AND user_id = ?
                """, this::mapReviewState, reviewStateId, AppConstants.LOCAL_USER_ID);
    }

    private void verifyDocumentVersion(UUID documentId, UUID versionId) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM document d
                JOIN document_version v ON v.document_id = d.id
                WHERE d.owner_id = ? AND d.id = ? AND v.id = ?
                """, Integer.class, AppConstants.LOCAL_USER_ID, documentId, versionId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document version not found");
        }
    }

    private void verifySection(UUID versionId, UUID sectionId) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM content_node
                WHERE version_id = ? AND id = ?
                """, Integer.class, versionId, sectionId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Section not found");
        }
    }

    private void verifyBlock(UUID versionId, UUID sectionId, UUID blockId) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM content_block
                WHERE version_id = ? AND node_id = ? AND id = ?
                """, Integer.class, versionId, sectionId, blockId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Block not found");
        }
    }

    private void verifyNodeBelongsToDocument(UUID documentId, UUID nodeId) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM content_node n
                JOIN document_version v ON v.id = n.version_id
                JOIN document d ON d.id = v.document_id
                WHERE d.owner_id = ? AND d.id = ? AND n.id = ?
                """, Integer.class, AppConstants.LOCAL_USER_ID, documentId, nodeId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Content node not found");
        }
    }

    private String normalizeMastery(String value) {
        var mastery = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (mastery) {
            case "UNKNOWN", "HARD", "FUZZY", "KNOWN" -> mastery;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "mastery must be UNKNOWN, HARD, FUZZY or KNOWN");
        };
    }

    private Integer intervalDays(String mastery) {
        return switch (mastery) {
            case "HARD" -> 1;
            case "FUZZY" -> 3;
            case "KNOWN" -> 7;
            default -> null;
        };
    }

    private BookmarkDto mapBookmark(ResultSet rs, int rowNum) throws SQLException {
        return new BookmarkDto(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getObject("block_id", UUID.class),
                rs.getString("title"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private NoteDto mapNote(ResultSet rs, int rowNum) throws SQLException {
        return new NoteDto(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getObject("block_id", UUID.class),
                rs.getString("selected_text"),
                rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ReviewStateDto mapReviewState(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewStateDto(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("node_id", UUID.class),
                rs.getString("mastery"),
                rs.getObject("due_at", OffsetDateTime.class),
                (Integer) rs.getObject("interval_days"),
                rs.getInt("repetitions"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private record ExistingReviewState(UUID id, int repetitions) {
    }
}
