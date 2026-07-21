package com.example.interviewreader.document;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 文档内容块类型，对应数据库与 API 中的稳定编码。 */
public enum BlockType {
    PARAGRAPH("paragraph"),
    HEADING_NOTE("heading_note"),
    UNORDERED_LIST("unordered_list"),
    ORDERED_LIST("ordered_list"),
    CODE("code"),
    TABLE("table"),
    QUOTE("quote"),
    CALLOUT("callout"),
    FORMULA("formula"),
    IMAGE("image"),
    DIVIDER("divider"),
    TABLE_SNAPSHOT("table_snapshot");

    private final String code;

    BlockType(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static BlockType fromCode(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown block type: " + value));
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(BlockType::getCode).collect(Collectors.toUnmodifiableSet());
    }
}
