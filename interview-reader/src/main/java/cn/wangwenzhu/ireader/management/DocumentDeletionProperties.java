package cn.wangwenzhu.ireader.management;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "interview-reader.deletion")
@Validated
public record DocumentDeletionProperties(
        boolean workerEnabled,
        @Min(1) int maxConcurrency,
        @Min(1) int maxAttempts,
        @NotNull Duration retryDelay,
        @NotNull Duration tombstoneRetention) {
    @AssertTrue(message = "retry delay and tombstone retention must be positive")
    public boolean hasPositiveDurations() {
        return positive(retryDelay) && positive(tombstoneRetention);
    }

    private static boolean positive(Duration value) {
        return value == null || (!value.isZero() && !value.isNegative());
    }
}