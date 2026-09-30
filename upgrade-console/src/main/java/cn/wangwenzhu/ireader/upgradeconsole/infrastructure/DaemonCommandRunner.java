package cn.wangwenzhu.ireader.upgradeconsole.infrastructure;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import cn.wangwenzhu.ireader.upgradeconsole.domain.FeedKind;
import cn.wangwenzhu.ireader.upgradeconsole.domain.UpgradeStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Streams each daemon invocation to H2 while retaining the existing diagnostic files.
 */
@Component
@RequiredArgsConstructor
public class DaemonCommandRunner {
    private static final int MAX_LOG_LINES = 2_000;
    private static final int MAX_LOG_LINE_CHARS = 4_096;
    private final UpgradeSettings settings;
    private final UpgradeStateRepository store;

    public void run(String operationId, Action action) throws Exception {
        var process = new ProcessBuilder(settings.daemon().toString(), action.argument).start();
        var output = settings.stateDir().resolve(operationId + "-daemon.log");
        var error = settings.stateDir().resolve(operationId + "-daemon-error.log");
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdout = readers.submit(() -> collect(operationId, process.getInputStream(), output, FeedKind.COMMAND_STDOUT));
            var stderr = readers.submit(() -> collect(operationId, process.getErrorStream(), error, FeedKind.COMMAND_STDERR));
            if (!process.waitFor(50, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("主应用启停命令超时");
            }
            var outputFailure = stdout.get();
            var errorFailure = stderr.get();
            if (outputFailure != null) throw outputFailure;
            if (errorFailure != null) throw errorFailure;
            if (process.exitValue() != 0) throw new IOException("主应用启停命令失败");
        }
    }

    private IOException collect(String operationId, InputStream stream, Path file, FeedKind kind) {
        IOException failure = null;
        try (var lines = new BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
             var saved = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            int count = 0;
            String line;
            while ((line = lines.readLine()) != null) {
                if (count == MAX_LOG_LINES) {
                    var marker = "命令输出超过 " + MAX_LOG_LINES + " 行，后续内容已截断";
                    saved.write(marker);
                    saved.newLine();
                    try {
                        store.appendCommandLog(operationId, kind, marker);
                    } catch (IOException persistFailure) {
                        failure = persistFailure;
                    }
                }
                if (count++ >= MAX_LOG_LINES) continue;
                var entry = line.length() > MAX_LOG_LINE_CHARS
                        ? line.substring(0, MAX_LOG_LINE_CHARS) + "…[截断]" : line;
                saved.write(entry);
                saved.newLine();
                saved.flush();
                if (failure == null) {
                    try {
                        store.appendCommandLog(operationId, kind, entry);
                    } catch (IOException persistFailure) {
                        failure = persistFailure;
                    }
                }
            }
        } catch (IOException readFailure) {
            if (failure == null) failure = readFailure;
            else failure.addSuppressed(readFailure);
        }
        return failure;
    }

    public enum Action {
        START("start"), STOP_GRACEFULLY("stop-gracefully");
        private final String argument;

        Action(String argument) {
            this.argument = argument;
        }
    }
}