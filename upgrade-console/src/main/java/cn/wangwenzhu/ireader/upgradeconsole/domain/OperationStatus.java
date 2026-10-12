package cn.wangwenzhu.ireader.upgradeconsole.domain;

/**
 * 升级操作结果在接口传输和数据库存储中使用的稳定编码。
 */
public enum OperationStatus {
    RUNNING, SUCCEEDED, FAILED, ROLLED_BACK, NEEDS_OPERATOR, RESTORED
}