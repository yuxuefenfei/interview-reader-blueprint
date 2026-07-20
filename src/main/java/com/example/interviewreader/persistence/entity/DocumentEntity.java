package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 文档聚合根持久化实体。 */
@Getter
@Setter
@Table("document")
public class DocumentEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String ownerId;
    private String code;
    private String title;
    @Column(isLarge = true)
    private String description;
    private String status;
    private String currentVersionId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
