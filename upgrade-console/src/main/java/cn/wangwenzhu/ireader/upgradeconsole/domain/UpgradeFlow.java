package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps persisted upgrade transitions explicit. Error exits are allowed from every
 * running phase because the maintenance gate must take precedence over workflow order.
 */
public final class UpgradeFlow {
    private static final Map<UpgradeStage, Set<UpgradeStage>> NEXT = new EnumMap<>(UpgradeStage.class);

    static {
        edge(UpgradeStage.PRECHECKING, UpgradeStage.STOP_WRITES);
        edge(UpgradeStage.STOP_WRITES, UpgradeStage.DRAINING);
        edge(UpgradeStage.DRAINING, UpgradeStage.BACKING_UP);
        edge(UpgradeStage.BACKING_UP, UpgradeStage.STOPPING);
        edge(UpgradeStage.STOPPING, UpgradeStage.SWITCHING, UpgradeStage.RESTORING_DB);
        edge(UpgradeStage.SWITCHING, UpgradeStage.STARTING);
        edge(UpgradeStage.STARTING, UpgradeStage.READINESS);
        edge(UpgradeStage.READINESS, UpgradeStage.OPENING);
        edge(UpgradeStage.RECOVERING, UpgradeStage.ROLLING_BACK);
        edge(UpgradeStage.ROLLING_BACK, UpgradeStage.RESTORING_DB);
        edge(UpgradeStage.RESTORING_DB, UpgradeStage.RESTORING_FILES, UpgradeStage.RESTORING_DB);
        edge(UpgradeStage.RESTORING_FILES, UpgradeStage.STARTING_OLD, UpgradeStage.RESTORING_DB);
        edge(UpgradeStage.STARTING_OLD, UpgradeStage.RESTORING_DB);
    }

    private UpgradeFlow() {
    }

    public static void requireTransition(String oldStatusCode, String oldStageCode,
                                         OperationStatus nextStatus, UpgradeStage nextStage) {
        var oldStatus = OperationStatus.valueOf(oldStatusCode);
        var oldStage = UpgradeStage.valueOf(oldStageCode);
        boolean allowed;
        if (oldStatus == OperationStatus.NEEDS_OPERATOR) {
            allowed = nextStatus == OperationStatus.RUNNING
                    && (nextStage == UpgradeStage.RECOVERING || nextStage == UpgradeStage.ABORTING);
        } else if (oldStatus != OperationStatus.RUNNING) {
            allowed = false;
        } else if (nextStatus == OperationStatus.RUNNING) {
            allowed = NEXT.getOrDefault(oldStage, Set.of()).contains(nextStage)
                    || nextStage == UpgradeStage.ROLLING_BACK;
        } else {
            allowed = switch (nextStatus) {
                case SUCCEEDED -> oldStage == UpgradeStage.OPENING && nextStage == UpgradeStage.SUCCEEDED;
                case RESTORED -> oldStage == UpgradeStage.STARTING_OLD && nextStage == UpgradeStage.RESTORED;
                case ROLLED_BACK -> oldStage == UpgradeStage.STARTING_OLD && nextStage == UpgradeStage.ROLLED_BACK;
                case FAILED -> nextStage == UpgradeStage.FAILED;
                case NEEDS_OPERATOR -> EnumSet.of(UpgradeStage.INTERRUPTED, UpgradeStage.GATED_FAILURE,
                        UpgradeStage.ROLLBACK_FAILED, UpgradeStage.RECOVERY_FAILED,
                        UpgradeStage.POST_OPEN_ERROR).contains(nextStage);
                case RUNNING -> false;
            };
        }
        if (!allowed) throw new IllegalStateException("非法升级状态转换：" + oldStatus + "/" + oldStage
                + " -> " + nextStatus + "/" + nextStage);
    }

    private static void edge(UpgradeStage from, UpgradeStage... next) {
        NEXT.put(from, EnumSet.copyOf(java.util.List.of(next)));
    }
}