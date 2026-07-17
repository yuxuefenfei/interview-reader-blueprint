package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentDtos.BookmarkDto;
import com.example.interviewreader.document.DocumentDtos.BookmarkRequest;
import com.example.interviewreader.document.DocumentDtos.NoteDto;
import com.example.interviewreader.document.DocumentDtos.NoteRequest;
import com.example.interviewreader.document.DocumentDtos.ReviewQueueItem;
import com.example.interviewreader.document.DocumentDtos.ReviewStateDto;
import com.example.interviewreader.document.DocumentDtos.ReviewStateRequest;
import com.example.interviewreader.persistence.entity.BookmarkEntity;
import com.example.interviewreader.persistence.entity.NoteEntity;
import com.example.interviewreader.persistence.entity.ReviewStateEntity;
import com.example.interviewreader.persistence.mapper.BookmarkMapper;
import com.example.interviewreader.persistence.mapper.ContentBlockMapper;
import com.example.interviewreader.persistence.mapper.ContentNodeMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.example.interviewreader.persistence.mapper.NoteMapper;
import com.example.interviewreader.persistence.mapper.ReviewStateMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.interviewreader.persistence.entity.table.BookmarkEntityTableDef.BOOKMARK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.NoteEntityTableDef.NOTE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ReviewStateEntityTableDef.REVIEW_STATE_ENTITY;

@Service
public class InteractionService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final BookmarkMapper bookmarkMapper;
    private final NoteMapper noteMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;

    public InteractionService(
            BookmarkMapper bookmarkMapper,
            NoteMapper noteMapper,
            ReviewStateMapper reviewStateMapper,
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            ContentNodeMapper contentNodeMapper,
            ContentBlockMapper contentBlockMapper
    ) {
        this.bookmarkMapper = bookmarkMapper;
        this.noteMapper = noteMapper;
        this.reviewStateMapper = reviewStateMapper;
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.contentNodeMapper = contentNodeMapper;
        this.contentBlockMapper = contentBlockMapper;
    }

    @Transactional
    public BookmarkDto createBookmark(BookmarkRequest request) {
        verifyDocumentVersion(request.documentId(), request.versionId());
        verifySection(request.versionId(), request.sectionId());
        verifyBlock(request.versionId(), request.sectionId(), request.blockId());

        var existing = bookmarkMapper.selectOneByQuery(QueryWrapper.create()
                .select(BOOKMARK_ENTITY.ALL_COLUMNS)
                .from(BOOKMARK_ENTITY)
                .where(BOOKMARK_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(BOOKMARK_ENTITY.VERSION_ID.eq(id(request.versionId())))
                .and(BOOKMARK_ENTITY.BLOCK_ID.eq(id(request.blockId()))));
        if (existing != null) {
            existing.documentId = id(request.documentId());
            existing.sectionId = id(request.sectionId());
            existing.title = request.title();
            bookmarkMapper.update(existing);
            return getBookmark(uuid(existing.id));
        }

        var bookmark = new BookmarkEntity();
        bookmark.id = UUID.randomUUID().toString();
        bookmark.userId = LOCAL_USER_ID;
        bookmark.documentId = id(request.documentId());
        bookmark.versionId = id(request.versionId());
        bookmark.sectionId = id(request.sectionId());
        bookmark.blockId = id(request.blockId());
        bookmark.title = request.title();
        bookmarkMapper.insertSelective(bookmark);
        return getBookmark(uuid(bookmark.id));
    }

    @Transactional
    public void deleteBookmark(UUID bookmarkId) {
        var bookmark = bookmarkMapper.selectOneByQuery(QueryWrapper.create()
                .select(BOOKMARK_ENTITY.ALL_COLUMNS)
                .from(BOOKMARK_ENTITY)
                .where(BOOKMARK_ENTITY.ID.eq(id(bookmarkId)))
                .and(BOOKMARK_ENTITY.USER_ID.eq(LOCAL_USER_ID)));
        if (bookmark != null) {
            bookmarkMapper.deleteById(bookmark.id);
        }
    }

    @Transactional
    public NoteDto createNote(NoteRequest request) {
        verifyDocumentVersion(request.documentId(), request.versionId());
        verifySection(request.versionId(), request.sectionId());
        if (request.blockId() != null) {
            verifyBlock(request.versionId(), request.sectionId(), request.blockId());
        }

        var note = new NoteEntity();
        note.id = UUID.randomUUID().toString();
        note.userId = LOCAL_USER_ID;
        note.documentId = id(request.documentId());
        note.versionId = id(request.versionId());
        note.sectionId = id(request.sectionId());
        note.blockId = id(request.blockId());
        note.selectedText = request.selectedText();
        note.body = request.body();
        noteMapper.insertSelective(note);
        return getNote(uuid(note.id));
    }

    @Transactional
    public ReviewStateDto upsertReviewState(UUID nodeId, ReviewStateRequest request) {
        verifyNodeBelongsToDocument(request.documentId(), nodeId);
        var mastery = normalizeMastery(request.mastery());
        var intervalDays = intervalDays(mastery);
        var dueAt = intervalDays == null ? null : OffsetDateTime.now().plusDays(intervalDays);

        var existing = reviewStateMapper.selectOneByQuery(QueryWrapper.create()
                .select(REVIEW_STATE_ENTITY.ALL_COLUMNS)
                .from(REVIEW_STATE_ENTITY)
                .where(REVIEW_STATE_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(REVIEW_STATE_ENTITY.NODE_ID.eq(id(nodeId))));
        if (existing == null) {
            var reviewState = new ReviewStateEntity();
            reviewState.id = UUID.randomUUID().toString();
            reviewState.userId = LOCAL_USER_ID;
            reviewState.documentId = id(request.documentId());
            reviewState.nodeId = id(nodeId);
            reviewState.mastery = mastery;
            reviewState.dueAt = dueAt;
            reviewState.intervalDays = intervalDays;
            reviewState.repetitions = "UNKNOWN".equals(mastery) ? 0 : 1;
            reviewStateMapper.insertSelective(reviewState);
            return getReviewState(uuid(reviewState.id));
        }

        existing.documentId = id(request.documentId());
        existing.mastery = mastery;
        existing.dueAt = dueAt;
        existing.intervalDays = intervalDays;
        existing.repetitions = "UNKNOWN".equals(mastery) ? 0 : existing.repetitions + 1;
        existing.updatedAt = OffsetDateTime.now();
        reviewStateMapper.update(existing);
        return getReviewState(uuid(existing.id));
    }

    public List<ReviewQueueItem> reviewQueue(UUID documentId, Integer limit, boolean dueOnly) {
        if (documentId != null) {
            verifyDocument(documentId);
        }
        var safeLimit = Math.clamp(limit == null ? 5 : limit, 1, 50);
        var now = OffsetDateTime.now();
        var query = QueryWrapper.create()
                .select(
                        DOCUMENT_ENTITY.ID.as("documentId"),
                        DOCUMENT_VERSION_ENTITY.ID.as("versionId"),
                        CONTENT_NODE_ENTITY.ID.as("nodeId"),
                        CONTENT_NODE_ENTITY.TITLE,
                        CONTENT_NODE_ENTITY.NODE_TYPE,
                        CONTENT_NODE_ENTITY.SEMANTIC_ROLE,
                        CONTENT_NODE_ENTITY.SOURCE_PAGE_START,
                        REVIEW_STATE_ENTITY.MASTERY,
                        REVIEW_STATE_ENTITY.DUE_AT,
                        REVIEW_STATE_ENTITY.INTERVAL_DAYS,
                        REVIEW_STATE_ENTITY.REPETITIONS)
                .from(CONTENT_NODE_ENTITY)
                .innerJoin(DOCUMENT_VERSION_ENTITY).on(CONTENT_NODE_ENTITY.VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID))
                .innerJoin(DOCUMENT_ENTITY).on(DOCUMENT_ENTITY.CURRENT_VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID))
                .leftJoin(REVIEW_STATE_ENTITY).on(REVIEW_STATE_ENTITY.NODE_ID.eq(CONTENT_NODE_ENTITY.ID)
                        .and(REVIEW_STATE_ENTITY.USER_ID.eq(LOCAL_USER_ID)))
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq("PUBLISHED"))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq("PUBLISHED"))
                .and(CONTENT_NODE_ENTITY.NODE_TYPE.eq("QUESTION")
                        .or(CONTENT_NODE_ENTITY.SEMANTIC_ROLE.eq("QUESTION")))
                .orderByUnSafely(
                        "CASE COALESCE(review_state.mastery, 'UNKNOWN') WHEN 'HARD' THEN 0 WHEN 'UNKNOWN' THEN 1 WHEN 'FUZZY' THEN 2 ELSE 3 END",
                        "CASE WHEN review_state.due_at IS NULL OR review_state.due_at <= CURRENT_TIMESTAMP THEN 0 ELSE 1 END",
                        "content_node.title ASC")
                .limit(safeLimit);
        if (documentId != null) {
            query.and(DOCUMENT_ENTITY.ID.eq(id(documentId)));
        }
        if (dueOnly) {
            query.and(REVIEW_STATE_ENTITY.ID.isNull()
                    .or(REVIEW_STATE_ENTITY.DUE_AT.isNull())
                    .or(REVIEW_STATE_ENTITY.DUE_AT.le(now))
                    .or(REVIEW_STATE_ENTITY.MASTERY.eq("UNKNOWN")));
        }
        return contentNodeMapper.selectListByQueryAs(query, ReviewQueueRow.class).stream()
                .map(row -> new ReviewQueueItem(
                        uuid(row.documentId),
                        uuid(row.versionId),
                        uuid(row.nodeId),
                        row.title,
                        List.of(row.title),
                        row.nodeType,
                        row.semanticRole,
                        row.sourcePageStart,
                        row.mastery == null ? "UNKNOWN" : row.mastery,
                        row.dueAt,
                        row.intervalDays,
                        row.repetitions == null ? 0 : row.repetitions))
                .toList();
    }

    public static class ReviewQueueRow {
        public String documentId;
        public String versionId;
        public String nodeId;
        public String title;
        public String nodeType;
        public String semanticRole;
        public Integer sourcePageStart;
        public String mastery;
        public OffsetDateTime dueAt;
        public Integer intervalDays;
        public Integer repetitions;
    }

    private BookmarkDto getBookmark(UUID bookmarkId) {
        var bookmark = bookmarkMapper.selectOneByQuery(QueryWrapper.create()
                .select(BOOKMARK_ENTITY.ALL_COLUMNS)
                .from(BOOKMARK_ENTITY)
                .where(BOOKMARK_ENTITY.ID.eq(id(bookmarkId)))
                .and(BOOKMARK_ENTITY.USER_ID.eq(LOCAL_USER_ID)));
        return mapBookmark(bookmark);
    }

    private NoteDto getNote(UUID noteId) {
        var note = noteMapper.selectOneByQuery(QueryWrapper.create()
                .select(NOTE_ENTITY.ALL_COLUMNS)
                .from(NOTE_ENTITY)
                .where(NOTE_ENTITY.ID.eq(id(noteId)))
                .and(NOTE_ENTITY.USER_ID.eq(LOCAL_USER_ID)));
        return mapNote(note);
    }

    private ReviewStateDto getReviewState(UUID reviewStateId) {
        var reviewState = reviewStateMapper.selectOneByQuery(QueryWrapper.create()
                .select(REVIEW_STATE_ENTITY.ALL_COLUMNS)
                .from(REVIEW_STATE_ENTITY)
                .where(REVIEW_STATE_ENTITY.ID.eq(id(reviewStateId)))
                .and(REVIEW_STATE_ENTITY.USER_ID.eq(LOCAL_USER_ID)));
        return mapReviewState(reviewState);
    }

    private void verifyDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.ID.eq(id(documentId))));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }

    private void verifyDocumentVersion(UUID documentId, UUID versionId) {
        verifyDocument(documentId);
        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(id(documentId)))
                .and(DOCUMENT_VERSION_ENTITY.ID.eq(id(versionId))));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document version not found");
        }
    }

    private void verifySection(UUID versionId, UUID sectionId) {
        var section = contentNodeMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                .from(CONTENT_NODE_ENTITY)
                .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(id(versionId)))
                .and(CONTENT_NODE_ENTITY.ID.eq(id(sectionId))));
        if (section == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Section not found");
        }
    }

    private void verifyBlock(UUID versionId, UUID sectionId, UUID blockId) {
        var block = contentBlockMapper.selectOneByQuery(QueryWrapper.create()
                .select(CONTENT_BLOCK_ENTITY.ALL_COLUMNS)
                .from(CONTENT_BLOCK_ENTITY)
                .where(CONTENT_BLOCK_ENTITY.VERSION_ID.eq(id(versionId)))
                .and(CONTENT_BLOCK_ENTITY.NODE_ID.eq(id(sectionId)))
                .and(CONTENT_BLOCK_ENTITY.ID.eq(id(blockId))));
        if (block == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Block not found");
        }
    }

    private void verifyNodeBelongsToDocument(UUID documentId, UUID nodeId) {
        verifyDocument(documentId);
        var node = contentNodeMapper.selectOneById(id(nodeId));
        var version = node == null ? null : documentVersionMapper.selectOneById(node.versionId);
        if (version == null || !id(documentId).equals(version.documentId)) {
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


    private static BookmarkDto mapBookmark(BookmarkEntity entity) {
        return new BookmarkDto(
                uuid(entity.id),
                uuid(entity.documentId),
                uuid(entity.versionId),
                uuid(entity.sectionId),
                uuid(entity.blockId),
                entity.title,
                entity.createdAt);
    }

    private static NoteDto mapNote(NoteEntity entity) {
        return new NoteDto(
                uuid(entity.id),
                uuid(entity.documentId),
                uuid(entity.versionId),
                uuid(entity.sectionId),
                uuid(entity.blockId),
                entity.selectedText,
                entity.body,
                entity.createdAt,
                entity.updatedAt);
    }

    private static ReviewStateDto mapReviewState(ReviewStateEntity entity) {
        return new ReviewStateDto(
                uuid(entity.id),
                uuid(entity.documentId),
                uuid(entity.nodeId),
                entity.mastery,
                entity.dueAt,
                entity.intervalDays,
                entity.repetitions,
                entity.updatedAt);
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
