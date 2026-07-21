package com.example.interviewreader.persistence;

import com.example.interviewreader.document.DocumentStatus;
import com.example.interviewreader.management.DeletionJobStatus;
import com.example.interviewreader.persistence.entity.DocumentDeletionJobEntity;
import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.example.interviewreader.persistence.mapper.BookmarkMapper;
import com.example.interviewreader.persistence.mapper.DocumentDeletionJobMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentTagMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.example.interviewreader.persistence.mapper.ImportIssueMapper;
import com.example.interviewreader.persistence.mapper.ImportJobMapper;
import com.example.interviewreader.persistence.mapper.NoteMapper;
import com.example.interviewreader.persistence.mapper.ReadingProgressMapper;
import com.example.interviewreader.persistence.mapper.ReviewStateMapper;
import com.example.interviewreader.persistence.mapper.TagMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.example.interviewreader.persistence.entity.table.AssetEntityTableDef.ASSET_ENTITY;
import static com.example.interviewreader.persistence.entity.table.BookmarkEntityTableDef.BOOKMARK_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentDeletionJobEntityTableDef.DOCUMENT_DELETION_JOB_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentTagEntityTableDef.DOCUMENT_TAG_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ImportIssueEntityTableDef.IMPORT_ISSUE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ImportJobEntityTableDef.IMPORT_JOB_ENTITY;
import static com.example.interviewreader.persistence.entity.table.NoteEntityTableDef.NOTE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ReadingProgressEntityTableDef.READING_PROGRESS_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ReviewStateEntityTableDef.REVIEW_STATE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.TagEntityTableDef.TAG_ENTITY;

/**
 * 文档永久删除的持久化适配器。
 *
 * <p>删除链路涉及多个表、外部文件引用检查和行锁，集中在此处使用 MyBatis-Flex Wrapper，
 * 避免业务服务混用多种数据访问方式。</p>
 */
@Repository
@RequiredArgsConstructor
public class DocumentDeletionPersistence {
    private final DocumentDeletionJobMapper deletionJobMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ImportJobMapper importJobMapper;
    private final ImportIssueMapper importIssueMapper;
    private final AssetMapper assetMapper;
    private final DocumentTagMapper documentTagMapper;
    private final TagMapper tagMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final BookmarkMapper bookmarkMapper;
    private final NoteMapper noteMapper;
    private final ReadingProgressMapper readingProgressMapper;

    public DocumentDeletionJobEntity findJob(String jobId) {
        return deletionJobMapper.selectOneById(jobId);
    }

    public DocumentDeletionJobEntity findByDocument(String documentId, String ownerId) {
        return deletionJobMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_DELETION_JOB_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_DELETION_JOB_ENTITY)
                .where(DOCUMENT_DELETION_JOB_ENTITY.DOCUMENT_ID.eq(documentId))
                .and(DOCUMENT_DELETION_JOB_ENTITY.OWNER_ID.eq(ownerId)));
    }

    public DocumentDeletionJobEntity findOwnedJob(String jobId, String ownerId) {
        return deletionJobMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_DELETION_JOB_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_DELETION_JOB_ENTITY)
                .where(DOCUMENT_DELETION_JOB_ENTITY.ID.eq(jobId))
                .and(DOCUMENT_DELETION_JOB_ENTITY.OWNER_ID.eq(ownerId)));
    }

    public List<DocumentDeletionJobEntity> findRecoverableJobs() {
        return deletionJobMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_DELETION_JOB_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_DELETION_JOB_ENTITY)
                .where(DOCUMENT_DELETION_JOB_ENTITY.STATUS.in(
                        DeletionJobStatus.QUEUED, DeletionJobStatus.RUNNING))
                .orderBy(DOCUMENT_DELETION_JOB_ENTITY.REQUESTED_AT.asc()));
    }

    public List<DocumentDeletionJobEntity> findCompletedSince(String ownerId, OffsetDateTime cutoff, int limit) {
        return deletionJobMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_DELETION_JOB_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_DELETION_JOB_ENTITY)
                .where(DOCUMENT_DELETION_JOB_ENTITY.OWNER_ID.eq(ownerId))
                .and(DOCUMENT_DELETION_JOB_ENTITY.STATUS.eq(DeletionJobStatus.COMPLETED))
                .and(DOCUMENT_DELETION_JOB_ENTITY.COMPLETED_AT.ge(cutoff))
                .orderBy(DOCUMENT_DELETION_JOB_ENTITY.COMPLETED_AT.asc())
                .limit(limit));
    }

    public int deleteExpiredJobs(OffsetDateTime cutoff) {
        return deletionJobMapper.deleteByQuery(QueryWrapper.create()
                .where(DOCUMENT_DELETION_JOB_ENTITY.STATUS.eq(DeletionJobStatus.COMPLETED))
                .and(DOCUMENT_DELETION_JOB_ENTITY.COMPLETED_AT.lt(cutoff)));
    }

    public int insertJob(DocumentDeletionJobEntity job) {
        return deletionJobMapper.insertSelective(job);
    }

    public int updateJob(DocumentDeletionJobEntity job) {
        return deletionJobMapper.update(job);
    }

    public DocumentEntity lockOwnedDocument(String documentId, String ownerId) {
        return documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.eq(documentId))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(ownerId))
                .forUpdate());
    }

    public void updateDocumentStatus(String documentId, DocumentStatus status, OffsetDateTime updatedAt) {
        var update = UpdateWrapper.of(DocumentEntity.class)
                .set(DOCUMENT_ENTITY.STATUS, status)
                .set(DOCUMENT_ENTITY.UPDATED_AT, updatedAt);
        documentMapper.updateByQuery(update.toEntity(), false,
                QueryWrapper.create().where(DOCUMENT_ENTITY.ID.eq(documentId)));
    }

    public DeletionReferences collectReferences(String jobId, String documentId, String ownerId) {
        var versions = documentVersionMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.ID, DOCUMENT_VERSION_ENTITY.ORIGIN_IMPORT_JOB_ID)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId)));
        var versionIds = versions.stream()
                .map(version -> version.getId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var importJobIds = importJobMapper.selectListByQuery(QueryWrapper.create()
                        .select(IMPORT_JOB_ENTITY.ID)
                        .from(IMPORT_JOB_ENTITY)
                        .where(IMPORT_JOB_ENTITY.TARGET_DOCUMENT_ID.eq(documentId)))
                .stream()
                .map(job -> job.getId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!versionIds.isEmpty()) {
            importJobMapper.selectListByQuery(QueryWrapper.create()
                            .select(IMPORT_JOB_ENTITY.ID)
                            .from(IMPORT_JOB_ENTITY)
                            .where(IMPORT_JOB_ENTITY.RESULT_VERSION_ID.in(versionIds)))
                    .stream()
                    .map(job -> job.getId())
                    .forEach(importJobIds::add);
            versions.stream()
                    .map(version -> version.getOriginImportJobId())
                    .filter(java.util.Objects::nonNull)
                    .forEach(importJobIds::add);
        }
        var tagIds = documentTagMapper.selectListByQuery(QueryWrapper.create()
                        .select(DOCUMENT_TAG_ENTITY.TAG_ID)
                        .from(DOCUMENT_TAG_ENTITY)
                        .where(DOCUMENT_TAG_ENTITY.DOCUMENT_ID.eq(documentId)))
                .stream()
                .map(link -> link.getTagId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new DeletionReferences(jobId, documentId, ownerId, versionIds, importJobIds, tagIds);
    }

    public Set<String> findManagedObjectKeys(DeletionReferences references) {
        var keys = new LinkedHashSet<String>();
        if (!references.importJobIds().isEmpty()) {
            importJobMapper.selectListByQuery(QueryWrapper.create()
                            .select(IMPORT_JOB_ENTITY.ALL_COLUMNS)
                            .from(IMPORT_JOB_ENTITY)
                            .where(IMPORT_JOB_ENTITY.ID.in(references.importJobIds())))
                    .forEach(job -> {
                        addKey(keys, job.getSourceObjectKey());
                        addKey(keys, job.getRawExtractionObjectKey());
                        var normalized = job.getNormalizedObjectKey();
                        if (normalized != null
                                && !normalized.stripLeading().startsWith("{")
                                && !normalized.stripLeading().startsWith("[")) {
                            addKey(keys, normalized);
                        }
                    });
        }
        if (!references.versionIds().isEmpty()) {
            assetMapper.selectListByQuery(QueryWrapper.create()
                            .select(ASSET_ENTITY.OBJECT_KEY)
                            .from(ASSET_ENTITY)
                            .where(ASSET_ENTITY.VERSION_ID.in(references.versionIds())))
                    .stream()
                    .map(asset -> asset.getObjectKey())
                    .filter(java.util.Objects::nonNull)
                    .forEach(keys::add);
        }
        return keys;
    }

    public boolean isObjectKeyReferencedOutside(DeletionReferences references, String objectKey) {
        var jobQuery = QueryWrapper.create()
                .from(IMPORT_JOB_ENTITY)
                .where(IMPORT_JOB_ENTITY.SOURCE_OBJECT_KEY.eq(objectKey)
                        .or(IMPORT_JOB_ENTITY.RAW_EXTRACTION_OBJECT_KEY.eq(objectKey))
                        .or(IMPORT_JOB_ENTITY.NORMALIZED_OBJECT_KEY.eq(objectKey)));
        if (!references.importJobIds().isEmpty()) {
            jobQuery.and(IMPORT_JOB_ENTITY.ID.notIn(references.importJobIds()));
        }
        if (importJobMapper.selectCountByQuery(jobQuery) > 0) {
            return true;
        }

        var assetQuery = QueryWrapper.create()
                .from(ASSET_ENTITY)
                .where(ASSET_ENTITY.OBJECT_KEY.eq(objectKey));
        if (!references.versionIds().isEmpty()) {
            assetQuery.and(ASSET_ENTITY.VERSION_ID.notIn(references.versionIds()));
        }
        return assetMapper.selectCountByQuery(assetQuery) > 0;
    }

    public void deleteDocumentData(DeletionReferences references) {
        var documentId = references.documentId();
        reviewStateMapper.deleteByQuery(QueryWrapper.create()
                .where(REVIEW_STATE_ENTITY.DOCUMENT_ID.eq(documentId)));
        bookmarkMapper.deleteByQuery(QueryWrapper.create()
                .where(BOOKMARK_ENTITY.DOCUMENT_ID.eq(documentId)));
        noteMapper.deleteByQuery(QueryWrapper.create()
                .where(NOTE_ENTITY.DOCUMENT_ID.eq(documentId)));
        readingProgressMapper.deleteByQuery(QueryWrapper.create()
                .where(READING_PROGRESS_ENTITY.DOCUMENT_ID.eq(documentId)));

        var clearCurrentVersion = UpdateWrapper.of(DocumentEntity.class)
                .set(DOCUMENT_ENTITY.CURRENT_VERSION_ID, null);
        documentMapper.updateByQuery(clearCurrentVersion.toEntity(), false,
                QueryWrapper.create().where(DOCUMENT_ENTITY.ID.eq(documentId)));

        var clearVersionLinks = UpdateWrapper.of(com.example.interviewreader.persistence.entity.DocumentVersionEntity.class)
                .set(DOCUMENT_VERSION_ENTITY.PARENT_VERSION_ID, null)
                .set(DOCUMENT_VERSION_ENTITY.ORIGIN_IMPORT_JOB_ID, null);
        documentVersionMapper.updateByQuery(clearVersionLinks.toEntity(), false,
                QueryWrapper.create().where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId)));

        if (!references.importJobIds().isEmpty()) {
            var clearImportLinks = UpdateWrapper.of(com.example.interviewreader.persistence.entity.ImportJobEntity.class)
                    .set(IMPORT_JOB_ENTITY.RESULT_VERSION_ID, null)
                    .set(IMPORT_JOB_ENTITY.TARGET_DOCUMENT_ID, null);
            importJobMapper.updateByQuery(clearImportLinks.toEntity(), false,
                    QueryWrapper.create().where(IMPORT_JOB_ENTITY.ID.in(references.importJobIds())));
            importIssueMapper.deleteByQuery(QueryWrapper.create()
                    .where(IMPORT_ISSUE_ENTITY.JOB_ID.in(references.importJobIds())));
            importJobMapper.deleteByQuery(QueryWrapper.create()
                    .where(IMPORT_JOB_ENTITY.ID.in(references.importJobIds())));
        }

        documentMapper.deleteById(documentId);
        if (!references.tagIds().isEmpty()) {
            var tagsStillInUse = QueryWrapper.create()
                    .select(DOCUMENT_TAG_ENTITY.TAG_ID)
                    .from(DOCUMENT_TAG_ENTITY);
            tagMapper.deleteByQuery(QueryWrapper.create()
                    .where(TAG_ENTITY.OWNER_ID.eq(references.ownerId()))
                    .and(TAG_ENTITY.ID.in(references.tagIds()))
                    .and(TAG_ENTITY.ID.notIn(tagsStillInUse)));
        }
    }

    private static void addKey(Set<String> keys, String value) {
        if (value != null && !value.isBlank()) {
            keys.add(value);
        }
    }

    public record DeletionReferences(
            String jobId,
            String documentId,
            String ownerId,
            Set<String> versionIds,
            Set<String> importJobIds,
            Set<String> tagIds
    ) {
    }
}
