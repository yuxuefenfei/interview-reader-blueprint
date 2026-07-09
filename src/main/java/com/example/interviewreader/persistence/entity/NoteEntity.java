package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("note")
public class NoteEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String userId;
    public String documentId;
    public String versionId;
    public String sectionId;
    public String blockId;
    @Column(isLarge = true)
    public String selectedText;
    @Column(isLarge = true)
    public String body;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
