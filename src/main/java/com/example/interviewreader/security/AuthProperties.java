package com.example.interviewreader.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "interview-reader.security")
public record AuthProperties(
        boolean enabled,
        String username,
        String password,
        Duration sessionTtl) {
    public AuthProperties {
        username = username == null || username.isBlank() ? "admin" : username;
        password = password == null || password.isBlank() ? "admin" : password;
        sessionTtl = sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()
                ? Duration.ofHours(12)
                : sessionTtl;
    }
}
