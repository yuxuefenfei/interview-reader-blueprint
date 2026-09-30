package cn.wangwenzhu.ireader.upgradeconsole.domain;

/**
 * Stable wire and database codes for an upgrade operation result.
 */
public enum OperationStatus {
    RUNNING, SUCCEEDED, FAILED, ROLLED_BACK, NEEDS_OPERATOR, RESTORED
}