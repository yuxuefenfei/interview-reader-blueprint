package com.example.interviewreader.management;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;

/** 文档永久删除任务状态，枚举名与数据库及 API 中的稳定编码一致。 */
public enum DeletionJobStatus {
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    FAILED("FAILED"),
    COMPLETED("COMPLETED");

    private final String code;

    DeletionJobStatus(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static DeletionJobStatus fromCode(String value) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown deletion job status: " + value));
    }
}
