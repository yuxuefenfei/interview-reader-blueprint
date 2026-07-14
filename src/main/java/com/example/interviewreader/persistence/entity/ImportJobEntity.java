package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("import_job")
public class ImportJobEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String ownerId;
    public String targetDocumentId;
    public String sourceType;
    public String sourceObjectKey;
    public String sourceSha256;
    public String converterVersion;
    public String importFingerprint;
    public String status;
    public int progress;
    public String currentStage;
    public String resultVersionId;
    public String errorCode;
    @Column(isLarge = true)
    public String errorMessage;
    @Column(isLarge = true)
    public String statistics;
    public String rawExtractionObjectKey;
    @Column(isLarge = true)
    public String rawExtractionJson;
    @Column(isLarge = true)
    public String normalizedObjectKey;
    public OffsetDateTime createdAt;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
}
