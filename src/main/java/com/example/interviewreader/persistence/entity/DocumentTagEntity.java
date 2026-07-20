package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/** 文档与标签关联持久化实体。 */
@Getter
@Setter
@Table("document_tag")
public class DocumentTagEntity {
    @Id(keyType = KeyType.None)
    private String documentId;
    @Id(keyType = KeyType.None)
    private String tagId;
}
