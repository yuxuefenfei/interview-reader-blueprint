package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mybatisflex.annotation.EnumValue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 导入流程阶段，枚举名与数据库及 API 中的稳定编码一致。 */
public enum ImportStage {
    UPLOADED("UPLOADED"),
    PREFLIGHT("PREFLIGHT"),
    EXTRACTING("EXTRACTING"),
    NORMALIZING("NORMALIZING"),
    VALIDATING("VALIDATING"),
    READY("READY"),
    REVIEW_REQUIRED("REVIEW_REQUIRED"),
    REVIEWING("REVIEWING"),
    FAILED("FAILED"),
    CANCELED("CANCELED"),
    COMMITTED("COMMITTED"),
    DRAFT_DISCARDED("DRAFT_DISCARDED");

    private final String code;

    ImportStage(String code) {
        this.code = code;
    }

    @JsonValue
    @EnumValue
    public String getCode() {
        return code;
    }

    public ImportJobStatus activeJobStatus() {
        return switch (this) {
            case UPLOADED -> ImportJobStatus.UPLOADED;
            case PREFLIGHT -> ImportJobStatus.PREFLIGHT;
            case EXTRACTING -> ImportJobStatus.EXTRACTING;
            case NORMALIZING -> ImportJobStatus.NORMALIZING;
            case VALIDATING -> ImportJobStatus.VALIDATING;
            default -> throw new IllegalStateException("Stage does not represent an active import status: " + code);
        };
    }

    public static ImportStage resultStage(ImportJobStatus status) {
        return switch (status) {
            case READY -> READY;
            case REVIEW_REQUIRED -> REVIEW_REQUIRED;
            default -> throw new IllegalArgumentException("Import status has no result stage: " + status.getCode());
        };
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(ImportStage::getCode).collect(Collectors.toUnmodifiableSet());
    }

    @JsonCreator
    public static ImportStage fromCode(String value) {
        return Arrays.stream(values())
                .filter(stage -> stage.code.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown import stage: " + value));
    }
}
