package com.example.interviewreader.security;

import java.time.Duration;

public final class LoginRateLimitException extends RuntimeException {
    private final Duration retryAfter;

    public LoginRateLimitException(Duration retryAfter) {
        super("登录尝试过于频繁，请稍后重试。");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
