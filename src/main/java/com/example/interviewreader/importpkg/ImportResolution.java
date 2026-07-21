package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 导入提交时的目标文档处理方式。 */
public enum ImportResolution {
    CREATE_NEW("CREATE_NEW"),
    IMPORT_AS_NEW_VERSION("IMPORT_AS_NEW_VERSION");

    private final String code;

    ImportResolution(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static ImportResolution fromCode(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(resolution -> resolution.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown import resolution: " + value));
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(ImportResolution::getCode).collect(Collectors.toUnmodifiableSet());
    }
}
