package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Table("content_block")
public class ContentBlockEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String versionId;
    public String nodeId;
    public String blockKey;
    public int seq;
    public String blockType;
    @Column(isLarge = true)
    public String payload;
    @Column(isLarge = true)
    public String plainText;
    public String language;
    public Integer sourcePage;
    @Column(isLarge = true)
    public String sourceBbox;
    public BigDecimal confidence;
    public String contentHash;
    public OffsetDateTime createdAt;
}
