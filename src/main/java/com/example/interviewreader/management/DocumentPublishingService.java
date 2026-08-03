package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentStatus;
import com.example.interviewreader.document.DocumentVersionStatus;
import com.example.interviewreader.persistence.entity.DocumentVersionEntity;
import com.example.interviewreader.persistence.entity.ReadingProgressEntity;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.example.interviewreader.persistence.mapper.ReadingProgressMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.UUID;

import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ReadingProgressEntityTableDef.READING_PROGRESS_ENTITY;

@Service
@RequiredArgsConstructor
public class DocumentPublishingService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ReadingProgressMapper readingProgressMapper;

    @Transactional
    public void publish(UUID documentId, UUID versionId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .where(com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY.ID.eq(documentId.toString()))
                .and(com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .forUpdate());
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found");
        }
        DocumentLifecycleService.rejectLocked(document);

        var version = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.ID.eq(versionId.toString()))
                .and(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId.toString())));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Version not found");
        }
        if (version.getStatus() != DocumentVersionStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a draft version can be published");
        }

        var previousVersionId = previousPublishedVersionId(documentId, versionId);
        var now = OffsetDateTime.now();
        var retire = UpdateWrapper.of(DocumentVersionEntity.class)
                .set(DOCUMENT_VERSION_ENTITY.STATUS, DocumentVersionStatus.RETIRED);
        documentVersionMapper.updateByQuery(retire.toEntity(), false, QueryWrapper.create()
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId.toString()))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED))
                .and(DOCUMENT_VERSION_ENTITY.ID.ne(versionId.toString())));

        version.setStatus(DocumentVersionStatus.PUBLISHED);
        version.setPublishedAt(now);
        documentVersionMapper.update(version);

        document.setStatus(DocumentStatus.PUBLISHED);
        document.setCurrentVersionId(versionId.toString());
        document.setUpdatedAt(now);
        documentMapper.update(document);
        migrateReadingProgress(documentId, previousVersionId, versionId);
    }

    private UUID previousPublishedVersionId(UUID documentId, UUID nextVersionId) {
        return documentVersionMapper.selectListByQuery(QueryWrapper.create()
                        .select(
                                DOCUMENT_VERSION_ENTITY.ID,
                                DOCUMENT_VERSION_ENTITY.VERSION_NO,
                                DOCUMENT_VERSION_ENTITY.PUBLISHED_AT)
                        .from(DOCUMENT_VERSION_ENTITY)
                        .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId.toString()))
                        .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED)))
                .stream()
                .filter(version -> !version.getId().equals(nextVersionId.toString()))
                .max(Comparator
                        .comparing(DocumentVersionEntity::getPublishedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(DocumentVersionEntity::getVersionNo))
                .map(version -> UUID.fromString(version.getId()))
                .orElse(null);
    }

    private void migrateReadingProgress(UUID documentId, UUID previousVersionId, UUID nextVersionId) {
        if (previousVersionId == null || previousVersionId.equals(nextVersionId)) {
            return;
        }
        var rows = readingProgressMapper.selectListByQuery(QueryWrapper.create()
                .select(READING_PROGRESS_ENTITY.ID, READING_PROGRESS_ENTITY.REVISION)
                .from(READING_PROGRESS_ENTITY)
                .where(READING_PROGRESS_ENTITY.USER_ID.eq(LOCAL_USER_ID))
                .and(READING_PROGRESS_ENTITY.DOCUMENT_ID.eq(documentId.toString())));
        for (var row : rows) {
            var update = UpdateWrapper.of(ReadingProgressEntity.class)
                    .set(READING_PROGRESS_ENTITY.VERSION_ID, nextVersionId.toString())
                    .set(READING_PROGRESS_ENTITY.SECTION_ID, null)
                    .set(READING_PROGRESS_ENTITY.BLOCK_ID, null)
                    .set(READING_PROGRESS_ENTITY.CHAR_OFFSET, 0)
                    .set(READING_PROGRESS_ENTITY.BLOCK_VIEWPORT_OFFSET, 0)
                    .set(READING_PROGRESS_ENTITY.PROGRESS_RATIO, BigDecimal.ZERO)
                    .set(READING_PROGRESS_ENTITY.REVISION, row.getRevision() + 1)
                    .set(READING_PROGRESS_ENTITY.UPDATED_AT, OffsetDateTime.now());
            readingProgressMapper.updateByQuery(
                    update.toEntity(),
                    false,
                    QueryWrapper.create().where(READING_PROGRESS_ENTITY.ID.eq(row.getId()))
            );
        }
    }
}
