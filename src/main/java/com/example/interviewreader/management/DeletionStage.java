package com.example.interviewreader.management;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;

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

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static DeletionStage fromCode(String value) {
        return Arrays.stream(values())
                .filter(stage -> stage.code.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown deletion stage: " + value));
    }
}
