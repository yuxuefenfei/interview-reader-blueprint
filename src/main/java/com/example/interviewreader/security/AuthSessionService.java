package com.example.interviewreader.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AuthSessionService {
    private static final int TOKEN_BYTES = 32;

    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public AuthSessionService(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AuthSessionService(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<String> createSession(String username, String password) {
        if (!constantTimeEquals(properties.username(), username)
                || !constantTimeEquals(properties.password(), password)) {
            return Optional.empty();
        }
        sweepExpired();
        var token = newToken();
        sessions.put(token, new Session(properties.username(), Instant.now(clock).plus(properties.sessionTtl())));
        return Optional.of(token);
    }

    public Optional<String> usernameForToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        var session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (isExpired(session, Instant.now(clock))) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    public void destroySession(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private String newToken() {
        var bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 定期清理无人再次访问的过期会话，避免长期运行进程持续保留失效令牌。 */
    @Scheduled(fixedDelayString = "${interview-reader.security.session-cleanup-interval}")
    void sweepExpired() {
        var now = Instant.now(clock);
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }


    /** 到期时刻本身即不再有效，避免查询路径与定时清理对边界的解释不同。 */
    private boolean isExpired(Session session, Instant now) {
        return !session.expiresAt().isAfter(now);
    }

    int sessionCount() {
        return sessions.size();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        var expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var actualBytes = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private record Session(String username, Instant expiresAt) {
    }
}
