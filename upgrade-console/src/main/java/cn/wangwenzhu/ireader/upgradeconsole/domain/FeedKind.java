package cn.wangwenzhu.ireader.upgradeconsole.domain;

/**
 * Distinguishes workflow decisions from command output in the operation log.
 */
public enum FeedKind {
    STAGE, COMMAND_STDOUT, COMMAND_STDERR
}