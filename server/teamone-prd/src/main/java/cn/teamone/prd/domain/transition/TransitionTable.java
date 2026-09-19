package cn.teamone.prd.domain.transition;

import cn.teamone.prd.domain.WorkItem;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;

import java.util.Map;
import java.util.Set;

import static cn.teamone.prd.domain.WorkItem.TYPE_DEFECT;
import static cn.teamone.prd.domain.WorkItem.TYPE_REQUIREMENT;
import static cn.teamone.prd.domain.WorkItem.TYPE_TASK;
import static cn.teamone.prd.domain.WorkItem.TYPE_TEST_TASK;

/**
 * 四状态机转移表（05 架构文档 §6.1 与原型 store.ts 对齐；纯 Java 静态 Map，可单测）。
 *
 * <pre>
 * task:        todo → in_progress → done；done → in_progress（回退）
 * test_task:   pending → in_progress → passed / failed；failed → in_progress
 * defect:      新建 → 修复中 → 已修复 → 回归通过 → 已关闭；回归失败 → 重新打开 → 修复中
 *              （M-N2：已修复 → 重新打开 是「重新打开」的唯一入边，无此边状态不可达）
 * requirement: accepted → in_dev → delivered → closed
 *              （draft→pending_review 由 submit 动作处理；pending_review→accepted/draft
 *               由 review 动作处理；rejected 非持久状态——不进转移表）
 * </pre>
 *
 * <p>DB 侧类型感知复合 CHECK ck_work_item_type_status 双保险（值字节级一致）；
 * 状态流转只走 {@code TransitionService.transition}（唯一入口），非法流转抛
 * {@link ErrorCode#PRD_4201}（422）。</p>
 */
public final class TransitionTable {

    /** type → (from → 允许的 to 集合) */
    private static final Map<String, Map<String, Set<String>>> TABLE = build();

    private TransitionTable() {
    }

    private static Map<String, Map<String, Set<String>>> build() {
        Map<String, Map<String, Set<String>>> table = Map.of(
                TYPE_TASK,
                Map.of(
                        WorkItem.STATUS_TODO, Set.of(WorkItem.STATUS_IN_PROGRESS),
                        WorkItem.STATUS_IN_PROGRESS, Set.of(WorkItem.STATUS_DONE),
                        WorkItem.STATUS_DONE, Set.of(WorkItem.STATUS_IN_PROGRESS)),
                TYPE_TEST_TASK,
                Map.of(
                        WorkItem.STATUS_PENDING, Set.of(WorkItem.STATUS_IN_PROGRESS),
                        WorkItem.STATUS_IN_PROGRESS, Set.of(WorkItem.STATUS_PASSED, WorkItem.STATUS_FAILED),
                        WorkItem.STATUS_FAILED, Set.of(WorkItem.STATUS_IN_PROGRESS)),
                TYPE_DEFECT,
                Map.of(
                        WorkItem.STATUS_DEFECT_NEW, Set.of(WorkItem.STATUS_DEFECT_FIXING),
                        WorkItem.STATUS_DEFECT_FIXING, Set.of(WorkItem.STATUS_DEFECT_FIXED),
                        // M-N2：回归失败 → 重新打开（否则重新打开状态不可达，05 §6.1）
                        WorkItem.STATUS_DEFECT_FIXED, Set.of(
                                WorkItem.STATUS_DEFECT_REGRESSION_PASSED, WorkItem.STATUS_DEFECT_REOPENED),
                        WorkItem.STATUS_DEFECT_REGRESSION_PASSED, Set.of(WorkItem.STATUS_DEFECT_CLOSED),
                        WorkItem.STATUS_DEFECT_REOPENED, Set.of(WorkItem.STATUS_DEFECT_FIXING)),
                TYPE_REQUIREMENT,
                Map.of(
                        WorkItem.STATUS_REQ_ACCEPTED, Set.of(WorkItem.STATUS_REQ_IN_DEV),
                        WorkItem.STATUS_REQ_IN_DEV, Set.of(WorkItem.STATUS_REQ_DELIVERED),
                        WorkItem.STATUS_REQ_DELIVERED, Set.of(WorkItem.STATUS_REQ_CLOSED)));
        return Map.copyOf(table);
    }

    /** 是否允许该流转（未知状态/类型/null 一律 false，不可变表拒绝 null 键） */
    public static boolean canTransition(String type, String from, String to) {
        if (type == null || from == null || to == null) {
            return false;
        }
        Map<String, Set<String>> row = TABLE.get(type);
        return row != null && row.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 校验流转合法性，非法抛 T1-PRD-4201（422）。
     *
     * @throws BusinessException 状态不允许该流转
     */
    public static void check(String type, String from, String to) {
        if (!canTransition(type, from, to)) {
            throw new BusinessException(ErrorCode.PRD_4201,
                    "状态不允许该流转: " + type + " " + from + " → " + to,
                    java.util.List.of("type=" + type, "from=" + from, "to=" + to));
        }
    }
}
