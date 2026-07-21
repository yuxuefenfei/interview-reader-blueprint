package com.example.interviewreader.exportpkg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

/** 管理后台支持的文档导出格式。 */
public enum ExportFormat {
    JSON_PACKAGE("JSON_PACKAGE"),
    EXCEL("EXCEL"),
    MARKDOWN("MARKDOWN"),
    STATIC_HTML("STATIC_HTML");

    private final String code;

    ExportFormat(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static ExportFormat fromCode(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> format.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown export format: " + value));
    }
}
