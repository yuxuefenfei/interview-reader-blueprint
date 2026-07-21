package com.example.interviewreader.persistence.entity;
import com.example.interviewreader.document.MasteryState;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 复习状态持久化实体。 */
@Getter
@Setter
@Table("review_state")
public class ReviewStateEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String userId;
    private String documentId;
    private String nodeId;
    private MasteryState mastery;
    private OffsetDateTime dueAt;
    private Integer intervalDays;
    private int repetitions;
    private OffsetDateTime updatedAt;
}
