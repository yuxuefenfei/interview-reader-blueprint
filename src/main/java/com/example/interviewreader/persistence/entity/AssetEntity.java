package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("asset")
public class AssetEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String versionId;
    public String assetKey;
    public String objectKey;
    public String originalName;
    public String mimeType;
    public String sha256;
    public long sizeBytes;
    public Integer widthPx;
    public Integer heightPx;
    @Column(isLarge = true)
    public String metadata;
    public OffsetDateTime createdAt;
}
