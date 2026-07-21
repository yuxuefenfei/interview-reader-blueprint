package com.example.interviewreader.document;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 文档节点语义角色，对应数据库与 API 中的稳定编码。 */
public enum SemanticRole {
    QUESTION("QUESTION"),
    ANSWER("ANSWER"),
    EXPLANATION("EXPLANATION"),
    CONCLUSION("CONCLUSION"),
    INTRODUCTION("INTRODUCTION"),
    DIRECTORY("DIRECTORY"),
    PRINCIPLE("PRINCIPLE"),
    PRACTICE("PRACTICE"),
    PITFALL("PITFALL"),
    FOLLOW_UP("FOLLOW_UP");

    private final String code;

    SemanticRole(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static SemanticRole fromCode(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown semantic role: " + value));
    }

    public static SemanticRole fromNullableCode(String value) {
        return value == null || value.isBlank() ? null : fromCode(value);
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(SemanticRole::getCode).collect(Collectors.toUnmodifiableSet());
    }
}
