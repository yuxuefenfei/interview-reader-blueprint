package com.example.interviewreader.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "interview-reader.security")
@Validated
public record AuthProperties(
        boolean enabled,
        String username,
        String password,
        @NotNull @DurationMin(seconds = 1) Duration sessionTtl,
        boolean secureCookie) {
    @AssertTrue(message = "username and password must be non-blank when security is enabled")
    public boolean isCredentialConfigurationValid() {
        return !enabled || hasText(username) && hasText(password);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}