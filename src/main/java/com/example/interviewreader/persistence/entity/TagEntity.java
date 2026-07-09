package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

@Table("tag")
public class TagEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String ownerId;
    public String name;
    public String normalizedName;
}
