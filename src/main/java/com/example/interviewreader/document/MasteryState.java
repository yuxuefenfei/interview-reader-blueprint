package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.mybatisflex.annotation.EnumValue;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 复习掌握度，枚举名与数据库及 API 中的稳定编码一致。 */
public enum MasteryState {
    UNKNOWN("UNKNOWN", null),
    HARD("HARD", 1),
    FUZZY("FUZZY", 3),
    KNOWN("KNOWN", 7);

    private final String code;
    private final Integer intervalDays;

    MasteryState(String code, Integer intervalDays) {
        this.code = code;
        this.intervalDays = intervalDays;
    }

    @EnumValue
    public String getCode() {
        return code;
    }

    public Integer intervalDays() {
        return intervalDays;
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(MasteryState::getCode).collect(Collectors.toUnmodifiableSet());
    }

    public static MasteryState parse(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        try {
            return Arrays.stream(values())
                    .filter(state -> state.code.equals(normalized))
                    .findFirst()
                    .orElseThrow();
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "mastery must be UNKNOWN, HARD, FUZZY or KNOWN");
        }
    }
}
