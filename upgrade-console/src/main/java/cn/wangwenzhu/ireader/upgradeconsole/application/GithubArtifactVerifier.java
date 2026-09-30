package cn.wangwenzhu.ireader.upgradeconsole.application;

import cn.wangwenzhu.ireader.upgradeconsole.config.UpgradeSettings;
import cn.wangwenzhu.ireader.upgradeconsole.infrastructure.github.GithubActionsClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;

/**
 * Verifies a local JAR against one successful, trusted main-branch build.
 */
@Component
@RequiredArgsConstructor
public class GithubArtifactVerifier {
    private static final long MIN_JAR_BYTES = 100_000;
    private static final long MAX_JAR_BYTES = 150_000_000;
    private static final String CHECKSUM_ARTIFACT = "production-jar-sha256";
    private static final String TRUSTED_BRANCH = "main";
    private static final String TRUSTED_WORKFLOW = "verify";
    private static final String TRUSTED_WORKFLOW_PATH = ".github/workflows/verify.yml";
    private static final String SUCCESS_CONCLUSION = "success";
    private static final String COMPLETED_STATUS = "completed";
    private static final String PUSH_EVENT = "push";
    private static final String BOOT_MAIN_CLASS = "org.springframework.boot.loader.launch.JarLauncher";
    private static final String APPLICATION_CLASS =
            "BOOT-INF/classes/cn/wangwenzhu/ireader/InterviewReaderApplication.class";
    // Previously verified releases may still contain the pre-rename entry point.
    private static final String LEGACY_APPLICATION_CLASS =
            "BOOT-INF/classes/com/example/interviewreader/InterviewReaderApplication.class";
    private final UpgradeSettings settings;
    private final GithubActionsClient github;

    public static void requireTrustedMainRun(GithubActionsClient.WorkflowRun run, String repository) {
        if (!SUCCESS_CONCLUSION.equals(run.conclusion()) || !COMPLETED_STATUS.equals(run.status())
                || !PUSH_EVENT.equals(run.event()) || !TRUSTED_BRANCH.equals(run.branch())
                || !TRUSTED_WORKFLOW.equals(run.name()) || !TRUSTED_WORKFLOW_PATH.equals(run.path())
                || !repository.equals(run.repository())) {
            throw new IllegalArgumentException("只接受 main 分支成功完成的 verify 构建");
        }
    }

    public Verified verify(Path jar, long runId) throws Exception {
        if (runId <= 0) throw new IllegalArgumentException("构建运行 ID 无效");
        var size = Files.size(jar);
        if (size < MIN_JAR_BYTES || size > MAX_JAR_BYTES)
            throw new IllegalArgumentException("JAR 大小不符合要求");
        checkExecutableJar(jar);
        var run = github.run(runId);
        requireTrustedMainRun(run, settings.githubOwner() + "/" + settings.githubRepo());
        var commit = run.commit();
        if (!commit.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("构建提交信息缺失");
        Long checksumArtifactId = null;
        for (var artifact : github.artifacts(runId)) {
            if (CHECKSUM_ARTIFACT.equals(artifact.name()) && !artifact.expired()
                    && artifact.runId() == runId && commit.equals(artifact.commit())) {
                if (checksumArtifactId != null) throw new IllegalArgumentException("构建校验产物不唯一");
                checksumArtifactId = artifact.id();
            }
        }
        if (checksumArtifactId == null) throw new IllegalArgumentException("构建校验产物不存在或已过期");
        var expected = github.checksum(checksumArtifactId);
        var actual = sha256(jar);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("上传 JAR 与 GitHub Actions 校验值不一致");
        }
        return new Verified(commit, actual, size);
    }

    private void checkExecutableJar(Path jar) throws IOException {
        try (var archive = new JarFile(jar.toFile())) {
            var manifest = archive.getManifest();
            if (manifest == null || !BOOT_MAIN_CLASS.equals(
                    manifest.getMainAttributes().getValue("Main-Class"))
                    || (archive.getEntry(APPLICATION_CLASS) == null
                    && archive.getEntry(LEGACY_APPLICATION_CLASS) == null)) {
                throw new IllegalArgumentException("不是可执行的 Interview Reader JAR");
            }
        }
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

    public record Verified(String commit, String sha256, long bytes) {
    }
}