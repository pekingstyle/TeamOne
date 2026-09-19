package cn.teamone.prd.repo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 目标概览条目聚合投影（GET /goals/overview，COUNT/SUM FILTER 查询侧聚合，红线条 ①）。
 *
 * <p>total/doneCount 与 {@link RoadmapAggView} 同口径（done 含 test_task 'passed'，恒限
 * task/test_task/defect 三类型，requirement 不进完成率）；三个 hours 桶 = estimate_hours
 * 按状态三桶 SUM FILTER——done（task done / defect 已修复·回归通过·已关闭 / test_task passed）、
 * in_progress（进行中类）、todo（其余）。</p>
 *
 * <p>B3 · R-1（裁决 D8 桑基末端=类型计数）新增：taskCount/requirementCount/defectCount
 * 及各自 done 数——test_task 并入任务桶（taskCount/taskDone），done 集按类型感知
 * （task done·closed / test_task passed / requirement delivered·closed /
 * defect 已修复·回归通过·已关闭，与 WorkItem.doneStatusesOf 同源）；
 * releaseId = 条目正向挂接版本（报表下钻 条目→版本→迭代 链用）。</p>
 */
public interface GoalOverviewAggView {

    /** roadmap_item.id */
    UUID getId();

    String getName();

    /** 条目正向挂接版本（release_id，可为 null） */
    UUID getReleaseId();

    /** 三类型（task/test_task/defect）工作项总数（含未完成） */
    long getTotal();

    /** 已完成数（count(*) FILTER，别名列映射 getDoneCount） */
    long getDoneCount();

    /** 完成桶工时合计（SUM FILTER，COALESCE 0；numeric(6,1) 求和保持 1 位小数） */
    BigDecimal getDoneHours();

    /** 进行中桶工时合计 */
    BigDecimal getInProgressHours();

    /** 待办桶工时合计 */
    BigDecimal getTodoHours();

    /** 任务桶计数（task + test_task） */
    long getTaskCount();

    /** 任务桶已完成数（task done·closed + test_task passed） */
    long getTaskDone();

    /** 需求桶计数（type = requirement） */
    long getRequirementCount();

    /** 需求桶已完成数（delivered/closed） */
    long getRequirementDone();

    /** 缺陷桶计数（type = defect） */
    long getDefectCount();

    /** 缺陷桶已完成数（已修复/回归通过/已关闭） */
    long getDefectDone();
}
