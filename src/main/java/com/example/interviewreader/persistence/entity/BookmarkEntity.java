package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("bookmark")
public class BookmarkEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String userId;
    public String documentId;
    public String versionId;
    public String sectionId;
    public String blockId;
    public String title;
    public OffsetDateTime createdAt;
}
