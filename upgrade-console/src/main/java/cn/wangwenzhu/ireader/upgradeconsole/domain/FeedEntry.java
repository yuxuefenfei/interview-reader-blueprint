package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.time.Instant;

public record FeedEntry(long id, String operationId, Instant at, FeedKind kind, String stage, String message) {
}