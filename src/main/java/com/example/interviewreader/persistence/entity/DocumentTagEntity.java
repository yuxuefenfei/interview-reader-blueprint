package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

@Table("document_tag")
public class DocumentTagEntity {
    @Id(keyType = KeyType.None)
    public String documentId;
    @Id(keyType = KeyType.None)
    public String tagId;
}
