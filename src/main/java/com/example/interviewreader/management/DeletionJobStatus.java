package com.example.interviewreader.management;

import com.mybatisflex.annotation.EnumValue;

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

    @EnumValue
    public String getCode() {
        return code;
    }
}
