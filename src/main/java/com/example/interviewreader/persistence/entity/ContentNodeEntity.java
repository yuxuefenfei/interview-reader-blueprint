package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 文档目录节点持久化实体。 */
@Getter
@Setter
@Table("content_node")
public class ContentNodeEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String versionId;
    private String parentId;
    private String nodeKey;
    private String nodeType;
    private String semanticRole;
    private String title;
    private int level;
    private String path;
    private int sortOrder;
    private String anchor;
    private Integer sourcePageStart;
    private Integer sourcePageEnd;
    @Column(isLarge = true)
    private String sourceBbox;
    private String contentHash;
    @Column(isLarge = true)
    private String searchText;
    private OffsetDateTime createdAt;
}
