package com.example.interviewreader.upgradeconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
public class GithubArtifactVerifier {
    public record Verified(String commit, String sha256, long bytes) {}

    private final UpgradeSettings settings;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiRoot;

    public GithubArtifactVerifier(UpgradeSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        if (!settings.githubOwner().matches("[A-Za-z0-9-]+")
                || !settings.githubRepo().matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("GitHub 仓库名无效");
        }
        this.apiRoot = "https://api.github.com/repos/" + settings.githubOwner() + "/"
                + settings.githubRepo() + "/actions";
    }

    public Verified verify(Path jar, long runId) throws Exception {
        if (runId <= 0) throw new IllegalArgumentException("构建运行 ID 无效");
        var size = Files.size(jar);
        if (size < 100_000 || size > 150_000_000) throw new IllegalArgumentException("JAR 大小不符合要求");
        checkExecutableJar(jar);
        var run = apiJson(apiRoot + "/runs/" + runId);
        if (!"success".equals(run.path("conclusion").asText())
                || !"completed".equals(run.path("status").asText())
                || !"push".equals(run.path("event").asText())
                || !"main".equals(run.path("head_branch").asText())
                || !"verify".equals(run.path("name").asText())
                || !run.path("path").asText().startsWith(".github/workflows/verify.yml@")
                || !(settings.githubOwner() + "/" + settings.githubRepo()).equals(
                        run.path("head_repository").path("full_name").asText())) {
            throw new IllegalArgumentException("只接受 main 分支成功完成的 verify 构建");
        }
        var commit = run.path("head_sha").asText();
        if (!commit.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("构建提交信息缺失");
        var artifacts = apiJson(apiRoot + "/runs/" + runId + "/artifacts?per_page=100").path("artifacts");
        JsonNode checksumArtifact = null;
        for (var artifact : artifacts) {
            if (artifact.path("name").asText().equals("production-jar-sha256")
                    && !artifact.path("expired").asBoolean(true)
                    && artifact.path("workflow_run").path("id").asLong() == runId
                    && commit.equals(artifact.path("workflow_run").path("head_sha").asText())) {
                if (checksumArtifact != null) throw new IllegalArgumentException("构建校验产物不唯一");
                checksumArtifact = artifact;
            }
        }
        if (checksumArtifact == null) throw new IllegalArgumentException("构建校验产物不存在或已过期");
        var expected = readChecksum(checksumArtifact.path("id").asLong());
        var actual = sha256(jar);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("上传 JAR 与 GitHub Actions 校验值不一致");
        }
        return new Verified(commit, actual, size);
    }

    private void checkExecutableJar(Path jar) throws IOException {
        try (var archive = new JarFile(jar.toFile())) {
            var manifest = archive.getManifest();
            if (manifest == null || !"org.springframework.boot.loader.launch.JarLauncher".equals(
                    manifest.getMainAttributes().getValue("Main-Class"))
                    || archive.getEntry("BOOT-INF/classes/com/example/interviewreader/InterviewReaderApplication.class") == null) {
                throw new IllegalArgumentException("不是可执行的 Interview Reader JAR");
            }
        }
    }

    private JsonNode apiJson(String url) throws Exception {
        var request = apiRequest(url).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("GitHub API 查询失败：HTTP " + response.statusCode());
        try (var body = response.body()) {
            var bytes = body.readNBytes(1_000_001);
            if (bytes.length > 1_000_000) throw new IOException("GitHub API 响应过大");
            return json.readTree(bytes);
        }
    }

    private String readChecksum(long artifactId) throws Exception {
        if (artifactId <= 0) throw new IOException("GitHub 产物 ID 无效");
        var response = client.send(apiRequest(apiRoot + "/artifacts/" + artifactId + "/zip").build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 302) throw new IOException("GitHub 产物下载失败");
        var redirect = response.headers().firstValue("Location").orElseThrow();
        var uri = URI.create(redirect);
        if (!"https".equals(uri.getScheme())) throw new IOException("GitHub 产物跳转地址无效");
        var download = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (download.statusCode() != 200) throw new IOException("GitHub 产物下载失败");
        byte[] bytes;
        try (var body = download.body()) {
            bytes = body.readNBytes(100_001);
        }
        if (bytes.length > 100_000) throw new IOException("校验产物过大");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            if (entry == null || !entry.getName().equals("production-jar.sha256")) {
                throw new IOException("校验产物内容不符合要求");
            }
            var content = new String(zip.readNBytes(200), StandardCharsets.US_ASCII).trim();
            if (zip.getNextEntry() != null || !content.matches("[a-f0-9]{64}  interview-reader\\.jar")) {
                throw new IOException("校验产物格式不符合要求");
            }
            return content.substring(0, 64);
        }
    }

    private HttpRequest.Builder apiRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + settings.githubToken())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "interview-reader-upgrade-console").GET();
    }

    private String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
