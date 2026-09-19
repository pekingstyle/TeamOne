package cn.teamone.prd.domain.transition;

import cn.teamone.prd.domain.WorkItem;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四状态机转移表纯逻辑单测（W2-B6）：四类型全部合法转移逐条 + 每类型至少 2 条非法转移
 * 断言抛 T1-PRD-4201（422）。
 */
class TransitionTableTest {

    // ==================== task ====================

    @Test
    void task_legal_transitions_all() {
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_TASK,
                WorkItem.STATUS_TODO, WorkItem.STATUS_IN_PROGRESS)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_TASK,
                WorkItem.STATUS_IN_PROGRESS, WorkItem.STATUS_DONE)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_TASK,
                WorkItem.STATUS_DONE, WorkItem.STATUS_IN_PROGRESS)).isTrue();
    }

    @Test
    void task_illegal_transitions_throw_4201() {
        assertIllegal(WorkItem.TYPE_TASK, WorkItem.STATUS_TODO, WorkItem.STATUS_DONE); // 跳段
        assertIllegal(WorkItem.TYPE_TASK, WorkItem.STATUS_DONE, WorkItem.STATUS_TODO); // 回起点
    }

    // ==================== test_task ====================

    @Test
    void test_task_legal_transitions_all() {
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_TEST_TASK,
                WorkItem.STATUS_PENDING, WorkItem.STATUS_IN_PROGRESS)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_TEST_TASK,
                WorkItem.STATUS_IN_PROGRESS, WorkItem.STATUS_PASSED)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_TEST_TASK,
                WorkItem.STATUS_IN_PROGRESS, WorkItem.STATUS_FAILED)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_TEST_TASK,
                WorkItem.STATUS_FAILED, WorkItem.STATUS_IN_PROGRESS)).isTrue();
    }

    @Test
    void test_task_illegal_transitions_throw_4201() {
        assertIllegal(WorkItem.TYPE_TEST_TASK, WorkItem.STATUS_PENDING, WorkItem.STATUS_PASSED); // 跳过执行
        assertIllegal(WorkItem.TYPE_TEST_TASK, WorkItem.STATUS_PASSED, WorkItem.STATUS_FAILED); // 终态回退
    }

    // ==================== defect ====================

    @Test
    void defect_legal_transitions_all() {
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_DEFECT,
                WorkItem.STATUS_DEFECT_NEW, WorkItem.STATUS_DEFECT_FIXING)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_DEFECT,
                WorkItem.STATUS_DEFECT_FIXING, WorkItem.STATUS_DEFECT_FIXED)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_DEFECT,
                WorkItem.STATUS_DEFECT_FIXED, WorkItem.STATUS_DEFECT_REGRESSION_PASSED)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_DEFECT,
                WorkItem.STATUS_DEFECT_REGRESSION_PASSED, WorkItem.STATUS_DEFECT_CLOSED)).isTrue();
        // M-N2：回归失败边——已修复 → 重新打开（「重新打开」唯一入边）
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_DEFECT,
                WorkItem.STATUS_DEFECT_FIXED, WorkItem.STATUS_DEFECT_REOPENED)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_DEFECT,
                WorkItem.STATUS_DEFECT_REOPENED, WorkItem.STATUS_DEFECT_FIXING)).isTrue(); // 重开后回修复
    }

    @Test
    void defect_illegal_transitions_throw_4201() {
        assertIllegal(WorkItem.TYPE_DEFECT, WorkItem.STATUS_DEFECT_NEW, WorkItem.STATUS_DEFECT_CLOSED); // 直跳关闭
        assertIllegal(WorkItem.TYPE_DEFECT, WorkItem.STATUS_DEFECT_FIXED, WorkItem.STATUS_DEFECT_CLOSED); // 跳过回归
    }

    // ==================== requirement ====================

    @Test
    void requirement_legal_transitions_all() {
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_REQUIREMENT,
                WorkItem.STATUS_REQ_ACCEPTED, WorkItem.STATUS_REQ_IN_DEV)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_REQUIREMENT,
                WorkItem.STATUS_REQ_IN_DEV, WorkItem.STATUS_REQ_DELIVERED)).isTrue();
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_REQUIREMENT,
                WorkItem.STATUS_REQ_DELIVERED, WorkItem.STATUS_REQ_CLOSED)).isTrue();
    }

    @Test
    void requirement_illegal_transitions_throw_4201() {
        assertIllegal(WorkItem.TYPE_REQUIREMENT, WorkItem.STATUS_REQ_ACCEPTED, WorkItem.STATUS_REQ_CLOSED); // 跳段
        assertIllegal(WorkItem.TYPE_REQUIREMENT, WorkItem.STATUS_REQ_DELIVERED, WorkItem.STATUS_REQ_IN_DEV); // 回退
    }

    // ==================== 边界 ====================

    @Test
    void unknown_state_and_null_are_illegal() {
        assertThat(TransitionTable.canTransition(WorkItem.TYPE_DEFECT, "不存在的状态",
                WorkItem.STATUS_DEFECT_FIXING)).isFalse();
        assertThat(TransitionTable.canTransition("unknown_type",
                WorkItem.STATUS_TODO, WorkItem.STATUS_DONE)).isFalse();
        assertThatThrownBy(() -> TransitionTable.check(WorkItem.TYPE_DEFECT, null,
                        WorkItem.STATUS_DEFECT_FIXING))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo("T1-PRD-4201");
    }

    private static void assertIllegal(String type, String from, String to) {
        assertThat(TransitionTable.canTransition(type, from, to))
                .as("非法转移 %s: %s → %s", type, from, to)
                .isFalse();
        assertThatThrownBy(() -> TransitionTable.check(type, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo("T1-PRD-4201");
    }
}
