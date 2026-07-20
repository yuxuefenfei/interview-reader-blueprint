package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 阅读进度持久化实体。 */
@Getter
@Setter
@Table("reading_progress")
public class ReadingProgressEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String userId;
    private String documentId;
    private String versionId;
    private String sectionId;
    private String blockId;
    private int charOffset;
    private int blockViewportOffset;
    private BigDecimal progressRatio;
    private OffsetDateTime clientUpdatedAt;
    private String deviceId;
    private long revision;
    private OffsetDateTime updatedAt;
}
