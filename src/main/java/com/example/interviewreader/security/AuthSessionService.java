package com.example.interviewreader.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
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
        if (session.expiresAt().isBefore(Instant.now(clock))) {
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

    private void sweepExpired() {
        var now = Instant.now(clock);
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        var expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var actualBytes = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return expectedBytes.length == actualBytes.length && Arrays.equals(expectedBytes, actualBytes);
    }

    private record Session(String username, Instant expiresAt) {
    }
}
