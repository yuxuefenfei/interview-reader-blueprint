package cn.wangwenzhu.ireader.upgradeconsole;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import cn.wangwenzhu.ireader.upgradeconsole.domain.*;
import cn.wangwenzhu.ireader.upgradeconsole.infrastructure.StateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        var release = new Release("release-1", 123L, "a".repeat(40), "b".repeat(64), 3L, now);
        var event = new OperationEvent(now, "SUCCEEDED", "原有事件");
        var operation = new UpgradeOperation("operation-1", release.id(), "SUCCEEDED", "SUCCEEDED",
                "backup-1", "原有操作", now, now, java.util.List.of(event));
        json.writeValue(settings.stateDir().resolve("state.json").toFile(),
                new UpgradeSnapshot(java.util.List.of(release), java.util.List.of(operation)));

        try (var store = new AutoCloseableStore(new StateStore(settings, json))) {
            assertThat(store.value.snapshot().releases()).containsExactly(release);
            assertThat(store.value.operation(operation.id())).isEqualTo(operation);
            assertThat(Files.exists(settings.stateDir().resolve("console-state.mv.db"))).isTrue();
            assertThat(Files.exists(settings.stateDir().resolve("state.json.migrated"))).isTrue();
            assertThat(Files.exists(settings.stateDir().resolve("state.json"))).isFalse();
            store.value.appendCommandLog(operation.id(), FeedKind.COMMAND_STDOUT, "新日志");
        }
        try (var restarted = new AutoCloseableStore(new StateStore(settings, json))) {
            assertThat(restarted.value.operation(operation.id()).events()).hasSize(1);
            assertThat(restarted.value.operationFeed(operation.id(), 0, 20)).hasSize(2);
            assertThat(restarted.value.operationFeed(operation.id(), 0, 20).get(1).message()).isEqualTo("新日志");
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

    @Test
    void releaseAndOperationListsAreSearchedAndPagedInH2() throws Exception {
        var settings = new UpgradeSettings(directory, directory, directory.resolve("state"), directory.resolve("mysql.cnf"),
                "interview_reader", directory.resolve("mysql"), directory.resolve("mysqldump"),
                "owner", "repo", "token", "admin", "password", "https://upgrade.example.com",
                "01234567890123456789012345678901", 28080);
        try (var managed = new AutoCloseableStore(new StateStore(settings, new ObjectMapper().findAndRegisterModules()))) {
            var firstFile = Files.createTempFile(settings.stateDir().resolve("releases"), "one-", ".jar");
            var secondFile = Files.createTempFile(settings.stateDir().resolve("releases"), "two-", ".jar");
            var first = managed.value.addRelease(101, "a".repeat(40), "b".repeat(64), 3, firstFile);
            var second = managed.value.addRelease(202, "c".repeat(40), "d".repeat(64), 3, secondFile);
            assertThat(managed.value.releases("", 1, 1).items()).containsExactly(second);
            assertThat(managed.value.releases("", 1, 1).hasNext()).isTrue();
            assertThat(managed.value.releases("101", 1, 20).items()).containsExactly(first);

            var operation = managed.value.begin(first.id());
            managed.value.update(operation.id(), OperationStatus.FAILED, UpgradeStage.FAILED, "预检失败");
            managed.value.begin(first.id());
            assertThat(managed.value.operations(first.id(), 1, 1).total()).isEqualTo(2);
            assertThat(managed.value.operations(first.id(), 1, 1).items()).hasSize(1);
            assertThat(managed.value.operations(first.id(), 1, 1).items().getFirst().events()).isEmpty();
        }
    }
    private record AutoCloseableStore(StateStore value) implements AutoCloseable {
        @Override public void close() throws Exception { value.close(); }
    }
}
