package cn.wangwenzhu.ireader.upgradeconsole;

import cn.wangwenzhu.ireader.upgradeconsole.application.GithubArtifactVerifier;
import cn.wangwenzhu.ireader.upgradeconsole.infrastructure.github.GithubActionsClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubArtifactVerifierTest {
    @Test
    void acceptsTheWorkflowPathReturnedByGithubActions() {
        var repository = "yuxuefenfei/interview-reader-blueprint";
        var run = new GithubActionsClient.WorkflowRun("success", "completed", "push", "main",
                "verify", ".github/workflows/verify.yml", "a".repeat(40), repository);
        assertThatCode(() -> GithubArtifactVerifier.requireTrustedMainRun(run, repository))
                .doesNotThrowAnyException();
        var wrongWorkflow = new GithubActionsClient.WorkflowRun("success", "completed", "push", "main",
                "verify", ".github/workflows/release.yml", run.commit(), repository);
        assertThatThrownBy(() -> GithubArtifactVerifier.requireTrustedMainRun(wrongWorkflow, repository))
                .isInstanceOf(IllegalArgumentException.class);
    }
}