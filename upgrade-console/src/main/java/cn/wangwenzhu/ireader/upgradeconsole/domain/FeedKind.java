package cn.wangwenzhu.ireader.upgradeconsole.domain;

/**
 * 在操作日志中区分流程决策和命令输出。
 */
public enum FeedKind {
    STAGE, COMMAND_STDOUT, COMMAND_STDERR
}