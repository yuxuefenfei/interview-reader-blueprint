package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("import_issue")
public class ImportIssueEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String jobId;
    public String severity;
    public String issueCode;
    @Column(isLarge = true)
    public String message;
    public Integer sourcePage;
    public String sectionKey;
    public String blockKey;
    public String cellRef;
    @Column(isLarge = true)
    public String details;
    public boolean resolved;
    public OffsetDateTime createdAt;
}
