package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/** 文档标签持久化实体。 */
@Getter
@Setter
@Table("tag")
public class TagEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String ownerId;
    private String name;
    private String normalizedName;
}
