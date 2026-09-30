package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The application uses this port for durable upgrade state and operation logs.
 */
public interface UpgradeStateRepository {
    UpgradeSnapshot snapshot();

    Release addRelease(long runId, String commit, String hash, long bytes, Path staged) throws IOException;

    Release release(String id);

    PageResult<Release> releases(String query, int page, int size) throws IOException;

    PageResult<UpgradeOperation> operations(String releaseId, int page, int size) throws IOException;

    PageResult<UpgradeOperation> operations(int page, int size) throws IOException;

    UpgradeOperation begin(String releaseId) throws IOException;

    UpgradeOperation beginRecovery(String backupId) throws IOException;

    UpgradeOperation operation(String id);

    UpgradeOperation latestOperation();

    UpgradeOperation update(String id, OperationStatus status, UpgradeStage stage, String message) throws IOException;

    void setBackup(String id) throws IOException;

    Path releasePath(String id);

    Path backupPath(String id);

    long lastFeedId() throws IOException;

    List<FeedEntry> feedAfter(long afterId, int limit) throws IOException;

    List<FeedEntry> operationFeed(String operationId, long beforeId, int limit) throws IOException;

    void appendCommandLog(String operationId, FeedKind kind, String message) throws IOException;
}