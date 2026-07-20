package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 文档资源文件持久化实体。 */
@Getter
@Setter
@Table("asset")
public class AssetEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String versionId;
    private String assetKey;
    private String objectKey;
    private String originalName;
    private String mimeType;
    private String sha256;
    private long sizeBytes;
    private Integer widthPx;
    private Integer heightPx;
    @Column(isLarge = true)
    private String metadata;
    private OffsetDateTime createdAt;
}
