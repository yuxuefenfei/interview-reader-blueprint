package cn.wangwenzhu.ireader.upgradeconsole.domain;

/**
 * Stable wire and database codes for every forward, rollback and recovery phase.
 */
public enum UpgradeStage {
    PRECHECKING, STOP_WRITES, DRAINING, BACKING_UP, STOPPING, SWITCHING, STARTING,
    READINESS, OPENING, SUCCEEDED, ROLLING_BACK, RESTORING_DB, RESTORING_FILES,
    STARTING_OLD, ROLLED_BACK, FAILED, INTERRUPTED, GATED_FAILURE, ROLLBACK_FAILED,
    RECOVERING, RECOVERY_FAILED, POST_OPEN_ERROR, ABORTING, RESTORED
}