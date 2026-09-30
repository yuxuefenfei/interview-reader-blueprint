package com.example.interviewreader.upgradeconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class StateStore {
    public record Release(String id, long runId, String commit, String sha256, long bytes, Instant stagedAt) {}
    public record Event(Instant at, String stage, String message) {}
    public record Operation(String id, String releaseId, String status, String stage, String backupId,
                            String message, Instant startedAt, Instant updatedAt, List<Event> events) {}
    public record Snapshot(List<Release> releases, List<Operation> operations) {}

    private final Path root;
    private final Path stateFile;
    private final ObjectMapper json;
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private Snapshot snapshot;

    public StateStore(UpgradeSettings settings, ObjectMapper json) throws IOException {
        this.root = settings.stateDir().toAbsolutePath().normalize();
        this.stateFile = root.resolve("state.json");
        this.json = json;
        Files.createDirectories(root.resolve("releases"));
        Files.createDirectories(root.resolve("backups"));
        lockChannel = FileChannel.open(root.resolve("console.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        processLock = lockChannel.tryLock();
        if (processLock == null) throw new IllegalStateException("已有升级控制台进程持有操作锁");
        snapshot = Files.exists(stateFile) ? json.readValue(stateFile.toFile(), Snapshot.class)
                : new Snapshot(List.of(), List.of());
        for (var operation : List.copyOf(snapshot.operations())) {
            if (operation.status().equals("RUNNING")) {
                if (operation.backupId() == null && !Files.exists(settings.marker())
                        && (operation.stage().equals("PRECHECKING") || operation.stage().equals("STOP_WRITES"))) {
                    update(operation.id(), "FAILED", "FAILED", "控制台在设置维护门禁前中断；主应用未切换");
                } else {
                    update(operation.id(), "NEEDS_OPERATOR", "INTERRUPTED", "控制台中断；保留停写状态，需人工核实后恢复");
                }
            }
        }
    }

    public Path releasePath(String id) { return root.resolve("releases").resolve(id + ".jar"); }
    public Path backupPath(String id) { return root.resolve("backups").resolve(id); }
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(snapshot.releases()), List.copyOf(snapshot.operations()));
    }

    public synchronized Release addRelease(long runId, String commit, String hash, long bytes, Path staged) throws IOException {
        if (snapshot.releases().stream().anyMatch(item -> item.runId() == runId)) {
            throw new IllegalStateException("该构建已上传");
        }
        var release = new Release(UUID.randomUUID().toString(), runId, commit, hash, bytes, Instant.now());
        Files.move(staged, releasePath(release.id()), StandardCopyOption.ATOMIC_MOVE);
        var releases = new ArrayList<>(snapshot.releases());
        releases.add(release);
        snapshot = new Snapshot(releases, snapshot.operations());
        save();
        return release;
    }

    public synchronized Release release(String id) {
        return snapshot.releases().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("发布产物不存在"));
    }

    public synchronized Operation begin(String releaseId) throws IOException {
        release(releaseId);
        if (snapshot.operations().stream().anyMatch(item -> item.status().equals("RUNNING")
                || item.status().equals("NEEDS_OPERATOR"))) {
            throw new IllegalStateException("已有升级或待人工处理的操作");
        }
        var now = Instant.now();
        var operation = new Operation(UUID.randomUUID().toString(), releaseId, "RUNNING", "PRECHECKING", null,
                "正在预检", now, now, List.of(new Event(now, "PRECHECKING", "正在预检")));
        var operations = new ArrayList<>(snapshot.operations());
        operations.add(operation);
        snapshot = new Snapshot(snapshot.releases(), operations);
        save();
        return operation;
    }

    public synchronized Operation beginRecovery(String backupId) throws IOException {
        if (snapshot.operations().stream().anyMatch(item -> item.status().equals("RUNNING")
                || item.status().equals("NEEDS_OPERATOR"))) {
            throw new IllegalStateException("已有升级或待人工处理的操作");
        }
        var source = snapshot.operations().stream().filter(item -> backupId.equals(item.backupId())
                && item.status().equals("SUCCEEDED")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("只允许恢复已成功发布对应的备份"));
        var now = Instant.now();
        var operation = new Operation(UUID.randomUUID().toString(), source.releaseId(), "RUNNING", "PRECHECKING",
                backupId, "人工恢复预检", now, now, List.of(new Event(now, "PRECHECKING", "人工恢复预检")));
        var operations = new ArrayList<>(snapshot.operations());
        operations.add(operation);
        snapshot = new Snapshot(snapshot.releases(), operations);
        save();
        return operation;
    }
    public synchronized Operation operation(String id) {
        return snapshot.operations().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作不存在"));
    }

    public synchronized Operation update(String id, String status, String stage, String message) throws IOException {
        var old = operation(id);
        var now = Instant.now();
        var events = new ArrayList<>(old.events());
        events.add(new Event(now, stage, message));
        var next = new Operation(id, old.releaseId(), status, stage, old.backupId(), message,
                old.startedAt(), now, events);
        replace(next);
        return next;
    }

    public synchronized void setBackup(String id) throws IOException {
        var old = operation(id);
        replace(new Operation(id, old.releaseId(), old.status(), old.stage(), id, old.message(),
                old.startedAt(), Instant.now(), old.events()));
    }

    private void replace(Operation next) throws IOException {
        var operations = new ArrayList<>(snapshot.operations());
        for (var i = 0; i < operations.size(); i++) {
            if (operations.get(i).id().equals(next.id())) {
                operations.set(i, next);
                snapshot = new Snapshot(snapshot.releases(), operations);
                save();
                return;
            }
        }
        throw new IllegalArgumentException("操作不存在");
    }

    private void save() throws IOException {
        var temp = root.resolve("state.json.tmp");
        json.writeValue(temp.toFile(), snapshot);
        Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @PreDestroy
    void close() throws IOException {
        processLock.release();
        lockChannel.close();
    }
}
