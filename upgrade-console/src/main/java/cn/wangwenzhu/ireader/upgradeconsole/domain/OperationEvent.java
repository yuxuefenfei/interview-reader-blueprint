package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.time.Instant;

public record OperationEvent(Instant at, String stage, String message) {
}