package com.example.interviewreader.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.OffsetDateTime;

@Table("app_user")
public class AppUserEntity {
    @Id(keyType = KeyType.None)
    public String id;
    public String username;
    public String displayName;
    public OffsetDateTime createdAt;
}
