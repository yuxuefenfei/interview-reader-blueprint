package cn.wangwenzhu.ireader.upgradeconsole;

import cn.wangwenzhu.ireader.upgradeconsole.domain.OperationStatus;
import cn.wangwenzhu.ireader.upgradeconsole.domain.UpgradeFlow;
import cn.wangwenzhu.ireader.upgradeconsole.domain.UpgradeStage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpgradeFlowTest {
    @Test
    void permitsForwardProgressAndRecoveryButRejectsSkippedOrTerminalTransitions() {
        assertThatCode(() -> UpgradeFlow.requireTransition("RUNNING", "PRECHECKING",
                OperationStatus.RUNNING, UpgradeStage.STOP_WRITES)).doesNotThrowAnyException();
        assertThatCode(() -> UpgradeFlow.requireTransition("RUNNING", "STARTING_OLD",
                OperationStatus.ROLLED_BACK, UpgradeStage.ROLLED_BACK)).doesNotThrowAnyException();
        assertThatCode(() -> UpgradeFlow.requireTransition("NEEDS_OPERATOR", "INTERRUPTED",
                OperationStatus.RUNNING, UpgradeStage.RECOVERING)).doesNotThrowAnyException();
        assertThatThrownBy(() -> UpgradeFlow.requireTransition("RUNNING", "PRECHECKING",
                OperationStatus.RUNNING, UpgradeStage.SWITCHING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UpgradeFlow.requireTransition("SUCCEEDED", "SUCCEEDED",
                OperationStatus.RUNNING, UpgradeStage.PRECHECKING)).isInstanceOf(IllegalStateException.class);
    }
}