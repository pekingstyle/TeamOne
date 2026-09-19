package cn.teamone.prd.repo;

import java.util.UUID;

/**
 * RoadMap 条目进度聚合投影（查询侧 COUNT FILTER，M2-INC-1 W1）。
 *
 * <p>口径照抄 05 §6.1 mv_goal_progress：叶子工作项 type IN (task/test_task/defect)，
 * 完成数 = status IN ('done','closed','回归通过','已关闭')。
 * 红线条 ①：进度一律查询侧聚合，不落冗余列（物化视图留 M3）。</p>
 *
 * <p>R-5 AC③ · 直连口径补 goalId：条目行 = 条目自身 goal_id；哨兵行（id=NULL，
 * 直挂目标的工作项）= 所属目标 goal_id（rollupAll 按目标分组后 timeline goal
 * 视角目标级合计用）。goalId 不参与计数口径。</p>
 */
public interface RoadmapAggView {

    /** roadmap_item.id（哨兵行 = NULL，消费处按 id 是否为 NULL 分流） */
    UUID getId();

    String getName();

    /** 所属目标（条目行 = 条目 goal_id；哨兵行 = 直挂工作项的 goal_id） */
    UUID getGoalId();

    /** 子树工作项总数（count(wi.id)，含未完成） */
    long getTotal();

    /** 已完成数（count(*) FILTER，别名列映射 getDoneCount） */
    long getDoneCount();
}
