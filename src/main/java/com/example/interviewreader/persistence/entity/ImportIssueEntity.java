package com.example.interviewreader.persistence.entity;
import com.example.interviewreader.importpkg.ImportIssueSeverity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 文档导入问题持久化实体。 */
@Getter
@Setter
@Table("import_issue")
public class ImportIssueEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String jobId;
    private ImportIssueSeverity severity;
    private String issueCode;
    @Column(isLarge = true)
    private String message;
    private Integer sourcePage;
    private String sectionKey;
    private String blockKey;
    private String cellRef;
    @Column(isLarge = true)
    private String details;
    private boolean resolved;
    private OffsetDateTime createdAt;
}
