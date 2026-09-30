package cn.wangwenzhu.ireader.upgradeconsole.infrastructure.github;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

/**
 * Owns GitHub HTTP details; release trust decisions belong to the verifier.
 */
@Component
public class GithubActionsClient {
    private static final String API_VERSION = "2022-11-28";
    private static final int MAX_JSON_BYTES = 1_000_000;
    private final UpgradeSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiRoot;

    public GithubActionsClient(UpgradeSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        if (!settings.githubOwner().matches("[A-Za-z0-9-]+")
                || !settings.githubRepo().matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("GitHub 仓库名无效");
        }
        apiRoot = "https://api.github.com/repos/" + settings.githubOwner() + "/"
                + settings.githubRepo() + "/actions";
    }

    public WorkflowRun run(long runId) throws Exception {
        var node = apiJson(apiRoot + "/runs/" + runId);
        return new WorkflowRun(node.path("conclusion").asText(), node.path("status").asText(),
                node.path("event").asText(), node.path("head_branch").asText(), node.path("name").asText(),
                node.path("path").asText(), node.path("head_sha").asText(),
                node.path("head_repository").path("full_name").asText());
    }

    public List<Artifact> artifacts(long runId) throws Exception {
        var result = new ArrayList<Artifact>();
        for (var node : apiJson(apiRoot + "/runs/" + runId + "/artifacts?per_page=100").path("artifacts")) {
            result.add(new Artifact(node.path("id").asLong(), node.path("name").asText(),
                    node.path("expired").asBoolean(true), node.path("workflow_run").path("id").asLong(),
                    node.path("workflow_run").path("head_sha").asText()));
        }
        return result;
    }

    public String checksum(long artifactId) throws Exception {
        if (artifactId <= 0) throw new IOException("GitHub 产物 ID 无效");
        var response = http.send(apiRequest(apiRoot + "/artifacts/" + artifactId + "/zip").build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 302) throw new IOException("GitHub 产物下载失败");
        var redirect = URI.create(response.headers().firstValue("Location").orElseThrow());
        if (!"https".equals(redirect.getScheme())) throw new IOException("GitHub 产物跳转地址无效");
        var download = http.send(HttpRequest.newBuilder(redirect).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (download.statusCode() != 200) throw new IOException("GitHub 产物下载失败");
        byte[] bytes;
        try (var input = download.body()) {
            bytes = input.readNBytes(100_001);
        }
        if (bytes.length > 100_000) throw new IOException("校验产物过大");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            if (entry == null || !entry.getName().equals("production-jar.sha256"))
                throw new IOException("校验产物内容不符合要求");
            var content = new String(zip.readNBytes(200), StandardCharsets.US_ASCII).trim();
            if (zip.getNextEntry() != null || !content.matches("[a-f0-9]{64} {2}interview-reader\\.jar"))
                throw new IOException("校验产物格式不符合要求");
            return content.substring(0, 64);
        }
    }

    private JsonNode apiJson(String url) throws Exception {
        var response = http.send(apiRequest(url).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("GitHub API 查询失败：HTTP " + response.statusCode());
        try (var body = response.body()) {
            var bytes = body.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("GitHub API 响应过大");
            return json.readTree(bytes);
        }
    }

    private HttpRequest.Builder apiRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + settings.githubToken())
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "interview-reader-upgrade-console").GET();
    }

    public record WorkflowRun(String conclusion, String status, String event, String branch,
                              String name, String path, String commit, String repository) {
    }

    public record Artifact(long id, String name, boolean expired, long runId, String commit) {
    }
}