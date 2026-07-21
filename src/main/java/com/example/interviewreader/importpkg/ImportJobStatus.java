package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** 导入任务状态，枚举名与数据库及 API 中的稳定编码一致。 */
public enum ImportJobStatus {
    UPLOADED("UPLOADED"),
    PREFLIGHT("PREFLIGHT"),
    EXTRACTING("EXTRACTING"),
    NORMALIZING("NORMALIZING"),
    VALIDATING("VALIDATING"),
    READY("READY"),
    REVIEW_REQUIRED("REVIEW_REQUIRED"),
    IMPORTED("IMPORTED"),
    FAILED("FAILED"),
    CANCELED("CANCELED");

    private static final Set<ImportJobStatus> CANCELABLE = EnumSet.of(
            UPLOADED, PREFLIGHT, EXTRACTING, NORMALIZING, VALIDATING);
    private final String code;

    ImportJobStatus(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    public static boolean isCancelable(ImportJobStatus value) {
        return CANCELABLE.contains(value);
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(ImportJobStatus::getCode).collect(Collectors.toUnmodifiableSet());
    }

    @JsonCreator
    public static ImportJobStatus fromCode(String value) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown import job status: " + value));
    }
}
