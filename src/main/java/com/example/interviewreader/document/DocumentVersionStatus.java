package com.example.interviewreader.document;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 文档版本状态，枚举名与数据库及 API 中的稳定编码一致。 */
public enum DocumentVersionStatus {
    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    RETIRED("RETIRED");

    private final String code;

    DocumentVersionStatus(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(DocumentVersionStatus::getCode).collect(Collectors.toUnmodifiableSet());
    }

    @JsonCreator
    public static DocumentVersionStatus fromCode(String value) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown document version status: " + value));
    }
}
