package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.time.Instant;
import java.util.List;

public record UpgradeOperation(String id, String releaseId, String status, String stage, String backupId,
                               String message, Instant startedAt, Instant updatedAt, List<OperationEvent> events) {
}