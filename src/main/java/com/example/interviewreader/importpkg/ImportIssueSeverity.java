package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 导入问题级别，枚举名与持久化编码一致。 */
public enum ImportIssueSeverity {
    BLOCKING("BLOCKING"),
    WARNING("WARNING");

    private final String code;

    ImportIssueSeverity(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    public boolean matches(String value) {
        return code.equals(value);
    }

    @JsonCreator
    public static ImportIssueSeverity fromCode(String value) {
        for (var severity : values()) {
            if (severity.matches(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown import issue severity: " + value);
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(ImportIssueSeverity::getCode).collect(Collectors.toUnmodifiableSet());
    }
}
