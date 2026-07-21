package com.example.interviewreader.persistence.entity;
import com.example.interviewreader.management.DeletionJobStatus;
import com.example.interviewreader.management.DeletionStage;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 文档彻底删除任务持久化实体。 */
@Getter
@Setter
@Table("document_deletion_job")
public class DocumentDeletionJobEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String documentId;
    private String ownerId;
    private DeletionJobStatus status;
    private DeletionStage currentStage;
    private int attemptCount;
    private String errorCode;
    @Column(isLarge = true)
    private String errorMessage;
    private OffsetDateTime requestedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime updatedAt;
}
