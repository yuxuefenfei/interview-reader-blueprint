package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.util.List;

public record UpgradeSnapshot(List<Release> releases, List<UpgradeOperation> operations) {
}