package cn.wangwenzhu.ireader.upgradeconsole.api.dto;

import cn.wangwenzhu.ireader.upgradeconsole.application.MainHealthService;
import cn.wangwenzhu.ireader.upgradeconsole.application.OperationFeedService;
import cn.wangwenzhu.ireader.upgradeconsole.application.UpgradeConsoleService;
import cn.wangwenzhu.ireader.upgradeconsole.domain.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Wire DTOs preserve the existing JSON fields while hiding domain and H2 types.
 */
public final class ConsoleDtos {
    private ConsoleDtos() {
    }

    public static OverviewResponse from(UpgradeConsoleService.Overview value) {
        return new OverviewResponse(from(value.health()),
                new SnapshotResponse(value.state().releases().stream().map(ConsoleDtos::from).toList(),
                        value.state().operations().stream().map(ConsoleDtos::from).toList()),
                value.feedCursor());
    }

    public static DashboardResponse from(UpgradeConsoleService.Dashboard value) {
        return new DashboardResponse(from(value.health()),
                value.latestOperation() == null ? null : from(value.latestOperation()), value.feedCursor());
    }

    public static PageResponse<ReleaseResponse> releases(PageResult<Release> page) {
        return new PageResponse<>(page.items().stream().map(ConsoleDtos::from).toList(),
                page.page(), page.size(), page.total(), page.hasNext());
    }

    public static PageResponse<OperationResponse> operations(PageResult<UpgradeOperation> page) {
        return new PageResponse<>(page.items().stream().map(ConsoleDtos::from).toList(),
                page.page(), page.size(), page.total(), page.hasNext());
    }

    public static HealthResponse from(MainHealthService.Snapshot value) {
        return new HealthResponse(value.checkedAt(), value.overall(), value.liveness(), value.readiness(),
                value.drain(), value.eligible(), value.blockers());
    }

    public static ReleaseResponse from(Release value) {
        return new ReleaseResponse(value.id(), value.runId(), value.commit(), value.sha256(),
                value.bytes(), value.stagedAt());
    }

    public static OperationResponse from(UpgradeOperation value) {
        return new OperationResponse(value.id(), value.releaseId(), value.status(), value.stage(),
                value.backupId(), value.message(), value.startedAt(), value.updatedAt(),
                value.events().stream().map(ConsoleDtos::from).toList());
    }

    public static EventResponse from(OperationEvent value) {
        return new EventResponse(value.at(), value.stage(), value.message());
    }

    public static FeedEntryResponse from(FeedEntry value) {
        return new FeedEntryResponse(value.id(), value.operationId(), value.at(),
                value.kind().name(), value.stage(), value.message());
    }

    public static FeedPageResponse from(OperationFeedService.FeedPage value) {
        return new FeedPageResponse(value.entries().stream().map(ConsoleDtos::from).toList(), value.nextBeforeId());
    }

    public record ConfirmationRequest(String backupId, boolean confirmDataRestore) {
    }

    public record ReleaseResponse(String id, long runId, String commit, String sha256, long bytes, Instant stagedAt) {
    }

    public record EventResponse(Instant at, String stage, String message) {
    }

    public record OperationResponse(String id, String releaseId, String status, String stage, String backupId,
                                    String message, Instant startedAt, Instant updatedAt, List<EventResponse> events) {
    }

    public record SnapshotResponse(List<ReleaseResponse> releases, List<OperationResponse> operations) {
    }

    public record HealthResponse(Instant checkedAt, JsonNode overall, JsonNode liveness, JsonNode readiness,
                                 JsonNode drain, boolean eligible, List<String> blockers) {
    }

    public record OverviewResponse(HealthResponse health, SnapshotResponse state, long feedCursor) {
    }

    public record DashboardResponse(HealthResponse health, OperationResponse latestOperation, long feedCursor) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total, boolean hasNext) {
    }

    public record FeedEntryResponse(long id, String operationId, Instant at, String kind, String stage,
                                    String message) {
    }

    public record FeedPageResponse(List<FeedEntryResponse> entries, Long nextBeforeId) {
    }
}