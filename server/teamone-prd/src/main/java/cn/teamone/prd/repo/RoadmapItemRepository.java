package cn.teamone.prd.repo;

import cn.teamone.prd.domain.RoadmapItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** RoadMap 条目仓库（表 prd.roadmap_item）。 */
public interface RoadmapItemRepository extends JpaRepository<RoadmapItem, UUID> {

    List<RoadmapItem> findByProductIdOrderByCreatedAtAsc(UUID productId);

    /** 挂某目标的条目（goal 视角时间轴 / rollup 行集合，M2-INC-1 W1） */
    List<RoadmapItem> findByGoalIdOrderByCreatedAtAsc(UUID goalId);

    /**
     * 目标下条目进度聚合（rollup 端点口径，@Query COUNT FILTER 查询侧聚合，红线条 ①）。
     * 完成口径照抄 05 §6.1 mv_goal_progress：type IN (task/test_task/defect)，
     * status IN ('done','closed','已修复','回归通过','已关闭')，并增补 test_task 的 'passed'
     * （M2 增订：测试任务以通过为完成）；与 EfficiencyReportService.isDone 的缺陷完成集一致，
     * 也与 overviewByGoal 的 doneHours 桶恒一致（QA 复审 MUST-FIX 对齐）；别名 "doneCount" 引号保大小写映射投影。
     *
     * <p>R-5 AC③ · work_item 直连口径（docs/v2/12，总监已批准）：工作项与目标承认两条路径——
     * a) 条目路径 wi.roadmap_item_id ∈ 目标条目集合；b) 直挂路径 wi.goal_id 命中且
     * roadmap_item_id IS NULL（需求类工作项 goal 直挂）。裁决：<b>直挂工作项不摊入条目行</b>
     * （LEFT JOIN OR 条件会让直挂项计入每个条目行 → 重复计数），条目行 JOIN 维持
     * wi.roadmap_item_id = ri.id（条目路径计数零回归），直挂部分以 UNION ALL 追加
     * <b>哨兵行</b>（id=NULL，name='直挂目标的工作项'）单独聚合；GoalService 组装处
     * 仅在计数 &gt; 0 时输出哨兵行。UNION 内 ORDER BY 只能引用输出列，故补
     * "createdAt" 列参与排序（哨兵行 createdAt=NULL，ASC 默认 NULLS LAST 排在末尾）。</p>
     */
    @Query(value = "SELECT ri.id AS id, ri.name AS name, ri.goal_id AS \"goalId\", "
            + "count(wi.id) AS total, "
            + "count(*) FILTER (WHERE wi.status IN ('done','closed','已修复','回归通过','已关闭','passed')) AS \"doneCount\", "
            + "ri.created_at AS \"createdAt\" "
            + "FROM prd.roadmap_item ri "
            + "LEFT JOIN prd.work_item wi ON wi.roadmap_item_id = ri.id "
            + "AND wi.type IN ('task','test_task','defect') "
            + "WHERE ri.goal_id = :goalId "
            + "GROUP BY ri.id, ri.name, ri.goal_id, ri.created_at "
            + "UNION ALL "
            + "SELECT NULL::uuid AS id, '直挂目标的工作项' AS name, wi.goal_id AS \"goalId\", "
            + "count(wi.id) AS total, "
            + "count(*) FILTER (WHERE wi.status IN ('done','closed','已修复','回归通过','已关闭','passed')) AS \"doneCount\", "
            + "NULL::timestamptz AS \"createdAt\" "
            + "FROM prd.work_item wi "
            + "WHERE wi.goal_id = :goalId AND wi.roadmap_item_id IS NULL "
            + "AND wi.type IN ('task','test_task','defect') "
            + "GROUP BY wi.goal_id "
            + "ORDER BY \"createdAt\"", nativeQuery = true)
    List<RoadmapAggView> rollupByGoal(@Param("goalId") UUID goalId);

    /**
     * 全表版聚合（timeline 双视角共用，按条目 id 取进度；完成口径与 rollupByGoal 恒一致）。
     *
     * <p>R-5 AC③ 直连口径：与 {@link #rollupByGoal} 同构——条目行 JOIN 维持条目路径
     * （计数零回归），UNION ALL 追加直挂工作项哨兵行并<b>按 wi.goal_id 分组</b>
     * （id=NULL 携带所属 goalId，timeline goal 视角目标级合计用；roadmap_service
     * 消费处按 id 是否为 NULL 分流，条目进度映射不受影响）。无 goal 的工作项
     * （goal_id IS NULL 直挂无主）不属于任何目标，不进哨兵行。</p>
     */
    @Query(value = "SELECT ri.id AS id, ri.name AS name, ri.goal_id AS \"goalId\", "
            + "count(wi.id) AS total, "
            + "count(*) FILTER (WHERE wi.status IN ('done','closed','已修复','回归通过','已关闭','passed')) AS \"doneCount\", "
            + "ri.created_at AS \"createdAt\" "
            + "FROM prd.roadmap_item ri "
            + "LEFT JOIN prd.work_item wi ON wi.roadmap_item_id = ri.id "
            + "AND wi.type IN ('task','test_task','defect') "
            + "GROUP BY ri.id, ri.name, ri.goal_id, ri.created_at "
            + "UNION ALL "
            + "SELECT NULL::uuid AS id, '直挂目标的工作项' AS name, wi.goal_id AS \"goalId\", "
            + "count(wi.id) AS total, "
            + "count(*) FILTER (WHERE wi.status IN ('done','closed','已修复','回归通过','已关闭','passed')) AS \"doneCount\", "
            + "NULL::timestamptz AS \"createdAt\" "
            + "FROM prd.work_item wi "
            + "WHERE wi.goal_id IS NOT NULL AND wi.roadmap_item_id IS NULL "
            + "AND wi.type IN ('task','test_task','defect') "
            + "GROUP BY wi.goal_id "
            + "ORDER BY \"createdAt\"", nativeQuery = true)
    List<RoadmapAggView> rollupAll();

    /**
     * 目标下条目进度+工时三桶+类型计数聚合（GET /goals/overview 口径，@Query COUNT/SUM FILTER 原生写法）。
     *
     * <p>B3 · R-1 桑基末端改「工作项类型计数」（裁决 D8）：join 拓宽纳入 'requirement'，
     * 既有 total/doneCount/hours 三桶全部显式 FILTER 回原三类型集合（task/test_task/defect），
     * 与 {@link #rollupByGoal} 完全同口径、数值逐位不变（requirement 不进完成率）；
     * 新增每条目类型计数：taskCount（task+test_task，test_task 并入任务桶）/ requirementCount /
     * defectCount 及各自 done 数（done 集按类型感知：task done·closed / test_task passed /
     * requirement delivered·closed / defect 已修复·回归通过·已关闭，与 WorkItem.doneStatusesOf 同源）。</p>
     *
     * <p>hours = estimate_hours 按状态三桶汇总（仍限三类型）——
     * done：task 'done' / defect '已修复'·'回归通过'·'已关闭'（含存量英文 'closed'）/ test_task 'passed'；
     * in_progress：进行中类 'in_progress'·'修复中'·'重新打开'；todo：其余（状态非空且不落前两桶）。
     * releaseId = 条目正向挂接版本（下钻条目→版本→迭代链用；GROUP BY 因 id 唯一不改变行集）。</p>
     *
     * <p>R-5 AC③ 直连口径（与 {@link #rollupByGoal} 同一裁决）：条目行 JOIN 维持条目路径
     * （三桶/类型计数零回归），UNION ALL 追加直挂工作项哨兵行（id=NULL）——各桶 FILTER
     * 与条目行逐字相同，WHERE 限定 wi.goal_id 命中且 roadmap_item_id IS NULL；
     * 哨兵行 releaseId=NULL（无条目即无正向挂接版本），排序同前排在末尾。</p>
     */
    @Query(value = "SELECT ri.id AS id, ri.name AS name, ri.release_id AS \"releaseId\", "
            + "count(*) FILTER (WHERE wi.type IN ('task','test_task','defect')) AS total, "
            + "count(*) FILTER (WHERE wi.type IN ('task','test_task','defect') "
            + "AND wi.status IN ('done','closed','已修复','回归通过','已关闭','passed')) AS \"doneCount\", "
            + "COALESCE(SUM(wi.estimate_hours) FILTER (WHERE wi.type IN ('task','test_task','defect') AND wi.status IN "
            + "('done','closed','已修复','回归通过','已关闭','passed')), 0) AS \"doneHours\", "
            + "COALESCE(SUM(wi.estimate_hours) FILTER (WHERE wi.type IN ('task','test_task','defect') "
            + "AND wi.status IN ('in_progress','修复中','重新打开')), 0) AS \"inProgressHours\", "
            + "COALESCE(SUM(wi.estimate_hours) FILTER (WHERE wi.type IN ('task','test_task','defect') "
            + "AND wi.status IS NOT NULL AND wi.status NOT IN "
            + "('done','closed','已修复','回归通过','已关闭','passed','in_progress','修复中','重新打开')), 0) AS \"todoHours\", "
            + "count(*) FILTER (WHERE wi.type IN ('task','test_task')) AS \"taskCount\", "
            + "count(*) FILTER (WHERE (wi.type = 'task' AND wi.status IN ('done','closed')) "
            + "OR (wi.type = 'test_task' AND wi.status = 'passed')) AS \"taskDone\", "
            + "count(*) FILTER (WHERE wi.type = 'requirement') AS \"requirementCount\", "
            + "count(*) FILTER (WHERE wi.type = 'requirement' AND wi.status IN ('delivered','closed')) AS \"requirementDone\", "
            + "count(*) FILTER (WHERE wi.type = 'defect') AS \"defectCount\", "
            + "count(*) FILTER (WHERE wi.type = 'defect' AND wi.status IN ('已修复','回归通过','已关闭','closed')) AS \"defectDone\", "
            + "ri.created_at AS \"createdAt\" "
            + "FROM prd.roadmap_item ri "
            + "LEFT JOIN prd.work_item wi ON wi.roadmap_item_id = ri.id "
            + "AND wi.type IN ('task','test_task','defect','requirement') "
            + "WHERE ri.goal_id = :goalId "
            + "GROUP BY ri.id, ri.name, ri.release_id, ri.created_at "
            + "UNION ALL "
            + "SELECT NULL::uuid AS id, '直挂目标的工作项' AS name, NULL::uuid AS \"releaseId\", "
            + "count(*) FILTER (WHERE wi.type IN ('task','test_task','defect')) AS total, "
            + "count(*) FILTER (WHERE wi.type IN ('task','test_task','defect') "
            + "AND wi.status IN ('done','closed','已修复','回归通过','已关闭','passed')) AS \"doneCount\", "
            + "COALESCE(SUM(wi.estimate_hours) FILTER (WHERE wi.type IN ('task','test_task','defect') AND wi.status IN "
            + "('done','closed','已修复','回归通过','已关闭','passed')), 0) AS \"doneHours\", "
            + "COALESCE(SUM(wi.estimate_hours) FILTER (WHERE wi.type IN ('task','test_task','defect') "
            + "AND wi.status IN ('in_progress','修复中','重新打开')), 0) AS \"inProgressHours\", "
            + "COALESCE(SUM(wi.estimate_hours) FILTER (WHERE wi.type IN ('task','test_task','defect') "
            + "AND wi.status IS NOT NULL AND wi.status NOT IN "
            + "('done','closed','已修复','回归通过','已关闭','passed','in_progress','修复中','重新打开')), 0) AS \"todoHours\", "
            + "count(*) FILTER (WHERE wi.type IN ('task','test_task')) AS \"taskCount\", "
            + "count(*) FILTER (WHERE (wi.type = 'task' AND wi.status IN ('done','closed')) "
            + "OR (wi.type = 'test_task' AND wi.status = 'passed')) AS \"taskDone\", "
            + "count(*) FILTER (WHERE wi.type = 'requirement') AS \"requirementCount\", "
            + "count(*) FILTER (WHERE wi.type = 'requirement' AND wi.status IN ('delivered','closed')) AS \"requirementDone\", "
            + "count(*) FILTER (WHERE wi.type = 'defect') AS \"defectCount\", "
            + "count(*) FILTER (WHERE wi.type = 'defect' AND wi.status IN ('已修复','回归通过','已关闭','closed')) AS \"defectDone\", "
            + "NULL::timestamptz AS \"createdAt\" "
            + "FROM prd.work_item wi "
            + "WHERE wi.goal_id = :goalId AND wi.roadmap_item_id IS NULL "
            + "ORDER BY \"createdAt\"", nativeQuery = true)
    List<GoalOverviewAggView> overviewByGoal(@Param("goalId") UUID goalId);
}
