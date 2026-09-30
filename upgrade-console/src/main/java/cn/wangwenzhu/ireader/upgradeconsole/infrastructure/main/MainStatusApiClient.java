package cn.wangwenzhu.ireader.upgradeconsole.infrastructure.main;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Only this client knows the local main-app URLs and internal-token header.
 */
@Component
public class MainStatusApiClient {
    private final UpgradeSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public MainStatusApiClient(UpgradeSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public Health overall() {
        return health("/actuator/health");
    }

    public Health liveness() {
        return health("/actuator/health/liveness");
    }

    public Health readiness() {
        return health("/actuator/health/readiness");
    }

    public Runtime runtime() {
        var body = get("/internal/upgrade/status", true);
        return new Runtime(body.has("maintenance") ? body.path("maintenance").asBoolean() : null,
                body.path("maintenanceFile").asText(), body.path("storageDir").asText(),
                body.path("databaseName").asText(), body.path("databaseServerId").asText(), body);
    }

    private Health health(String path) {
        var body = get(path, false);
        return new Health(body.path("status").asText(), body);
    }

    private JsonNode get(String path, boolean internal) {
        try {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + settings.mainPort() + path))
                    .timeout(Duration.ofSeconds(3)).GET();
            if (internal) builder.header("X-Upgrade-Token", settings.internalToken());
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 503)
                return json.createObjectNode();
            return json.readTree(response.body());
        } catch (Exception ignored) {
            // A missing or restarting main process is an unavailable health result.
            return json.createObjectNode();
        }
    }

    public record Health(String status, JsonNode body) {
        public boolean up() {
            return "UP".equals(status);
        }

        public boolean componentUp(String name) {
            return "UP".equals(body.path("components").path(name).path("status").asText());
        }
    }

    public record Runtime(Boolean maintenance, String maintenanceFile, String storageDir,
                          String databaseName, String databaseServerId, JsonNode body) {
    }
}