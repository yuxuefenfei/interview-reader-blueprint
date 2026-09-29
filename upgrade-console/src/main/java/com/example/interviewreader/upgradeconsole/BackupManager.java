package com.example.interviewreader.upgradeconsole;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class BackupManager {
    private final UpgradeSettings settings;
    private final StateStore store;
    private final ObjectMapper json;

    public BackupManager(UpgradeSettings settings, StateStore store, ObjectMapper json) {
        this.settings = settings;
        this.store = store;
        this.json = json;
    }

    public void precheck() throws IOException {
        var data = settings.dataDir().toAbsolutePath().normalize();
        var state = settings.stateDir().toAbsolutePath().normalize();
        if (state.startsWith(data) || data.startsWith(state)) throw new IOException("备份目录与应用数据目录不能相互包含");
        if (!Files.isRegularFile(settings.jar()) || !Files.isExecutable(settings.daemon())
                || !Files.isDirectory(settings.dataDir()) || !Files.isWritable(settings.appDir())
                || !Files.isExecutable(settings.mysqlBin()) || !Files.isExecutable(settings.mysqldumpBin())) {
            throw new IOException("应用目录、数据目录、脚本或数据库客户端不可用");
        }
        if (Files.isSymbolicLink(settings.jar()) || Files.isSymbolicLink(settings.dataDir())
                || Files.isSymbolicLink(settings.appDir()) || Files.isSymbolicLink(settings.dbDefaultsFile())) {
            throw new IOException("升级路径不能是符号链接");
        }
        var bytes = size(settings.dataDir());
        var free = Files.getFileStore(settings.stateDir()).getUsableSpace();
        if (free < bytes + 2_000_000_000L) throw new IOException("备份目标空间不足");
        if (Files.getFileStore(settings.dataDir()).getUsableSpace() < bytes + 500_000_000L)
            throw new IOException("数据目录所在磁盘缺少恢复暂存空间");
    }

    public void verifyDatabaseIdentity(JsonNode mainStatus) throws Exception {
        var expectedName = mainStatus.path("databaseName").asText();
        var expectedServer = mainStatus.path("databaseServerId").asText();
        if (!settings.dbName().equals(expectedName) || expectedServer.isBlank() || expectedServer.equals("unknown"))
            throw new IOException("主应用数据库身份不可确认或库名与备份配置不一致");
        var command = new ProcessBuilder(settings.mysqlBin().toString(),
                "--defaults-extra-file=" + settings.dbDefaultsFile(), "--connect-timeout=5",
                "--batch", "--skip-column-names", "--database=" + settings.dbName(),
                "--execute=SELECT DATABASE(), @@server_uuid");
        command.redirectError(settings.stateDir().resolve("mysql-precheck-error.log").toFile());
        var process = command.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("备份数据库身份检查超时");
        }
        if (process.exitValue() != 0) throw new IOException("备份数据库连接失败");
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readNBytes(200), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        var fields = output.split("\\t", 2);
        if (fields.length != 2 || !fields[0].equals(expectedName) || !fields[1].equals(expectedServer))
            throw new IOException("备份连接与主应用不在同一数据库实例");
    }

    public Path create(String operationId) throws Exception {
        var root = store.backupPath(operationId);
        Files.createDirectories(root);
        var dbDump = root.resolve("database.sql");
        run(Duration.ofMinutes(30), dbDump, root.resolve("database-error.log"),
                settings.mysqldumpBin().toString(),
                "--defaults-extra-file=" + settings.dbDefaultsFile(),
                "--single-transaction", "--quick", "--routines", "--triggers", "--events",
                "--add-drop-database", "--databases", settings.dbName());
        if (Files.size(dbDump) == 0) throw new IOException("数据库备份为空");
        copyTree(settings.dataDir(), root.resolve("data"));
        Files.copy(settings.jar(), root.resolve("interview-reader.jar"));
        var env = settings.appDir().resolve("conf/application.env");
        if (Files.isRegularFile(env)) {
            Files.createDirectories(root.resolve("conf"));
            Files.copy(env, root.resolve("conf/application.env"));
        }
        var hashes = new LinkedHashMap<String, String>();
        try (var walk = Files.walk(root)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals("database-error.log")) continue;
                hashes.put(root.relativize(file).toString().replace('\\', '/'), hash(file));
            }
        }
        json.writeValue(root.resolve("manifest.json").toFile(), hashes);
        return root;
    }

    public void verify(String backupId) throws Exception {
        var root = store.backupPath(backupId);
        var manifest = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) throw new IOException("备份清单不存在");
        Map<String, String> hashes = json.readValue(manifest.toFile(), new TypeReference<>() {});
        if (!hashes.containsKey("database.sql") || !hashes.containsKey("interview-reader.jar")) {
            throw new IOException("备份缺少数据库或旧 JAR");
        }
        for (var entry : hashes.entrySet()) {
            var file = root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)
                    || !hash(file).equals(entry.getValue())) throw new IOException("备份校验失败");
        }
    }

    public void restoreDatabase(String backupId) throws Exception {
        verify(backupId);
        var root = store.backupPath(backupId);
        var builder = new ProcessBuilder(settings.mysqlBin().toString(),
                "--defaults-extra-file=" + settings.dbDefaultsFile(), "--default-character-set=utf8mb4", "--batch");
        builder.redirectInput(root.resolve("database.sql").toFile());
        builder.redirectOutput(root.resolve("restore-output.log").toFile());
        builder.redirectError(root.resolve("restore-error.log").toFile());
        waitFor(builder.start(), Duration.ofMinutes(30));
    }

    public void restoreFilesAndJar(String backupId) throws Exception {
        var root = store.backupPath(backupId);
        var data = settings.dataDir().toAbsolutePath().normalize();
        var suffix = java.util.UUID.randomUUID().toString();
        var stagedData = data.resolveSibling(data.getFileName() + ".restore-" + suffix);
        var failedData = data.resolveSibling(data.getFileName() + ".failed-" + suffix);
        copyTree(root.resolve("data"), stagedData);
        if (Files.exists(data)) {
            if (Files.isSymbolicLink(data)) throw new IOException("当前数据目录是符号链接");
            Files.move(data, failedData, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(stagedData, data, StandardCopyOption.ATOMIC_MOVE);
        var stagedJar = settings.appDir().resolve("interview-reader.jar.restore-" + backupId);
        Files.copy(root.resolve("interview-reader.jar"), stagedJar);
        Files.move(stagedJar, settings.jar(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("数据目录包含符号链接");
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("数据目录包含非普通文件");
                Files.copy(file, to.resolve(from.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private long size(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(file -> {
                try { return Files.size(file); } catch (IOException exception) { throw new IllegalStateException(exception); }
            }).sum();
        }
    }

    private String hash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void run(Duration timeout, Path output, Path error, String... command) throws Exception {
        var builder = new ProcessBuilder(command);
        builder.redirectOutput(output.toFile());
        builder.redirectError(error.toFile());
        waitFor(builder.start(), timeout);
    }

    private void waitFor(Process process, Duration timeout) throws Exception {
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("数据库命令超时");
        }
        if (process.exitValue() != 0) throw new IOException("数据库命令失败");
    }
}
