package com.example.interviewreader.document;

import com.mybatisflex.annotation.EnumValue;

/** 文档生命周期状态，枚举名与数据库及 API 中的稳定编码一致。 */
public enum DocumentStatus {
    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    OFFLINE("OFFLINE"),
    DELETING("DELETING"),
    DELETE_FAILED("DELETE_FAILED");

    private final String code;

    DocumentStatus(String code) {
        this.code = code;
    }

    @EnumValue
    public String getCode() {
        return code;
    }

    public static boolean isDeletionLocked(DocumentStatus value) {
        return value == DELETING || value == DELETE_FAILED;
    }
}
