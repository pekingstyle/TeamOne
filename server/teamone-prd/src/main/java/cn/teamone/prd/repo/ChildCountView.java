package cn.teamone.prd.repo;

import java.util.UUID;

/**
 * 需求行子任务统计视图（GET /work-items 列表 type=requirement 行投影，
 * native GROUP BY parent_id 一次 IN 聚合，列表 size≤200 防 N+1）。
 *
 * <p>children 口径：parent_id 命中且 type ∈ task/test_task/defect；
 * done 集与 {@link cn.teamone.prd.domain.WorkItem#doneStatusesOf} 字节级同源
 * （task done·closed / test_task passed / defect 已修复·回归通过·已关闭）。
 * 别名与 getter 一一对应（Spring Data native 接口投影按列别名绑定）。</p>
 */
public interface ChildCountView {

    /** 父工作项 id（type=requirement 行） */
    UUID getParentId();

    /** 子任务总数（无子行的 requirement 不出现在结果里，装配处兜 0） */
    long getTaskCount();

    /** 已完结子任务数 */
    long getTaskDoneCount();
}
