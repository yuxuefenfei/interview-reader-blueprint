package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("document_version")
public class DocumentVersionEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String documentId;
    public int versionNo;
    public String sourceType;
    public String sourceFileName;
    public String sourceFileSha256;
    public String converterVersion;
    public String schemaVersion;
    public String status;
    public String language;
    @Column(isLarge = true)
    public String metadata;
    public OffsetDateTime publishedAt;
    public OffsetDateTime createdAt;
}
