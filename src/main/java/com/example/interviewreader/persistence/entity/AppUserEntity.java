package com.example.interviewreader.persistence.entity;
import lombok.Getter;
import lombok.Setter;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

/** 应用用户持久化实体。 */
@Getter
@Setter
@Table("app_user")
public class AppUserEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String username;
    private String displayName;
    private OffsetDateTime createdAt;
}
