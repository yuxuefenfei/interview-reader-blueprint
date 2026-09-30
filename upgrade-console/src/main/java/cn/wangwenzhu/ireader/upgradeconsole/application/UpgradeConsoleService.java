package cn.wangwenzhu.ireader.upgradeconsole.application;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import cn.wangwenzhu.ireader.upgradeconsole.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Coordinates console use cases without exposing HTTP uploads or persistence records.
 */
@Service
@RequiredArgsConstructor
public class UpgradeConsoleService {
    private static final long MAX_UPLOAD_BYTES = 150_000_000;
    private final UpgradeSettings settings;
    private final UpgradeStateRepository store;
    private final MainHealthService health;
    private final GithubArtifactVerifier verifier;
    private final DeploymentCoordinator deployments;
    private final OperationFeedService feed;

    public Overview overview() throws IOException {
        return new Overview(health.snapshot(), store.snapshot(), feed.cursor());
    }

    public UpgradeOperation operation(String id) {
        return store.operation(id);
    }

    public Dashboard dashboard() throws IOException {
        return new Dashboard(health.snapshot(), store.latestOperation(), feed.cursor());
    }

    public PageResult<Release> releases(String query, int page, int size) throws IOException {
        return store.releases(query, page, size);
    }

    public Release release(String id) {
        return store.release(id);
    }

    public PageResult<UpgradeOperation> operations(int page, int size) throws IOException {
        return store.operations(page, size);
    }

    public PageResult<UpgradeOperation> operations(String releaseId, int page, int size) throws IOException {
        return store.operations(releaseId, page, size);
    }

    public Release upload(InputStream input, long bytes, long runId) throws Exception {
        if (bytes <= 0 || bytes > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("JAR 大小不符合要求");
        Path staged = Files.createTempFile(settings.stateDir().resolve("releases"), "upload-", ".jar");
        try {
            Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
            var verified = verifier.verify(staged, runId);
            return store.addRelease(runId, verified.commit(), verified.sha256(), verified.bytes(), staged);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public UpgradeOperation deploy(String releaseId) throws Exception {
        var release = store.release(releaseId);
        var verified = verifier.verify(store.releasePath(releaseId), release.runId());
        if (!release.sha256().equals(verified.sha256()) || !release.commit().equals(verified.commit()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "暂存产物与已验证记录不一致");
        return deployments.deploy(releaseId);
    }

    public UpgradeOperation restorePublished(String backupId, boolean confirmed) throws IOException {
        if (!confirmed || backupId == null || backupId.isBlank())
            throw new IllegalArgumentException("人工恢复必须确认备份 ID 与可能丢失的写入");
        return deployments.restorePublished(backupId);
    }

    public UpgradeOperation abortInterrupted(String operationId, boolean confirmed) throws IOException {
        if (!confirmed) throw new IllegalArgumentException("请确认恢复旧版写入");
        return deployments.abortInterrupted(operationId);
    }

    public UpgradeOperation recover(String operationId, String backupId, boolean confirmed) throws IOException {
        var operation = store.operation(operationId);
        if (!confirmed || !java.util.Objects.equals(operation.backupId(), backupId))
            throw new IllegalArgumentException("恢复必须确认同批次备份与数据覆盖");
        return deployments.recover(operationId);
    }

    public record Overview(MainHealthService.Snapshot health, UpgradeSnapshot state, long feedCursor) {
    }

    public record Dashboard(MainHealthService.Snapshot health, UpgradeOperation latestOperation, long feedCursor) {
    }
}