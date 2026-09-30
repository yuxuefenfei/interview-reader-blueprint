package cn.wangwenzhu.ireader.upgradeconsole.application;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import cn.wangwenzhu.ireader.upgradeconsole.domain.OperationStatus;
import cn.wangwenzhu.ireader.upgradeconsole.domain.UpgradeOperation;
import cn.wangwenzhu.ireader.upgradeconsole.domain.UpgradeStage;
import cn.wangwenzhu.ireader.upgradeconsole.domain.UpgradeStateRepository;
import cn.wangwenzhu.ireader.upgradeconsole.infrastructure.BackupManager;
import cn.wangwenzhu.ireader.upgradeconsole.infrastructure.DaemonCommandRunner;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class DeploymentCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentCoordinator.class);
    private static final String HEALTH_UP = "UP";
    private final UpgradeSettings settings;
    private final UpgradeStateRepository store;
    private final MainHealthService health;
    private final BackupManager backup;
    private final DaemonCommandRunner daemon;
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();

    public UpgradeOperation deploy(String releaseId) throws IOException {
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("已有操作正在执行");
        try {
            var operation = store.begin(releaseId);
            executor.submit(() -> {
                try { execute(operation.id()); }
                finally { busy.set(false); }
            });
            return operation;
        } catch (RuntimeException | IOException exception) {
            busy.set(false);
            throw exception;
        }
    }

    public UpgradeOperation recover(String operationId) throws IOException {
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("已有操作正在执行");
        try {
            var operation = store.operation(operationId);
            if (!operation.status().equals(OperationStatus.NEEDS_OPERATOR.name()) || operation.backupId() == null
                    || !Files.isRegularFile(settings.marker())) {
                throw new IllegalStateException("仅允许恢复保留维护门禁且有备份的中断操作");
            }
            store.update(operationId, OperationStatus.RUNNING, UpgradeStage.RECOVERING, "人工发起备份恢复");
            executor.submit(() -> {
                try { rollback(operationId); }
                catch (Exception exception) {
                    LOG.error("Manual recovery failed operation={}", operationId, exception);
                    safeUpdate(operationId, OperationStatus.NEEDS_OPERATOR, UpgradeStage.RECOVERY_FAILED, "恢复失败；保留停写状态，请查看服务端日志");
                } finally { busy.set(false); }
            });
            return store.operation(operationId);
        } catch (RuntimeException | IOException exception) {
            busy.set(false);
            throw exception;
        }
    }

    public UpgradeOperation abortInterrupted(String operationId) throws IOException {
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("已有操作正在执行");
        try {
            var operation = store.operation(operationId);
            if (!operation.status().equals(OperationStatus.NEEDS_OPERATOR.name()) || operation.backupId() != null)
                throw new IllegalStateException("仅允许中断于备份完成前的操作恢复原服务");
            store.update(operationId, OperationStatus.RUNNING, UpgradeStage.ABORTING, "检查旧版并恢复写入");
            executor.submit(() -> {
                try {
                    var status = health.snapshot();
                    if (!HEALTH_UP.equals(status.overall().path("status").asText())
                            || !HEALTH_UP.equals(status.readiness().path("status").asText()))
                        throw new IOException("旧版健康状态不可确认");
                    waitForDrain();
                    openGate(operationId);
                    store.update(operationId, OperationStatus.FAILED, UpgradeStage.FAILED, "已确认旧版健康并恢复写入");
                } catch (Exception exception) {
                    LOG.error("Abort interrupted operation failed operation={}", operationId, exception);
                    safeUpdate(operationId, OperationStatus.NEEDS_OPERATOR, UpgradeStage.GATED_FAILURE, "无法安全开放写入；保留维护门禁");
                } finally { busy.set(false); }
            });
            return store.operation(operationId);
        } catch (RuntimeException | IOException exception) {
            busy.set(false);
            throw exception;
        }
    }

    public UpgradeOperation restorePublished(String backupId) throws IOException {
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("已有操作正在执行");
        try {
            var operation = store.beginRecovery(backupId);
            executor.submit(() -> {
                try { executePublishedRestore(operation.id(), backupId); }
                finally { busy.set(false); }
            });
            return operation;
        } catch (RuntimeException | IOException exception) {
            busy.set(false);
            throw exception;
        }
    }

    private void executePublishedRestore(String id, String backupId) {
        boolean gated = false;
        boolean stopped = false;
        boolean opened = false;
        try {
            var live = health.snapshot();
            if (!live.eligible()) throw new IOException("当前健康检查未通过");
            backup.precheck();
            backup.verifyDatabaseIdentity(live.drain());
            backup.verify(backupId);
            stage(id, UpgradeStage.STOP_WRITES, "人工恢复：关闭写入");
            closeGate(id);
            gated = true;
            stage(id, UpgradeStage.DRAINING, "人工恢复：等待任务排空");
            waitForDrain();
            stage(id, UpgradeStage.BACKING_UP, "人工恢复：先保存当前状态");
            backup.create(id);
            stage(id, UpgradeStage.STOPPING, "人工恢复：优雅停止当前进程");
            daemon.run(id, DaemonCommandRunner.Action.STOP_GRACEFULLY);
            waitStopped();
            stopped = true;
            restoreSnapshot(id, backupId);
            openGate(id);
            opened = true;
            store.update(id, OperationStatus.RESTORED, UpgradeStage.RESTORED, "已恢复选定备份；当前状态另存为备份 " + id);
        } catch (Exception exception) {
            LOG.error("Published recovery failed operation={}", id, exception);
            if (opened) {
                safeUpdate(id, OperationStatus.RESTORED, UpgradeStage.RESTORED, "目标备份已恢复且写入已开放；请核实操作日志");
                return;
            }
            if (stopped) {
                try {
                    daemon.run(id, DaemonCommandRunner.Action.STOP_GRACEFULLY);
                    waitStopped();
                    restoreSnapshot(id, id);
                    openGate(id);
                    safeUpdate(id, OperationStatus.ROLLED_BACK, UpgradeStage.ROLLED_BACK, "恢复目标失败；已恢复操作前状态");
                    return;
                } catch (Exception fallbackFailure) {
                    LOG.error("Recovery fallback failed operation={}", id, fallbackFailure);
                    safeUpdate(id, OperationStatus.NEEDS_OPERATOR, UpgradeStage.RECOVERY_FAILED, "恢复失败；保留停写状态，请人工核实");
                    return;
                }
            }
            if (gated) {
                try {
                    if (!HEALTH_UP.equals(health.snapshot().readiness().path("status").asText()))
                        throw new IOException("当前版本就绪状态不可确认");
                    openGate(id);
                    safeUpdate(id, OperationStatus.FAILED, UpgradeStage.FAILED, "人工恢复未执行；当前版本写入已恢复");
                } catch (Exception failure) {
                    safeUpdate(id, OperationStatus.NEEDS_OPERATOR, UpgradeStage.GATED_FAILURE, "人工恢复中止；保留停写状态");
                }
            } else safeUpdate(id, OperationStatus.FAILED, UpgradeStage.FAILED, "人工恢复预检失败；未改动主应用");
        }
    }
    private void execute(String id) {
        boolean gated = false;
        boolean stopped = false;
        boolean opened = false;
        try {
            var release = store.release(store.operation(id).releaseId());
            var live = health.snapshot();
            if (!live.eligible()) throw new IOException("健康预检未通过：" + String.join("；", live.blockers()));
            backup.precheck();
            backup.verifyDatabaseIdentity(live.drain());
            if (!Files.isRegularFile(store.releasePath(release.id()))) throw new IOException("已验证 JAR 不存在");
            stage(id, UpgradeStage.STOP_WRITES, "关闭主应用写入入口");
            closeGate(id);
            gated = true;
            stage(id, UpgradeStage.DRAINING, "等待请求和后台任务排空");
            waitForDrain();
            stage(id, UpgradeStage.BACKING_UP, "备份数据库、数据文件和旧 JAR");
            backup.create(id);
            store.setBackup(id);
            stage(id, UpgradeStage.STOPPING, "优雅停止主进程");
            daemon.run(id, DaemonCommandRunner.Action.STOP_GRACEFULLY);
            waitStopped();
            stopped = true;
            stage(id, UpgradeStage.SWITCHING, "原子切换已核验 JAR");
            var staged = settings.appDir().resolve("interview-reader.jar.next-" + id);
            Files.copy(store.releasePath(release.id()), staged);
            Files.move(staged, settings.jar(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            stage(id, UpgradeStage.STARTING, "启动新版本并运行数据库迁移");
            daemon.run(id, DaemonCommandRunner.Action.START);
            stage(id, UpgradeStage.READINESS, "等待新版本存活、数据库、磁盘和就绪检查");
            waitReady(true, release.commit());
            stage(id, UpgradeStage.OPENING, "验证通过，恢复写入");
            openGate(id);
            opened = true;
            store.update(id, OperationStatus.SUCCEEDED, UpgradeStage.SUCCEEDED, "升级完成；写入已恢复");
        } catch (Exception exception) {
            LOG.error("Deployment failed operation={}", id, exception);
            if (opened) {
                safeUpdate(id, OperationStatus.NEEDS_OPERATOR, UpgradeStage.POST_OPEN_ERROR, "写入已恢复但状态记录失败；请人工核实");
                return;
            }
            if (stopped) {
                try { rollback(id); }
                catch (Exception recoveryFailure) {
                    LOG.error("Automatic rollback failed operation={}", id, recoveryFailure);
                    safeUpdate(id, OperationStatus.NEEDS_OPERATOR, UpgradeStage.ROLLBACK_FAILED, "自动回滚失败；保留停写状态，请查看服务端日志");
                }
                return;
            }
            if (gated) {
                try {
                    if (!HEALTH_UP.equals(health.snapshot().readiness().path("status").asText())) {
                        throw new IOException("旧版就绪检查未通过");
                    }
                    openGate(id);
                    safeUpdate(id, OperationStatus.FAILED, UpgradeStage.FAILED, "升级中止；旧版仍在运行，写入已恢复");
                } catch (Exception releaseFailure) {
                    LOG.error("Could not reopen old service operation={}", id, releaseFailure);
                    safeUpdate(id, OperationStatus.NEEDS_OPERATOR, UpgradeStage.GATED_FAILURE, "升级中止；保留停写状态，请人工核实");
                }
            } else {
                safeUpdate(id, OperationStatus.FAILED, UpgradeStage.FAILED, "升级预检失败；主应用未切换");
            }
        }
    }

    private void rollback(String id) throws Exception {
        stage(id, UpgradeStage.ROLLING_BACK, "停止新版本并校验同批次备份");
        if (!Files.isRegularFile(settings.marker())) throw new IOException("维护门禁已丢失，无法自动恢复数据库");
        daemon.run(id, DaemonCommandRunner.Action.STOP_GRACEFULLY);
        waitStopped();
        var backupId = store.operation(id).backupId();
        if (backupId == null) throw new IOException("备份未完成");
        restoreSnapshot(id, backupId);
        openGate(id);
        store.update(id, OperationStatus.ROLLED_BACK, UpgradeStage.ROLLED_BACK, "旧版、数据库和文件已恢复，写入已开放");
    }

    private void restoreSnapshot(String id, String backupId) throws Exception {
        backup.verify(backupId);
        stage(id, UpgradeStage.RESTORING_DB, "恢复数据库备份");
        backup.restoreDatabase(backupId);
        stage(id, UpgradeStage.RESTORING_FILES, "恢复文件和旧 JAR");
        backup.restoreFilesAndJar(backupId);
        stage(id, UpgradeStage.STARTING_OLD, "启动匹配版本并等待就绪");
        daemon.run(id, DaemonCommandRunner.Action.START);
        waitReady(true, null);
    }

    private void closeGate(String id) throws IOException {
        var marker = settings.marker();
        if (Files.exists(marker)) throw new IOException("维护门禁已存在");
        var temp = marker.resolveSibling("upgrade.maintenance." + id + ".tmp");
        Files.writeString(temp, id + "\n", StandardCharsets.UTF_8);
        Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE);
    }

    private void openGate(String id) throws IOException {
        var marker = settings.marker();
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)
                || !Files.readString(marker).trim().equals(id)) throw new IOException("维护门禁标识不匹配");
        Files.delete(marker);
    }

    private void waitForDrain() throws Exception {
        var deadline = Instant.now().plus(Duration.ofMinutes(3));
        var consecutive = 0;
        while (Instant.now().isBefore(deadline)) {
            JsonNode state = health.drain();
            if (!state.path("maintenance").asBoolean(false)) throw new IOException("主应用维护状态不可确认");
            if (state.path("activeWrites").asInt(-1) == 0 && state.path("importJobs").asInt(-1) == 0
                    && state.path("deletionJobs").asInt(-1) == 0) {
                if (++consecutive >= 2) return;
            } else consecutive = 0;
            Thread.sleep(1000);
        }
        throw new IOException("写入任务排空超时");
    }

    private void waitReady(boolean gated, String expectedCommit) throws Exception {
        var deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(deadline)) {
            var status = health.snapshot();
            if (HEALTH_UP.equals(status.overall().path("status").asText())
                    && HEALTH_UP.equals(status.liveness().path("status").asText())
                    && HEALTH_UP.equals(status.readiness().path("status").asText())
                    && HEALTH_UP.equals(status.readiness().path("components").path("db").path("status").asText())
                    && HEALTH_UP.equals(status.readiness().path("components").path("diskSpace").path("status").asText())
                    && status.drain().path("maintenance").asBoolean() == gated
                    && (expectedCommit == null || expectedCommit.equals(status.drain().path("releaseCommit").asText()))) return;
            Thread.sleep(2000);
        }
        throw new IOException("主应用就绪检查超时");
    }

    private void waitStopped() throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", settings.mainPort()), 1000);
            } catch (IOException expected) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IOException("主应用端口仍被占用");
    }

    private void stage(String id, UpgradeStage stage, String message) throws IOException {
        store.update(id, OperationStatus.RUNNING, stage, message);
    }

    private void safeUpdate(String id, OperationStatus status, UpgradeStage stage, String message) {
        try { store.update(id, status, stage, message); }
        catch (IOException exception) { LOG.error("Could not persist operation={}", id, exception); }
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }
}
