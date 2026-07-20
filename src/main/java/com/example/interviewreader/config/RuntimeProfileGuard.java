package com.example.interviewreader.config;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeProfileGuard {
    private static final Set<String> SUPPORTED_PROFILES = Set.of("dev", "test", "prod");

    public RuntimeProfileGuard(Environment environment) {
        var activeProfiles = Set.of(environment.getActiveProfiles());
        if (activeProfiles.size() != 1 || !SUPPORTED_PROFILES.containsAll(activeProfiles)) {
            throw new IllegalStateException(
                    "Exactly one supported Spring profile is required. Select one of: dev, test, prod.");
        }

        // 非测试环境禁止把耗时转换退回 HTTP 线程同步执行，避免上传请求长期占用容器线程。
        var importWorkerEnabled = environment.getProperty("interview-reader.import-worker.enabled", Boolean.class, true);
        if (!activeProfiles.contains("test") && !importWorkerEnabled) {
            throw new IllegalStateException("Import worker must be enabled outside the test profile.");
        }
    }
}