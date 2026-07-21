package com.example.interviewreader.management;

import com.mybatisflex.annotation.EnumValue;

/** 文档永久删除阶段，枚举名与数据库及 API 中的稳定编码一致。 */
public enum DeletionStage {
    QUEUED("QUEUED"),
    CLIENT_SYNC_MARKED("CLIENT_SYNC_MARKED"),
    DELETING_FILES("DELETING_FILES"),
    DELETING_DATA("DELETING_DATA"),
    FAILED("FAILED"),
    COMPLETED("COMPLETED");

    private final String code;

    DeletionStage(String code) {
        this.code = code;
    }

    @EnumValue
    public String getCode() {
        return code;
    }
}
