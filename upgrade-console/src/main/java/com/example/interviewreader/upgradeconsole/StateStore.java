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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StateStore {
    public record Release(String id, long runId, String commit, String sha256, long bytes, Instant stagedAt) {}
    public record Event(Instant at, String stage, String message) {}
    public record Operation(String id, String releaseId, String status, String stage, String backupId,
                            String message, Instant startedAt, Instant updatedAt, List<Event> events) {}
    public record Snapshot(List<Release> releases, List<Operation> operations) {}

    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private final Connection db;
    private Snapshot snapshot;

    public StateStore(UpgradeSettings settings, ObjectMapper json) throws IOException {
        root = settings.stateDir().toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("releases"));
        Files.createDirectories(root.resolve("backups"));
        var legacy = root.resolve("state.json");
        var archive = root.resolve("state.json.migrated");
        if (Files.exists(archive) && !Files.exists(root.resolve("console-state.mv.db")))
            throw new IOException("升级状态数据库缺失；请先恢复 console-state.mv.db");

        FileChannel channel = null;
        FileLock lock = null;
        Connection connection = null;
        try {
            channel = FileChannel.open(root.resolve("console.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("已有升级控制台进程持有操作锁");
            var databasePath = root.resolve("console-state").toString().replace('\\', '/');
            if (databasePath.contains(";")) throw new IllegalArgumentException("升级状态目录不能包含分号");
            connection = DriverManager.getConnection("jdbc:h2:file:" + databasePath, "sa", "");
            lockChannel = channel;
            processLock = lock;
            db = connection;
            createSchema();
            db.setAutoCommit(false);
            migrateLegacyState(json, legacy, archive);
            snapshot = readSnapshot();
            db.commit();
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
        } catch (Exception failure) {
            try { if (connection != null) connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            try { if (lock != null) lock.release(); } catch (IOException close) { failure.addSuppressed(close); }
            try { if (channel != null) channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof SQLException sql) throw new IOException("升级状态数据库不可用", sql);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException(failure);
        }
    }

    public Path releasePath(String id) { return root.resolve("releases").resolve(id + ".jar"); }
    public Path backupPath(String id) { return root.resolve("backups").resolve(id); }
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(snapshot.releases()), List.copyOf(snapshot.operations()));
    }

    public synchronized Release addRelease(long runId, String commit, String hash, long bytes, Path staged) throws IOException {
        if (snapshot.releases().stream().anyMatch(item -> item.runId() == runId))
            throw new IllegalStateException("该构建已上传");
        var release = new Release(UUID.randomUUID().toString(), runId, commit, hash, bytes, Instant.now());
        var stored = releasePath(release.id());
        Files.move(staged, stored, StandardCopyOption.ATOMIC_MOVE);
        try {
            write(() -> insertRelease(release));
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(stored); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        var releases = new ArrayList<>(snapshot.releases());
        releases.add(release);
        snapshot = new Snapshot(releases, snapshot.operations());
        return release;
    }

    public synchronized Release release(String id) {
        return snapshot.releases().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("发布产物不存在"));
    }

    public synchronized Operation begin(String releaseId) throws IOException {
        release(releaseId);
        requireIdle();
        var now = Instant.now();
        var operation = new Operation(UUID.randomUUID().toString(), releaseId, "RUNNING", "PRECHECKING", null,
                "正在预检", now, now, List.of(new Event(now, "PRECHECKING", "正在预检")));
        addOperation(operation);
        return operation;
    }

    public synchronized Operation beginRecovery(String backupId) throws IOException {
        requireIdle();
        var source = snapshot.operations().stream().filter(item -> backupId.equals(item.backupId())
                && item.status().equals("SUCCEEDED")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("只允许恢复已成功发布对应的备份"));
        var now = Instant.now();
        var operation = new Operation(UUID.randomUUID().toString(), source.releaseId(), "RUNNING", "PRECHECKING",
                backupId, "人工恢复预检", now, now, List.of(new Event(now, "PRECHECKING", "人工恢复预检")));
        addOperation(operation);
        return operation;
    }

    public synchronized Operation operation(String id) {
        return snapshot.operations().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作不存在"));
    }

    public synchronized Operation update(String id, String status, String stage, String message) throws IOException {
        var old = operation(id);
        var now = Instant.now();
        var event = new Event(now, stage, message);
        var events = new ArrayList<>(old.events());
        events.add(event);
        var next = new Operation(id, old.releaseId(), status, stage, old.backupId(), message,
                old.startedAt(), now, List.copyOf(events));
        write(() -> {
            updateOperation(next);
            insertEvent(id, event);
        });
        replaceCached(next);
        return next;
    }

    public synchronized void setBackup(String id) throws IOException {
        var old = operation(id);
        var next = new Operation(id, old.releaseId(), old.status(), old.stage(), id, old.message(),
                old.startedAt(), Instant.now(), old.events());
        write(() -> updateOperation(next));
        replaceCached(next);
    }

    private void requireIdle() {
        if (snapshot.operations().stream().anyMatch(item -> item.status().equals("RUNNING")
                || item.status().equals("NEEDS_OPERATOR")))
            throw new IllegalStateException("已有升级或待人工处理的操作");
    }

    private void addOperation(Operation operation) throws IOException {
        write(() -> {
            insertOperation(operation);
            for (var event : operation.events()) insertEvent(operation.id(), event);
        });
        var operations = new ArrayList<>(snapshot.operations());
        operations.add(operation);
        snapshot = new Snapshot(snapshot.releases(), operations);
    }

    private void replaceCached(Operation next) {
        var operations = new ArrayList<>(snapshot.operations());
        for (var i = 0; i < operations.size(); i++) {
            if (operations.get(i).id().equals(next.id())) {
                operations.set(i, next);
                snapshot = new Snapshot(snapshot.releases(), operations);
                return;
            }
        }
        throw new IllegalArgumentException("操作不存在");
    }

    private void createSchema() throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS releases (
                      position BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                      id VARCHAR(36) NOT NULL UNIQUE, run_id BIGINT NOT NULL UNIQUE,
                      commit_sha VARCHAR(40) NOT NULL, sha256 VARCHAR(64) NOT NULL,
                      bytes BIGINT NOT NULL, staged_at VARCHAR(40) NOT NULL
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS operations (
                      position BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                      id VARCHAR(36) NOT NULL UNIQUE, release_id VARCHAR(36) NOT NULL,
                      status VARCHAR(40) NOT NULL, stage VARCHAR(40) NOT NULL,
                      backup_id VARCHAR(36), message CLOB NOT NULL,
                      started_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
                      FOREIGN KEY (release_id) REFERENCES releases(id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS operation_events (
                      position BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                      operation_id VARCHAR(36) NOT NULL, at_time VARCHAR(40) NOT NULL,
                      stage VARCHAR(40) NOT NULL, message CLOB NOT NULL,
                      FOREIGN KEY (operation_id) REFERENCES operations(id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS store_metadata (
                      id INTEGER PRIMARY KEY, legacy_sha256 VARCHAR(64)
                    )""");
        }
    }

    private void migrateLegacyState(ObjectMapper json, Path legacy, Path archive) throws IOException, SQLException {
        String importedHash = null;
        boolean initialized;
        try (var query = db.prepareStatement("SELECT legacy_sha256 FROM store_metadata WHERE id = 1");
             var rows = query.executeQuery()) {
            initialized = rows.next();
            if (initialized) importedHash = rows.getString(1);
        }
        if (initialized) {
            archiveLegacyFile(legacy, archive, importedHash);
            return;
        }
        if (Files.exists(archive))
            throw new IOException("已有旧状态归档但数据库未初始化；请人工核实");
        byte[] oldBytes = Files.exists(legacy) ? Files.readAllBytes(legacy) : null;
        Snapshot old = oldBytes == null ? new Snapshot(List.of(), List.of())
                : json.readValue(oldBytes, Snapshot.class);
        if (old == null || old.releases() == null || old.operations() == null)
            throw new IOException("旧升级状态文件格式无效");
        String hash = oldBytes == null ? null : sha256(oldBytes);
        write(() -> {
            for (var release : old.releases()) insertRelease(release);
            for (var operation : old.operations()) {
                insertOperation(operation);
                for (var event : operation.events()) insertEvent(operation.id(), event);
            }
            try (var insert = db.prepareStatement("INSERT INTO store_metadata (id, legacy_sha256) VALUES (1, ?)")) {
                insert.setString(1, hash);
                insert.executeUpdate();
            }
        });
        archiveLegacyFile(legacy, archive, hash);
    }

    private void archiveLegacyFile(Path legacy, Path archive, String importedHash) throws IOException {
        if (!Files.exists(legacy)) return;
        if (Files.exists(archive)) throw new IOException("旧状态归档已存在；请人工核实");
        if (importedHash == null || !importedHash.equals(sha256(Files.readAllBytes(legacy))))
            throw new IOException("旧 state.json 与已导入数据库的内容不一致；请人工核实");
        Files.move(legacy, archive, StandardCopyOption.ATOMIC_MOVE);
    }

    private Snapshot readSnapshot() throws SQLException {
        var releases = new ArrayList<Release>();
        try (var query = db.prepareStatement("SELECT id, run_id, commit_sha, sha256, bytes, staged_at FROM releases ORDER BY position");
             var rows = query.executeQuery()) {
            while (rows.next()) releases.add(new Release(rows.getString(1), rows.getLong(2), rows.getString(3),
                    rows.getString(4), rows.getLong(5), Instant.parse(rows.getString(6))));
        }
        Map<String, List<Event>> events = new HashMap<>();
        try (var query = db.prepareStatement("SELECT operation_id, at_time, stage, message FROM operation_events ORDER BY position");
             var rows = query.executeQuery()) {
            while (rows.next()) events.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                    .add(new Event(Instant.parse(rows.getString(2)), rows.getString(3), rows.getString(4)));
        }
        var operations = new ArrayList<Operation>();
        try (var query = db.prepareStatement("""
                SELECT id, release_id, status, stage, backup_id, message, started_at, updated_at
                FROM operations ORDER BY position""");
             var rows = query.executeQuery()) {
            while (rows.next()) operations.add(new Operation(rows.getString(1), rows.getString(2),
                    rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                    Instant.parse(rows.getString(7)), Instant.parse(rows.getString(8)),
                    List.copyOf(events.getOrDefault(rows.getString(1), List.of()))));
        }
        return new Snapshot(List.copyOf(releases), List.copyOf(operations));
    }

    private void insertRelease(Release release) throws SQLException {
        try (var insert = db.prepareStatement("""
                INSERT INTO releases (id, run_id, commit_sha, sha256, bytes, staged_at)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            insert.setString(1, release.id());
            insert.setLong(2, release.runId());
            insert.setString(3, release.commit());
            insert.setString(4, release.sha256());
            insert.setLong(5, release.bytes());
            insert.setString(6, release.stagedAt().toString());
            insert.executeUpdate();
        }
    }

    private void insertOperation(Operation operation) throws SQLException {
        try (var insert = db.prepareStatement("""
                INSERT INTO operations (id, release_id, status, stage, backup_id, message, started_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            insert.setString(1, operation.id());
            insert.setString(2, operation.releaseId());
            insert.setString(3, operation.status());
            insert.setString(4, operation.stage());
            insert.setString(5, operation.backupId());
            insert.setString(6, operation.message());
            insert.setString(7, operation.startedAt().toString());
            insert.setString(8, operation.updatedAt().toString());
            insert.executeUpdate();
        }
    }

    private void updateOperation(Operation operation) throws SQLException {
        try (var update = db.prepareStatement("""
                UPDATE operations SET status = ?, stage = ?, backup_id = ?, message = ?, updated_at = ?
                WHERE id = ?""")) {
            update.setString(1, operation.status());
            update.setString(2, operation.stage());
            update.setString(3, operation.backupId());
            update.setString(4, operation.message());
            update.setString(5, operation.updatedAt().toString());
            update.setString(6, operation.id());
            if (update.executeUpdate() != 1) throw new SQLException("升级操作不存在：" + operation.id());
        }
    }

    private void insertEvent(String operationId, Event event) throws SQLException {
        try (var insert = db.prepareStatement("""
                INSERT INTO operation_events (operation_id, at_time, stage, message) VALUES (?, ?, ?, ?)""")) {
            insert.setString(1, operationId);
            insert.setString(2, event.at().toString());
            insert.setString(3, event.stage());
            insert.setString(4, event.message());
            insert.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SqlAction { void run() throws SQLException; }

    private void write(SqlAction action) throws IOException {
        try {
            action.run();
            db.commit();
        } catch (SQLException | RuntimeException failure) {
            try { db.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            if (failure instanceof SQLException sql) throw new IOException("升级状态写入失败", sql);
            throw (RuntimeException) failure;
        }
    }
    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 不可用", exception);
        }
    }

    @PreDestroy
    void close() throws IOException {
        try {
            db.close();
        } catch (SQLException failure) {
            throw new IOException("关闭升级状态数据库失败", failure);
        } finally {
            try { processLock.release(); } finally { lockChannel.close(); }
        }
    }
}