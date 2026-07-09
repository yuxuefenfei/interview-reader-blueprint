package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("content_node")
public class ContentNodeEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String versionId;
    public String parentId;
    public String nodeKey;
    public String nodeType;
    public String semanticRole;
    public String title;
    public int level;
    public String path;
    public int sortOrder;
    public String anchor;
    public Integer sourcePageStart;
    public Integer sourcePageEnd;
    @Column(isLarge = true)
    public String sourceBbox;
    public String contentHash;
    @Column(isLarge = true)
    public String searchText;
    public OffsetDateTime createdAt;
}
