package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Table("reading_progress")
public class ReadingProgressEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String userId;
    public String documentId;
    public String versionId;
    public String sectionId;
    public String blockId;
    public int charOffset;
    public int blockViewportOffset;
    public BigDecimal progressRatio;
    public OffsetDateTime clientUpdatedAt;
    public String deviceId;
    public long revision;
    public OffsetDateTime updatedAt;
}
