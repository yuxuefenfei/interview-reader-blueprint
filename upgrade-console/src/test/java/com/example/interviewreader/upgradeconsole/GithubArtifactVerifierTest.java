package com.example.interviewreader.upgradeconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubArtifactVerifierTest {
    @Test
    void acceptsTheWorkflowPathReturnedByGithubActions() throws Exception {
        var run = new ObjectMapper().readTree("""
                {
                  "conclusion": "success",
                  "status": "completed",
                  "event": "push",
                  "head_branch": "main",
                  "name": "verify",
                  "path": ".github/workflows/verify.yml",
                  "head_repository": {"full_name": "yuxuefenfei/interview-reader-blueprint"}
                }
                """);
        var repository = "yuxuefenfei/interview-reader-blueprint";

        assertThatCode(() -> GithubArtifactVerifier.requireTrustedMainRun(run, repository))
                .doesNotThrowAnyException();

        ((ObjectNode) run).put("path", ".github/workflows/release.yml");
        assertThatThrownBy(() -> GithubArtifactVerifier.requireTrustedMainRun(run, repository))
                .isInstanceOf(IllegalArgumentException.class);
    }
}