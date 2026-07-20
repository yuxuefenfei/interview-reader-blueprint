package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.importpkg.ImportJobWorker;
import com.example.interviewreader.importpkg.SourceFileStorage;
import com.example.interviewreader.persistence.entity.DocumentDeletionJobEntity;
import com.example.interviewreader.persistence.mapper.DocumentDeletionJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentDeletionProcessor {
    private final DocumentDeletionJobMapper jobMapper;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SourceFileStorage storage;
    private final ImportJobWorker importWorker;
    private final DocumentDeletionProperties properties;

    public void process(UUID jobId) {
        while (true) {
            try {
                var context = startAttempt(jobId);
                if (context == null) return;
                context.importJobIds().forEach(id -> importWorker.cancel(UUID.fromString(id)));
                updateStage(jobId, "DELETING_FILES");
                deleteManagedFiles(context);
                updateStage(jobId, "DELETING_DATA");
                deleteDatabaseData(context);
                return;
            } catch (RuntimeException exception) {
                var attempts = failAttempt(jobId, exception);
                log.error("Permanent document deletion failed for job {} on attempt {}", jobId, attempts, exception);
                if (attempts >= properties.maxAttempts()) return;
                try {
                    Thread.sleep(properties.retryDelay().multipliedBy(attempts).toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private DeletionContext startAttempt(UUID jobId) {
        return transactions.execute(status -> {
            var job = jobMapper.selectOneById(jobId.toString());
            if (job == null || "COMPLETED".equals(job.getStatus())) return null;
            var now = OffsetDateTime.now();
            job.setStatus("RUNNING");
            job.setCurrentStage("CLIENT_SYNC_MARKED");
            job.setAttemptCount(job.getAttemptCount() + 1);
            if (job.getStartedAt() == null) job.setStartedAt(now);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setUpdatedAt(now);
            jobMapper.update(job);
            jdbc.update("UPDATE document SET status = 'DELETING', updated_at = :now WHERE id = :id",
                    Map.of("id", job.getDocumentId(), "now", now));
            return collectContext(job);
        });
    }

    private DeletionContext collectContext(DocumentDeletionJobEntity job) {
        var parameters = Map.<String, Object>of("documentId", job.getDocumentId());
        var versions = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT id FROM document_version WHERE document_id = :documentId", parameters, String.class));
        var importJobs = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT id FROM import_job WHERE target_document_id = :documentId", parameters, String.class));
        if (!versions.isEmpty()) {
            var byVersion = Map.<String, Object>of("versionIds", versions);
            importJobs.addAll(jdbc.queryForList(
                    "SELECT id FROM import_job WHERE result_version_id IN (:versionIds)", byVersion, String.class));
            importJobs.addAll(jdbc.queryForList(
                    "SELECT DISTINCT origin_import_job_id FROM document_version WHERE id IN (:versionIds) AND origin_import_job_id IS NOT NULL",
                    byVersion, String.class));
        }
        var tagIds = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT tag_id FROM document_tag WHERE document_id = :documentId", parameters, String.class));
        return new DeletionContext(job.getId(), job.getDocumentId(), job.getOwnerId(), versions, importJobs, tagIds);
    }

    private void deleteManagedFiles(DeletionContext context) {
        var keys = new LinkedHashSet<String>();
        if (!context.importJobIds().isEmpty()) {
            var rows = jdbc.queryForList("SELECT source_object_key, raw_extraction_object_key, normalized_object_key " +
                    "FROM import_job WHERE id IN (:jobIds)", Map.of("jobIds", context.importJobIds()));
            for (var row : rows) {
                addKey(keys, row.get("source_object_key"));
                addKey(keys, row.get("raw_extraction_object_key"));
                var normalized = row.get("normalized_object_key");
                if (normalized instanceof String value && !value.stripLeading().startsWith("{") && !value.stripLeading().startsWith("[")) {
                    addKey(keys, value);
                }
            }
        }
        if (!context.versionIds().isEmpty()) {
            keys.addAll(jdbc.queryForList("SELECT object_key FROM asset WHERE version_id IN (:versionIds)",
                    Map.of("versionIds", context.versionIds()), String.class));
        }
        for (var key : keys) {
            if (!isReferencedOutside(context, key)) storage.deleteIfManaged(key);
        }
    }

    private boolean isReferencedOutside(DeletionContext context, String key) {
        var jobSql = "SELECT COUNT(*) FROM import_job WHERE (source_object_key = :key OR raw_extraction_object_key = :key OR normalized_object_key = :key)";
        var jobParams = new java.util.HashMap<String, Object>();
        jobParams.put("key", key);
        if (!context.importJobIds().isEmpty()) {
            jobSql += " AND id NOT IN (:jobIds)";
            jobParams.put("jobIds", context.importJobIds());
        }
        var externalJobs = jdbc.queryForObject(jobSql, jobParams, Long.class);
        if (externalJobs != null && externalJobs > 0) return true;
        var assetSql = "SELECT COUNT(*) FROM asset a JOIN document_version v ON v.id = a.version_id WHERE a.object_key = :key";
        var assetParams = new java.util.HashMap<String, Object>();
        assetParams.put("key", key);
        if (!context.versionIds().isEmpty()) {
            assetSql += " AND a.version_id NOT IN (:versionIds)";
            assetParams.put("versionIds", context.versionIds());
        }
        var externalAssets = jdbc.queryForObject(assetSql, assetParams, Long.class);
        return externalAssets != null && externalAssets > 0;
    }

    private static void addKey(Set<String> keys, Object value) {
        if (value instanceof String key && !key.isBlank()) keys.add(key);
    }

    private void deleteDatabaseData(DeletionContext context) {
        transactions.executeWithoutResult(status -> {
            var document = Map.<String, Object>of("documentId", context.documentId());
            jdbc.update("DELETE FROM review_state WHERE document_id = :documentId", document);
            jdbc.update("DELETE FROM bookmark WHERE document_id = :documentId", document);
            jdbc.update("DELETE FROM note WHERE document_id = :documentId", document);
            jdbc.update("DELETE FROM reading_progress WHERE document_id = :documentId", document);
            jdbc.update("UPDATE document SET current_version_id = NULL WHERE id = :documentId", document);
            jdbc.update("UPDATE document_version SET parent_version_id = NULL, origin_import_job_id = NULL WHERE document_id = :documentId", document);
            if (!context.importJobIds().isEmpty()) {
                var jobs = Map.<String, Object>of("jobIds", context.importJobIds());
                jdbc.update("UPDATE import_job SET result_version_id = NULL, target_document_id = NULL WHERE id IN (:jobIds)", jobs);
                jdbc.update("DELETE FROM import_issue WHERE job_id IN (:jobIds)", jobs);
                jdbc.update("DELETE FROM import_job WHERE id IN (:jobIds)", jobs);
            }
            jdbc.update("DELETE FROM document WHERE id = :documentId", document);
            if (!context.tagIds().isEmpty()) {
                jdbc.update("DELETE FROM tag WHERE owner_id = :ownerId AND id IN (:tagIds) " +
                                "AND id NOT IN (SELECT DISTINCT tag_id FROM document_tag)",
                        Map.of("ownerId", context.ownerId(), "tagIds", context.tagIds()));
            }
            var job = jobMapper.selectOneById(context.jobId());
            var now = OffsetDateTime.now();
            job.setStatus("COMPLETED");
            job.setCurrentStage("COMPLETED");
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            jobMapper.update(job);
        });
    }

    private void updateStage(UUID jobId, String stage) {
        transactions.executeWithoutResult(status -> {
            var job = jobMapper.selectOneById(jobId.toString());
            if (job == null || "COMPLETED".equals(job.getStatus())) return;
            job.setCurrentStage(stage);
            job.setUpdatedAt(OffsetDateTime.now());
            jobMapper.update(job);
        });
    }

    private int failAttempt(UUID jobId, RuntimeException exception) {
        var attempts = transactions.execute(status -> {
            var job = jobMapper.selectOneById(jobId.toString());
            if (job == null) return properties.maxAttempts();
            var finalFailure = job.getAttemptCount() >= properties.maxAttempts();
            job.setStatus(finalFailure ? "FAILED" : "QUEUED");
            job.setCurrentStage(finalFailure ? "FAILED" : "QUEUED");
            job.setErrorCode(exception.getClass().getSimpleName());
            job.setErrorMessage(safeMessage(exception));
            job.setUpdatedAt(OffsetDateTime.now());
            jobMapper.update(job);
            if (finalFailure) {
                jdbc.update("UPDATE document SET status = 'DELETE_FAILED', updated_at = :now WHERE id = :id",
                        Map.of("id", job.getDocumentId(), "now", job.getUpdatedAt()));
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

    private record DeletionContext(String jobId, String documentId, String ownerId, Set<String> versionIds,
                                   Set<String> importJobIds, Set<String> tagIds) {}
}
