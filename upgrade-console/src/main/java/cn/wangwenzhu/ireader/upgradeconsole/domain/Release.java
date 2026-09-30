package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.time.Instant;

public record Release(String id, long runId, String commit, String sha256, long bytes, Instant stagedAt) {
}