package com.example.interviewreader.document;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 文档来源类型，对应数据库与 API 中的稳定编码。 */
public enum SourceType {
    PDF("PDF"),
    EXCEL("EXCEL"),
    JSON_PACKAGE("JSON_PACKAGE"),
    MARKDOWN("MARKDOWN"),
    MANUAL("MANUAL");

    private final String code;

    SourceType(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static SourceType fromCode(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown source type: " + value));
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(SourceType::getCode).collect(Collectors.toUnmodifiableSet());
    }
}
