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
    private record AutoCloseableStore(StateStore value) implements AutoCloseable {
        @Override public void close() throws Exception { value.close(); }
    }
}
