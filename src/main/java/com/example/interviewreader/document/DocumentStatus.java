package com.example.interviewreader.document;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;

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

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    public static boolean isDeletionLocked(DocumentStatus value) {
        return value == DELETING || value == DELETE_FAILED;
    }

    @JsonCreator
    public static DocumentStatus fromCode(String value) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown document status: " + value));
    }
}
