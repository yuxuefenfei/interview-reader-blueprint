package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.persistence.entity.DocumentDeletionJobEntity;
import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.example.interviewreader.persistence.mapper.DocumentDeletionJobMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;

@Service
@RequiredArgsConstructor
public class DocumentLifecycleService {
    private static final String OWNER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final DocumentDeletionJobMapper deletionJobMapper;
    private final DocumentDeletionWorker deletionWorker;

    @Transactional
    public void takeDown(UUID documentId) {
        var document = requireDocument(documentId);
        rejectLocked(document);
        if ("OFFLINE".equals(document.getStatus())) {
            return;
        }
        if (!"PUBLISHED".equals(document.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_PUBLISHED", "Document is not published");
        }
        updateStatus(document, "PUBLISHED", "OFFLINE");
    }

    @Transactional
    public void restore(UUID documentId) {
        var document = requireDocument(documentId);
        rejectLocked(document);
        if ("PUBLISHED".equals(document.getStatus())) {
            return;
        }
        if (!"OFFLINE".equals(document.getStatus()) || document.getCurrentVersionId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_CANNOT_RESTORE", "Offline document has no published version to restore");
        }
        var version = versionMapper.selectOneById(document.getCurrentVersionId());
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_CANNOT_RESTORE", "Published version is missing");
        }
        updateStatus(document, "OFFLINE", "PUBLISHED");
    }

    @Transactional
    public ManagementDtos.DeletionJobSummary requestDeletion(UUID documentId, ManagementDtos.DeleteDocumentRequest request) {
        var existing = deletionJobMapper.selectByDocument(documentId.toString(), OWNER_ID);
        if (existing != null) {
            return summary(existing);
        }
        var document = documentMapper.selectOwnedForUpdate(documentId.toString(), OWNER_ID);
        if (document == null) {
            existing = deletionJobMapper.selectByDocument(documentId.toString(), OWNER_ID);
            if (existing != null) return summary(existing);
            throw new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document not found");
        }
        existing = deletionJobMapper.selectByDocument(documentId.toString(), OWNER_ID);
        if (existing != null) return summary(existing);
        if ("PUBLISHED".equals(document.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_MUST_BE_OFFLINE", "Take the document down before permanent deletion");
        }
        rejectLocked(document);
        if (!("DRAFT".equals(document.getStatus()) || "OFFLINE".equals(document.getStatus()))) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_CANNOT_DELETE", "Document cannot be permanently deleted in its current state");
        }
        if (request == null || request.confirmationTitle() == null || !document.getTitle().equals(request.confirmationTitle())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TITLE_MISMATCH", "Confirmation title must exactly match the full document title");
        }
        var now = OffsetDateTime.now();
        var job = new DocumentDeletionJobEntity();
        job.setId(UUID.randomUUID().toString());
        job.setDocumentId(document.getId());
        job.setOwnerId(OWNER_ID);
        job.setStatus("QUEUED");
        job.setCurrentStage("QUEUED");
        job.setAttemptCount(0);
        job.setRequestedAt(now);
        job.setUpdatedAt(now);
        deletionJobMapper.insert(job);
        updateStatus(document, document.getStatus(), "DELETING");
        submitAfterCommit(UUID.fromString(job.getId()));
        return summary(job);
    }

    public ManagementDtos.DeletionJobSummary job(UUID jobId) {
        var job = requireJob(jobId);
        return summary(job);
    }

    @Transactional
    public ManagementDtos.DeletionJobSummary retry(UUID jobId) {
        var job = requireJob(jobId);
        if ("COMPLETED".equals(job.getStatus())) {
            return summary(job);
        }
        if (!"FAILED".equals(job.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DELETION_NOT_FAILED", "Only a failed deletion can be retried");
        }
        var document = documentMapper.selectOneById(job.getDocumentId());
        if (document == null) {
            throw new ApiException(HttpStatus.CONFLICT, "DELETION_DOCUMENT_MISSING", "Deletion target no longer exists");
        }
        job.setStatus("QUEUED");
        job.setCurrentStage("QUEUED");
        job.setAttemptCount(0);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setUpdatedAt(OffsetDateTime.now());
        deletionJobMapper.update(job);
        document.setStatus("DELETING");
        document.setUpdatedAt(job.getUpdatedAt());
        documentMapper.update(document);
        submitAfterCommit(jobId);
        return summary(job);
    }

    private void submitAfterCommit(UUID jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deletionWorker.submit(jobId); }
            });
        } else {
            deletionWorker.submit(jobId);
        }
    }

    private void updateStatus(DocumentEntity document, String expected, String target) {
        var now = OffsetDateTime.now();
        var update = UpdateWrapper.of(DocumentEntity.class)
                .set(DOCUMENT_ENTITY.STATUS, target)
                .set(DOCUMENT_ENTITY.UPDATED_AT, now);
        var changed = documentMapper.updateByQuery(update.toEntity(), false, QueryWrapper.create()
                .where(DOCUMENT_ENTITY.ID.eq(document.getId()))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(OWNER_ID))
                .and(DOCUMENT_ENTITY.STATUS.eq(expected)));
        if (changed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_STATE_CHANGED", "Document state changed; refresh and retry");
        }
        document.setStatus(target);
        document.setUpdatedAt(now);
    }

    private DocumentEntity requireDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .where(DOCUMENT_ENTITY.ID.eq(documentId.toString()))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(OWNER_ID)));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document not found");
        }
        return document;
    }

    private DocumentDeletionJobEntity requireJob(UUID jobId) {
        var job = deletionJobMapper.selectOwned(jobId.toString(), OWNER_ID);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DELETION_JOB_NOT_FOUND", "Deletion job not found");
        }
        return job;
    }

    public static void rejectLocked(DocumentEntity document) {
        if ("DELETING".equals(document.getStatus()) || "DELETE_FAILED".equals(document.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_DELETION_LOCKED", "Document is locked by permanent deletion");
        }
    }

    public static ManagementDtos.DeletionJobSummary summary(DocumentDeletionJobEntity job) {
        return new ManagementDtos.DeletionJobSummary(UUID.fromString(job.getId()), UUID.fromString(job.getDocumentId()),
                job.getStatus(), job.getCurrentStage(), job.getAttemptCount(), job.getErrorCode(), job.getErrorMessage(),
                job.getRequestedAt(), job.getStartedAt(), job.getCompletedAt(), job.getUpdatedAt());
    }
}
