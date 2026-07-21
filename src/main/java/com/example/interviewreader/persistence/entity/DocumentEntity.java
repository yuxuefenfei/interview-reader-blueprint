package com.example.interviewreader.persistence.entity;

import com.example.interviewreader.document.DocumentStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

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
    /** 文档级资料的乐观锁版本，不随内容版本编辑而变化。 */
    private long metadataRevision;
    private DocumentStatus status;
    private String currentVersionId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
