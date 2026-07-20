package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 文档版本持久化实体。 */
@Getter
@Setter
@Table("document_version")
public class DocumentVersionEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String documentId;
    private int versionNo;
    private String parentVersionId;
    private Integer parentVersionNo;
    private String originImportJobId;
    private long draftRevision;
    private String sourceType;
    private String sourceFileName;
    private String sourceFileSha256;
    private String converterVersion;
    private String schemaVersion;
    private String status;
    private String language;
    @Column(isLarge = true)
    private String metadata;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
}
