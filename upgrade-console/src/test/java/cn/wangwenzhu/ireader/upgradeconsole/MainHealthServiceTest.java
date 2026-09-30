package cn.wangwenzhu.ireader.upgradeconsole;

import cn.wangwenzhu.ireader.upgradeconsole.application.MainHealthService;
import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import cn.wangwenzhu.ireader.upgradeconsole.infrastructure.main.MainStatusApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MainHealthServiceTest {
    @TempDir Path directory;

    @Test
    void healthGateRequiresDatabaseAndMatchingStorageConfiguration() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var readiness = new AtomicReference<>("{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"},\"diskSpace\":{\"status\":\"UP\"}}}");
        var settings = new UpgradeSettings(directory, directory.resolve("data"), directory.resolve("state"),
                directory.resolve("mysql.cnf"), "db", directory.resolve("mysql"), directory.resolve("mysqldump"),
                "owner", "repo", "token", "admin", "password", "https://upgrade.example.com",
                "01234567890123456789012345678901", server.getAddress().getPort());
        server.createContext("/actuator/health/readiness", exchange -> reply(exchange, readiness.get()));
        server.createContext("/actuator/health/liveness", exchange -> reply(exchange, "{\"status\":\"UP\"}"));
        server.createContext("/actuator/health", exchange -> reply(exchange, "{\"status\":\"UP\"}"));
        var control = new ObjectMapper().createObjectNode()
                .put("maintenance", false)
                .put("maintenanceFile", settings.marker().toAbsolutePath().normalize().toString())
                .put("storageDir", settings.dataDir().toAbsolutePath().normalize().toString())
                .put("databaseName", "db")
                .put("databaseServerId", "12345678-1234-1234-1234-123456789abc");
        server.createContext("/internal/upgrade/status", exchange -> reply(exchange, control.toString()));
        server.start();
        try {
            var client = new MainHealthService(settings, new MainStatusApiClient(settings, new ObjectMapper()));
            assertThat(client.snapshot().eligible()).isTrue();
            readiness.set("{\"status\":\"DOWN\",\"components\":{\"db\":{\"status\":\"DOWN\"},\"diskSpace\":{\"status\":\"UP\"}}}");
            assertThat(client.snapshot().eligible()).isFalse();
            assertThat(client.snapshot().blockers()).contains("数据库健康检查未通过");
            readiness.set("{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"},\"diskSpace\":{\"status\":\"UP\"}}}");
            control.remove("storageDir");
            assertThat(client.snapshot().blockers()).contains("主应用文件存储根与备份配置不一致");
        } finally {
            server.stop(0);
        }
    }

    private void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
