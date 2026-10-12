package cn.wangwenzhu.ireader.upgradeconsole.application;

import cn.wangwenzhu.ireader.upgradeconsole.domain.FeedEntry;
import cn.wangwenzhu.ireader.upgradeconsole.domain.UpgradeStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 提供大小受限的日志分页，以及用于 SSE 重连的持久化游标。
 */
@Service
@RequiredArgsConstructor
public class OperationFeedService {
    private static final int PAGE_SIZE = 100;
    private final UpgradeStateRepository store;

    public long cursor() throws IOException {
        return store.lastFeedId();
    }

    public List<FeedEntry> after(long cursor) throws IOException {
        return store.feedAfter(cursor, PAGE_SIZE);
    }

    public FeedPage latest(String operationId, long beforeId) throws IOException {
        var entries = store.operationFeed(operationId, beforeId, PAGE_SIZE + 1);
        if (entries.size() <= PAGE_SIZE) return new FeedPage(entries, null);
        var page = List.copyOf(entries.subList(1, entries.size()));
        return new FeedPage(page, page.getFirst().id());
    }

    public record FeedPage(List<FeedEntry> entries, Long nextBeforeId) {
    }
}