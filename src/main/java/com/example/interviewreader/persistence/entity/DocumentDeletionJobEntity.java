package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("document_deletion_job")
public class DocumentDeletionJobEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String documentId;
    public String ownerId;
    public String status;
    public String currentStage;
    public int attemptCount;
    public String errorCode;
    @Column(isLarge = true)
    public String errorMessage;
    public OffsetDateTime requestedAt;
    public OffsetDateTime startedAt;
    public OffsetDateTime completedAt;
    public OffsetDateTime updatedAt;
}