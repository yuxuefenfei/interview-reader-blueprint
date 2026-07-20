package com.example.interviewreader.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "interview-reader.security")
@Validated
public record AuthProperties(
        boolean enabled,
        String username,
        String password,
        @NotNull @DurationMin(seconds = 1) Duration sessionTtl,
        @NotNull @DurationMin(seconds = 1) Duration sessionCleanupInterval,
        boolean secureCookie,
        @NotNull List<@NotBlank String> allowedOrigins,
        boolean trustForwardedClientIp,
        @NotNull @Valid LoginRateLimit loginRateLimit) {
    @AssertTrue(message = "username and password must be non-blank when security is enabled")
    public boolean isCredentialConfigurationValid() {
        return !enabled || hasText(username) && hasText(password);
    }

    @AssertTrue(message = "at least one allowed origin must be configured when security is enabled")
    public boolean isAllowedOriginConfigurationValid() {
        return !enabled || allowedOrigins != null && !allowedOrigins.isEmpty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record LoginRateLimit(
            @Min(1) int maxAttempts,
            @NotNull @DurationMin(seconds = 1) Duration window,
            @NotNull @DurationMin(seconds = 1) Duration blockDuration,
            @Min(100) int maxEntries) {
    }
}
