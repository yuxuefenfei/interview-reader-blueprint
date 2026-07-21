package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentDtos.*;
import com.example.interviewreader.persistence.entity.BookmarkEntity;
import com.example.interviewreader.persistence.entity.NoteEntity;
import com.example.interviewreader.persistence.entity.ReviewStateEntity;
import com.example.interviewreader.persistence.mapper.*;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.interviewreader.persistence.entity.table.BookmarkEntityTableDef.BOOKMARK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentBlockEntityTableDef.CONTENT_BLOCK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.NoteEntityTableDef.NOTE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ReviewStateEntityTableDef.REVIEW_STATE_ENTITY;

@Service
@RequiredArgsConstructor
public class InteractionService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();
    private static final String MASTERY_ORDER_SQL =
            "CASE COALESCE(review_state.mastery, '" + MasteryState.UNKNOWN.getCode() + "') "
                    + "WHEN '" + MasteryState.HARD.getCode() + "' THEN 0 "
                    + "WHEN '" + MasteryState.UNKNOWN.getCode() + "' THEN 1 "
                    + "WHEN '" + MasteryState.FUZZY.getCode() + "' THEN 2 ELSE 3 END";

    private final BookmarkMapper bookmarkMapper;
    private final NoteMapper noteMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;

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
            existing.setDocumentId(id(request.documentId()));
            existing.setSectionId(id(request.sectionId()));
            existing.setTitle(request.title());
            bookmarkMapper.update(existing);
            return getBookmark(uuid(existing.getId()));
        }

        var bookmark = new BookmarkEntity();
        bookmark.setId(UUID.randomUUID().toString());
        bookmark.setUserId(LOCAL_USER_ID);
        bookmark.setDocumentId(id(request.documentId()));
        bookmark.setVersionId(id(request.versionId()));
        bookmark.setSectionId(id(request.sectionId()));
        bookmark.setBlockId(id(request.blockId()));
        bookmark.setTitle(request.title());
        bookmarkMapper.insertSelective(bookmark);
        return getBookmark(uuid(bookmark.getId()));
    }

    @Transactional
    public void deleteBookmark(UUID bookmarkId) {
        var bookmark = bookmarkMapper.selectOneByQuery(QueryWrapper.create()
                .select(BOOKMARK_ENTITY.ALL_COLUMNS)
                .from(BOOKMARK_ENTITY)
                .where(BOOKMARK_ENTITY.ID.eq(id(bookmarkId)))
                .and(BOOKMARK_ENTITY.USER_ID.eq(LOCAL_USER_ID)));
        if (bookmark != null) {
            bookmarkMapper.deleteById(bookmark.getId());
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
        note.setId(UUID.randomUUID().toString());
        note.setUserId(LOCAL_USER_ID);
        note.setDocumentId(id(request.documentId()));
        note.setVersionId(id(request.versionId()));
        note.setSectionId(id(request.sectionId()));
        note.setBlockId(id(request.blockId()));
        note.setSelectedText(request.selectedText());
        note.setBody(request.body());
        noteMapper.insertSelective(note);
        return getNote(uuid(note.getId()));
    }

    @Transactional
    public ReviewStateDto upsertReviewState(UUID nodeId, ReviewStateRequest request) {
        verifyNodeBelongsToDocument(request.documentId(), nodeId);
        var mastery = request.mastery();
        var intervalDays = mastery.intervalDays();
        var dueAt = intervalDays == null ? null : OffsetDateTime.now().plusDays(intervalDays);

        var existing = reviewStateMapper.selectOneByQuery(QueryWrapper.create()
                .select(REVIEW_STATE_ENTITY.ALL_COLUMNS)
                .from(REVIEW_STATE_ENTITY)
                .where(REVIEW_STATE_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(REVIEW_STATE_ENTITY.NODE_ID.eq(id(nodeId))));
        if (existing == null) {
            var reviewState = new ReviewStateEntity();
            reviewState.setId(UUID.randomUUID().toString());
            reviewState.setUserId(LOCAL_USER_ID);
            reviewState.setDocumentId(id(request.documentId()));
            reviewState.setNodeId(id(nodeId));
            reviewState.setMastery(mastery);
            reviewState.setDueAt(dueAt);
            reviewState.setIntervalDays(intervalDays);
            reviewState.setRepetitions(mastery == MasteryState.UNKNOWN ? 0 : 1);
            reviewStateMapper.insertSelective(reviewState);
            return getReviewState(uuid(reviewState.getId()));
        }

        existing.setDocumentId(id(request.documentId()));
        existing.setMastery(mastery);
        existing.setDueAt(dueAt);
        existing.setIntervalDays(intervalDays);
        existing.setRepetitions(mastery == MasteryState.UNKNOWN ? 0 : existing.getRepetitions() + 1);
        existing.setUpdatedAt(OffsetDateTime.now());
        reviewStateMapper.update(existing);
        return getReviewState(uuid(existing.getId()));
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
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED))
                .and(CONTENT_NODE_ENTITY.NODE_TYPE.eq(NodeType.QUESTION)
                        .or(CONTENT_NODE_ENTITY.SEMANTIC_ROLE.eq(SemanticRole.QUESTION)))
                .orderByUnSafely(
                        MASTERY_ORDER_SQL,
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
                    .or(REVIEW_STATE_ENTITY.MASTERY.eq(MasteryState.UNKNOWN)));
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
                        row.mastery == null ? MasteryState.UNKNOWN : row.mastery,
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
        public NodeType nodeType;
        public SemanticRole semanticRole;
        public Integer sourcePageStart;
        public MasteryState mastery;
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
        var version = node == null ? null : documentVersionMapper.selectOneById(node.getVersionId());
        if (version == null || !id(documentId).equals(version.getDocumentId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Content node not found");
        }
    }

    private static BookmarkDto mapBookmark(BookmarkEntity entity) {
        return new BookmarkDto(
                uuid(entity.getId()),
                uuid(entity.getDocumentId()),
                uuid(entity.getVersionId()),
                uuid(entity.getSectionId()),
                uuid(entity.getBlockId()),
                entity.getTitle(),
                entity.getCreatedAt());
    }

    private static NoteDto mapNote(NoteEntity entity) {
        return new NoteDto(
                uuid(entity.getId()),
                uuid(entity.getDocumentId()),
                uuid(entity.getVersionId()),
                uuid(entity.getSectionId()),
                uuid(entity.getBlockId()),
                entity.getSelectedText(),
                entity.getBody(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static ReviewStateDto mapReviewState(ReviewStateEntity entity) {
        return new ReviewStateDto(
                uuid(entity.getId()),
                uuid(entity.getDocumentId()),
                uuid(entity.getNodeId()),
                entity.getMastery(),
                entity.getDueAt(),
                entity.getIntervalDays(),
                entity.getRepetitions(),
                entity.getUpdatedAt());
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
