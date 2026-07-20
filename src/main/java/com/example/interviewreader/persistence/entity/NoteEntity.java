package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 阅读笔记持久化实体。 */
@Getter
@Setter
@Table("note")
public class NoteEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String userId;
    private String documentId;
    private String versionId;
    private String sectionId;
    private String blockId;
    @Column(isLarge = true)
    private String selectedText;
    @Column(isLarge = true)
    private String body;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
