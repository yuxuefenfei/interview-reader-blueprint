package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 文档内容块持久化实体。 */
@Getter
@Setter
@Table("content_block")
public class ContentBlockEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String versionId;
    private String nodeId;
    private String blockKey;
    private int seq;
    private String blockType;
    @Column(isLarge = true)
    private String payload;
    @Column(isLarge = true)
    private String plainText;
    private String language;
    private Integer sourcePage;
    @Column(isLarge = true)
    private String sourceBbox;
    private BigDecimal confidence;
    private String contentHash;
    private OffsetDateTime createdAt;
}
