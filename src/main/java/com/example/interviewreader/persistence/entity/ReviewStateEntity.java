package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("review_state")
public class ReviewStateEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String userId;
    public String documentId;
    public String nodeId;
    public String mastery;
    public OffsetDateTime dueAt;
    public Integer intervalDays;
    public int repetitions;
    public OffsetDateTime updatedAt;
}
