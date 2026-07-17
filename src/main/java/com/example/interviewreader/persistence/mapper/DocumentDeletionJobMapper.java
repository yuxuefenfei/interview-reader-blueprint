package com.example.interviewreader.persistence.mapper;

import com.example.interviewreader.persistence.entity.DocumentDeletionJobEntity;
import com.mybatisflex.core.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DocumentDeletionJobMapper extends BaseMapper<DocumentDeletionJobEntity> {
    String COLUMNS = "id, document_id AS documentId, owner_id AS ownerId, status, " +
            "current_stage AS currentStage, attempt_count AS attemptCount, error_code AS errorCode, " +
            "error_message AS errorMessage, requested_at AS requestedAt, started_at AS startedAt, " +
            "completed_at AS completedAt, updated_at AS updatedAt";

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job WHERE document_id = #{documentId} AND owner_id = #{ownerId}")
    DocumentDeletionJobEntity selectByDocument(@Param("documentId") String documentId, @Param("ownerId") String ownerId);

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job WHERE id = #{jobId} AND owner_id = #{ownerId}")
    DocumentDeletionJobEntity selectOwned(@Param("jobId") String jobId, @Param("ownerId") String ownerId);

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job WHERE status IN ('QUEUED', 'RUNNING') ORDER BY requested_at ASC")
    List<DocumentDeletionJobEntity> selectRecoverable();

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job WHERE owner_id = #{ownerId} AND status = 'COMPLETED' AND completed_at >= #{cutoff} ORDER BY completed_at ASC LIMIT #{limit}")
    List<DocumentDeletionJobEntity> selectCompletedSince(@Param("ownerId") String ownerId, @Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    @Delete("DELETE FROM document_deletion_job WHERE status = 'COMPLETED' AND completed_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}