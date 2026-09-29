package com.example.interviewreader.upgradeconsole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ConsoleController {
    public record Confirmation(String backupId, boolean confirmDataRestore) {}

    private final UpgradeSettings settings;
    private final StateStore store;
    private final MainHealthClient health;
    private final GithubArtifactVerifier verifier;
    private final DeploymentCoordinator deployments;

    public ConsoleController(UpgradeSettings settings, StateStore store, MainHealthClient health,
                             GithubArtifactVerifier verifier, DeploymentCoordinator deployments) {
        this.settings = settings;
        this.store = store;
        this.health = health;
        this.verifier = verifier;
        this.deployments = deployments;
    }

    @GetMapping("/api/overview")
    public Map<String, Object> overview() {
        return Map.of("health", health.snapshot(), "state", store.snapshot());
    }

    @GetMapping("/api/operations/{id}")
    public StateStore.Operation operation(@PathVariable String id) { return store.operation(id); }

    @PostMapping(value = "/api/releases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StateStore.Release upload(@RequestPart("file") MultipartFile file, @RequestParam long runId) throws Exception {
        if (file.isEmpty() || file.getSize() > 150_000_000L) throw new IllegalArgumentException("JAR 大小不符合要求");
        Path staged = Files.createTempFile(settings.stateDir().resolve("releases"), "upload-", ".jar");
        try {
            try (var input = file.getInputStream()) { Files.copy(input, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            var verified = verifier.verify(staged, runId);
            return store.addRelease(runId, verified.commit(), verified.sha256(), verified.bytes(), staged);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @PostMapping("/api/releases/{id}/deployments")
    public StateStore.Operation deploy(@PathVariable String id) throws Exception {
        var release = store.release(id);
        var verified = verifier.verify(store.releasePath(id), release.runId());
        if (!release.sha256().equals(verified.sha256()) || !release.commit().equals(verified.commit())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "暂存产物与已验证记录不一致");
        }
        return deployments.deploy(id);
    }

    @PostMapping("/api/recoveries")
    public StateStore.Operation restorePublished(@RequestBody Confirmation confirmation) throws IOException {
        if (confirmation == null || !confirmation.confirmDataRestore()
                || confirmation.backupId() == null || confirmation.backupId().isBlank()) {
            throw new IllegalArgumentException("人工恢复必须确认备份 ID 与可能丢失的写入");
        }
        return deployments.restorePublished(confirmation.backupId());
    }
    @PostMapping("/api/operations/{id}/abort")
    public StateStore.Operation abortInterrupted(@PathVariable String id, @RequestBody Confirmation confirmation) throws IOException {
        if (confirmation == null || !confirmation.confirmDataRestore())
            throw new IllegalArgumentException("请确认恢复旧版写入");
        return deployments.abortInterrupted(id);
    }
    @PostMapping("/api/operations/{id}/recover")
    public StateStore.Operation recover(@PathVariable String id, @RequestBody Confirmation confirmation) throws IOException {
        var operation = store.operation(id);
        if (confirmation == null || !confirmation.confirmDataRestore()
                || !java.util.Objects.equals(operation.backupId(), confirmation.backupId())) {
            throw new IllegalArgumentException("恢复必须确认同批次备份与数据覆盖");
        }
        return deployments.recover(id);
    }
}
