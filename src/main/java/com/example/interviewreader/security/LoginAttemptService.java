package com.example.interviewreader.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {
    private static final int MAX_KEY_PART_LENGTH = 200;

    private final AuthProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginAttemptService(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String attemptKey(HttpServletRequest request, String username) {
        return clientAddress(request) + ':' + normalize(username);
    }

    public void requireAllowed(String key) {
        var retryAfter = retryAfter(key);
        if (!retryAfter.isZero()) throw new LoginRateLimitException(retryAfter);
    }

    public void recordFailure(String key) {
        ensureCapacity(key);
        var now = Instant.now(clock);
        var policy = properties.loginRateLimit();
        var attempt = attempts.compute(key, (ignored, previous) -> {
            if (previous == null || !now.isBefore(previous.windowStarted().plus(policy.window()))) {
                return new Attempt(1, now, null, now);
            }
            if (previous.blockedUntil() != null && now.isBefore(previous.blockedUntil())) {
                return new Attempt(previous.failures(), previous.windowStarted(), previous.blockedUntil(), now);
            }
            var failures = previous.failures() + 1;
            var blockedUntil = failures >= policy.maxAttempts() ? now.plus(policy.blockDuration()) : null;
            return new Attempt(failures, previous.windowStarted(), blockedUntil, now);
        });
        if (attempt.blockedUntil() != null && now.isBefore(attempt.blockedUntil())) {
            throw new LoginRateLimitException(Duration.between(now, attempt.blockedUntil()));
        }
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    private Duration retryAfter(String key) {
        var now = Instant.now(clock);
        var attempt = attempts.get(key);
        if (attempt == null || attempt.blockedUntil() == null) return Duration.ZERO;
        if (!now.isBefore(attempt.blockedUntil())) {
            attempts.remove(key, attempt);
            return Duration.ZERO;
        }
        return Duration.between(now, attempt.blockedUntil());
    }

    private void ensureCapacity(String key) {
        var limit = properties.loginRateLimit().maxEntries();
        if (attempts.containsKey(key) || attempts.size() < limit) return;
        var now = Instant.now(clock);
        attempts.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        if (attempts.size() < limit) return;
        // 攻击者可制造大量用户名/IP 组合；达到上限时淘汰最久未更新的记录，避免限流器自身耗尽内存。
        attempts.entrySet().stream().min(java.util.Map.Entry.comparingByValue(
                java.util.Comparator.comparing(Attempt::updatedAt))).ifPresent(entry -> attempts.remove(entry.getKey(), entry.getValue()));
    }

    private boolean expired(Attempt attempt, Instant now) {
        var policy = properties.loginRateLimit();
        var activeUntil = attempt.blockedUntil() == null
                ? attempt.windowStarted().plus(policy.window())
                : attempt.blockedUntil();
        return !now.isBefore(activeUntil);
    }

    private String clientAddress(HttpServletRequest request) {
        var remote = normalize(request.getRemoteAddr());
        if (!properties.trustForwardedClientIp() || !isLoopback(remote)) return remote;
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remote;
        return normalize(forwarded.split(",", 2)[0]);
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address);
    }

    private String normalize(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= MAX_KEY_PART_LENGTH ? normalized : normalized.substring(0, MAX_KEY_PART_LENGTH);
    }

    private record Attempt(int failures, Instant windowStarted, Instant blockedUntil, Instant updatedAt) {
    }
}
