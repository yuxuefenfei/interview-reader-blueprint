package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.document.DocumentStatus;
import com.example.interviewreader.importpkg.ImportJobWorker;
import com.example.interviewreader.importpkg.SourceFileStorage;
import com.example.interviewreader.persistence.DocumentDeletionPersistence;
import com.example.interviewreader.persistence.DocumentDeletionPersistence.DeletionReferences;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentDeletionProcessor {
    private final DocumentDeletionPersistence persistence;
    private final TransactionTemplate transactions;
    private final SourceFileStorage storage;
    private final ImportJobWorker importWorker;
    private final DocumentDeletionProperties properties;

    @SuppressWarnings("BusyWait")
    public void process(UUID jobId) {
        while (true) {
            try {
                var context = startAttempt(jobId);
                if (context == null) {
                    return;
                }
                context.importJobIds().forEach(id -> importWorker.cancel(UUID.fromString(id)));
                updateStage(jobId, DeletionStage.DELETING_FILES);
                deleteManagedFiles(context);
                updateStage(jobId, DeletionStage.DELETING_DATA);
                deleteDatabaseData(context);
                return;
            } catch (RuntimeException exception) {
                var attempts = failAttempt(jobId, exception);
                log.error("Permanent document deletion failed for job {} on attempt {}", jobId, attempts, exception);
                if (attempts >= properties.maxAttempts()) {
                    return;
                }
                try {
                    Thread.sleep(properties.retryDelay().multipliedBy(attempts).toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private DeletionReferences startAttempt(UUID jobId) {
        return transactions.execute(status -> {
            var job = persistence.findJob(jobId.toString());
            if (job == null || job.getStatus() == DeletionJobStatus.COMPLETED) {
                return null;
            }
            var now = OffsetDateTime.now();
            job.setStatus(DeletionJobStatus.RUNNING);
            job.setCurrentStage(DeletionStage.CLIENT_SYNC_MARKED);
            job.setAttemptCount(job.getAttemptCount() + 1);
            if (job.getStartedAt() == null) {
                job.setStartedAt(now);
            }
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setUpdatedAt(now);
            persistence.updateJob(job);
            persistence.updateDocumentStatus(job.getDocumentId(), DocumentStatus.DELETING, now);
            return persistence.collectReferences(job.getId(), job.getDocumentId(), job.getOwnerId());
        });
    }

    private void deleteManagedFiles(DeletionReferences context) {
        for (var key : persistence.findManagedObjectKeys(context)) {
            if (!persistence.isObjectKeyReferencedOutside(context, key)) {
                storage.deleteIfManaged(key);
            }
        }
    }

    private void deleteDatabaseData(DeletionReferences context) {
        transactions.executeWithoutResult(status -> {
            persistence.deleteDocumentData(context);
            var job = persistence.findJob(context.jobId());
            var now = OffsetDateTime.now();
            job.setStatus(DeletionJobStatus.COMPLETED);
            job.setCurrentStage(DeletionStage.COMPLETED);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            persistence.updateJob(job);
        });
    }

    private void updateStage(UUID jobId, DeletionStage stage) {
        transactions.executeWithoutResult(status -> {
            var job = persistence.findJob(jobId.toString());
            if (job == null || job.getStatus() == DeletionJobStatus.COMPLETED) {
                return;
            }
            job.setCurrentStage(stage);
            job.setUpdatedAt(OffsetDateTime.now());
            persistence.updateJob(job);
        });
    }

    private int failAttempt(UUID jobId, RuntimeException exception) {
        var attempts = transactions.execute(status -> {
            var job = persistence.findJob(jobId.toString());
            if (job == null) {
                return properties.maxAttempts();
            }
            var finalFailure = job.getAttemptCount() >= properties.maxAttempts();
            var nextStatus = finalFailure ? DeletionJobStatus.FAILED : DeletionJobStatus.QUEUED;
            var nextStage = finalFailure ? DeletionStage.FAILED : DeletionStage.QUEUED;
            job.setStatus(nextStatus);
            job.setCurrentStage(nextStage);
            job.setErrorCode(exception.getClass().getSimpleName());
            job.setErrorMessage(safeMessage(exception));
            job.setUpdatedAt(OffsetDateTime.now());
            persistence.updateJob(job);
            if (finalFailure) {
                persistence.updateDocumentStatus(
                        job.getDocumentId(), DocumentStatus.DELETE_FAILED, job.getUpdatedAt());
            }
            return job.getAttemptCount();
        });
        return attempts == null ? properties.maxAttempts() : attempts;
    }

    private static String safeMessage(RuntimeException exception) {
        if (exception instanceof ApiException) {
            var message = exception.getMessage();
            return message == null || message.isBlank() ? "Deletion failed" : message;
        }
        return "Deletion failed; inspect server logs with the deletion job id.";
    }
}
