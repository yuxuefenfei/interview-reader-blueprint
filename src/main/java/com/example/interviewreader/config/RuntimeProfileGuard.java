package com.example.interviewreader.config;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeProfileGuard {
    private static final Set<String> SUPPORTED_PROFILES = Set.of("dev", "test", "prod");

    public RuntimeProfileGuard(Environment environment) {
        var activeProfiles = Set.of(environment.getActiveProfiles());
        if (activeProfiles.stream().noneMatch(SUPPORTED_PROFILES::contains)) {
            throw new IllegalStateException(
                    "An explicit Spring profile is required. Select one of: dev, test, prod.");
        }
    }
}