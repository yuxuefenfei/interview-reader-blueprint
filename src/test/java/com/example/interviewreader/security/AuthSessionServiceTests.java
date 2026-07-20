package com.example.interviewreader.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionServiceTests {
    @Test
    void scheduledSweepRemovesExpiredSessionsThatAreNeverReadAgain() {
        var clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        var properties = new AuthProperties(
                true,
                "admin",
                "password",
                Duration.ofMinutes(1),
                Duration.ofMinutes(10),
                false,
                List.of("http://localhost"),
                false,
                new AuthProperties.LoginRateLimit(5, Duration.ofMinutes(1), Duration.ofMinutes(5), 1000));
        var service = new AuthSessionService(properties, clock);

        var token = service.createSession("admin", "password").orElseThrow();
        assertThat(service.sessionCount()).isEqualTo(1);
        assertThat(service.usernameForToken(token)).contains("admin");

        clock.advance(Duration.ofMinutes(1));
        service.sweepExpired();

        assertThat(service.sessionCount()).isZero();
        assertThat(service.usernameForToken(token)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
