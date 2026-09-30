package com.example.interviewreader.upgradeconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateStoreTest {
    @TempDir Path directory;

    @Test
    void interruptedOperationSurvivesConsoleRestartAndBlocksAnotherDeployment() throws Exception {
        var settings = new UpgradeSettings(directory, directory, directory.resolve("state"), directory.resolve("mysql.cnf"),
                "interview_reader", directory.resolve("mysql"), directory.resolve("mysqldump"),
                "owner", "repo", "token", "admin", "password", "https://upgrade.example.com",
                "01234567890123456789012345678901", 28080);
        var json = new ObjectMapper().findAndRegisterModules();
        String operationId;
        String releaseId;
        try (var store = new AutoCloseableStore(new StateStore(settings, json))) {
            var staged = Files.createTempFile(settings.stateDir().resolve("releases"), "upload-", ".jar");
            Files.writeString(staged, "jar");
            releaseId = store.value.addRelease(123L, "a".repeat(40), "b".repeat(64), 3, staged).id();
            operationId = store.value.begin(releaseId).id();
            Files.createDirectories(settings.marker().getParent());
            Files.writeString(settings.marker(), operationId);
        }
        try (var restarted = new AutoCloseableStore(new StateStore(settings, json))) {
            assertThat(restarted.value.operation(operationId).status()).isEqualTo("NEEDS_OPERATOR");
            assertThat(restarted.value.release(releaseId).runId()).isEqualTo(123L);
            assertThatThrownBy(() -> restarted.value.begin(releaseId)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void restartBeforeMaintenanceGateDoesNotBlockFutureDeployment() throws Exception {
        var settings = new UpgradeSettings(directory, directory, directory.resolve("state"), directory.resolve("mysql.cnf"),
                "interview_reader", directory.resolve("mysql"), directory.resolve("mysqldump"),
                "owner", "repo", "token", "admin", "password", "https://upgrade.example.com",
                "01234567890123456789012345678901", 28080);
        var json = new ObjectMapper().findAndRegisterModules();
        String operationId;
        String releaseId;
        try (var store = new AutoCloseableStore(new StateStore(settings, json))) {
            var staged = Files.createTempFile(settings.stateDir().resolve("releases"), "upload-", ".jar");
            Files.writeString(staged, "jar");
            releaseId = store.value.addRelease(124L, "a".repeat(40), "b".repeat(64), 3, staged).id();
            operationId = store.value.begin(releaseId).id();
        }
        try (var restarted = new AutoCloseableStore(new StateStore(settings, json))) {
            assertThat(restarted.value.operation(operationId).status()).isEqualTo("FAILED");
            assertThat(restarted.value.begin(releaseId).status()).isEqualTo("RUNNING");
        }
    }
    @Test
    void migratesLegacyJsonOnceAndKeepsLaterEventsInH2() throws Exception {
        var settings = new UpgradeSettings(directory, directory, directory.resolve("state"), directory.resolve("mysql.cnf"),
                "interview_reader", directory.resolve("mysql"), directory.resolve("mysqldump"),
                "owner", "repo", "token", "admin", "password", "https://upgrade.example.com",
                "01234567890123456789012345678901", 28080);
        var json = new ObjectMapper().findAndRegisterModules();
        Files.createDirectories(settings.stateDir());
        var now = java.time.Instant.parse("2026-09-30T01:00:00Z");
        var release = new StateStore.Release("release-1", 123L, "a".repeat(40), "b".repeat(64), 3L, now);
        var event = new StateStore.Event(now, "SUCCEEDED", "原有事件");
        var operation = new StateStore.Operation("operation-1", release.id(), "SUCCEEDED", "SUCCEEDED",
                "backup-1", "原有操作", now, now, java.util.List.of(event));
        json.writeValue(settings.stateDir().resolve("state.json").toFile(),
                new StateStore.Snapshot(java.util.List.of(release), java.util.List.of(operation)));

        try (var store = new AutoCloseableStore(new StateStore(settings, json))) {
            assertThat(store.value.snapshot().releases()).containsExactly(release);
            assertThat(store.value.operation(operation.id())).isEqualTo(operation);
            assertThat(Files.exists(settings.stateDir().resolve("console-state.mv.db"))).isTrue();
            assertThat(Files.exists(settings.stateDir().resolve("state.json.migrated"))).isTrue();
            assertThat(Files.exists(settings.stateDir().resolve("state.json"))).isFalse();
            store.value.update(operation.id(), "RESTORED", "RESTORED", "新事件");
        }
        try (var restarted = new AutoCloseableStore(new StateStore(settings, json))) {
            assertThat(restarted.value.operation(operation.id()).events()).hasSize(2);
            assertThat(restarted.value.operation(operation.id()).events().get(1).message()).isEqualTo("新事件");
            assertThat(restarted.value.snapshot().releases()).containsExactly(release);
        }

        Files.writeString(settings.stateDir().resolve("state.json"), "stale");
        assertThatThrownBy(() -> new StateStore(settings, json)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("归档已存在");

        Files.delete(settings.stateDir().resolve("state.json"));
        Files.delete(settings.stateDir().resolve("console-state.mv.db"));
        assertThatThrownBy(() -> new StateStore(settings, json)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("数据库缺失");
    }
    private record AutoCloseableStore(StateStore value) implements AutoCloseable {
        @Override public void close() throws Exception { value.close(); }
    }
}
