package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("document")
public class DocumentEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String ownerId;
    public String code;
    public String title;
    @Column(isLarge = true)
    public String description;
    public String status;
    public String currentVersionId;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
