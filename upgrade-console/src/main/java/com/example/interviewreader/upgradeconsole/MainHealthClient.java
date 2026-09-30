package com.example.interviewreader.upgradeconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MainHealthClient {
    public record Snapshot(Instant checkedAt, JsonNode overall, JsonNode liveness, JsonNode readiness,
                           JsonNode drain, boolean eligible, List<String> blockers) {}

    private final UpgradeSettings settings;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public Snapshot snapshot() {
        var overall = get("/actuator/health", false);
        var liveness = get("/actuator/health/liveness", false);
        var readiness = get("/actuator/health/readiness", false);
        var drain = get("/internal/upgrade/status", true);
        var blockers = new ArrayList<String>();
        if (!up(overall)) blockers.add("主应用总体健康检查未通过");
        if (!up(liveness)) blockers.add("主应用存活检查未通过");
        if (!up(readiness)) blockers.add("主应用就绪检查未通过");
        if (!up(readiness.path("components").path("db"))) blockers.add("数据库健康检查未通过");
        if (!up(readiness.path("components").path("diskSpace"))) blockers.add("磁盘空间健康检查未通过");
        if (!drain.has("maintenance")) blockers.add("主应用升级控制接口不可用");
        else if (!settings.marker().toAbsolutePath().normalize().toString().equals(drain.path("maintenanceFile").asText())) blockers.add("主应用维护门禁路径与控制台不一致");
        if (!settings.dataDir().toAbsolutePath().normalize().toString().equals(drain.path("storageDir").asText())) blockers.add("主应用文件存储根与备份配置不一致");
        if (!settings.dbName().equals(drain.path("databaseName").asText())) blockers.add("主应用数据库名与备份配置不一致");
        if (drain.path("databaseServerId").asText().isBlank() || drain.path("databaseServerId").asText().equals("unknown")) blockers.add("主应用数据库实例身份不可确认");
        else if (drain.path("maintenance").asBoolean()) blockers.add("主应用已有维护门禁");
        return new Snapshot(Instant.now(), overall, liveness, readiness, drain, blockers.isEmpty(), blockers);
    }

    public JsonNode drain() { return get("/internal/upgrade/status", true); }

    private JsonNode get(String path, boolean internal) {
        try {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + settings.mainPort() + path))
                    .timeout(Duration.ofSeconds(3)).GET();
            if (internal) builder.header("X-Upgrade-Token", settings.internalToken());
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 503) return json.createObjectNode();
            return json.readTree(response.body());
        } catch (Exception ignored) {
            return json.createObjectNode();
        }
    }

    private boolean up(JsonNode node) { return "UP".equals(node.path("status").asText()); }
}
