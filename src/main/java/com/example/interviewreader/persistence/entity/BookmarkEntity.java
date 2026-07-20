package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 阅读书签持久化实体。 */
@Getter
@Setter
@Table("bookmark")
public class BookmarkEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String userId;
    private String documentId;
    private String versionId;
    private String sectionId;
    private String blockId;
    private String title;
    private OffsetDateTime createdAt;
}
