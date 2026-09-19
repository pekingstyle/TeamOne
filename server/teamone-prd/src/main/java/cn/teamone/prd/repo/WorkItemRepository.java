package cn.teamone.prd.repo;

import cn.teamone.prd.domain.WorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作项仓库。发布门禁热点查询（05 §6.1）命中部分索引 idx_work_item_gate：
 * blocked_release_id + severity(致命/严重) + status NOT IN (已关闭/回归通过)。
 */
public interface WorkItemRepository
        extends JpaRepository<WorkItem, UUID>, JpaSpecificationExecutor<WorkItem> {

    /** path 前缀子树存在性（物化路径 '/g1/' 前缀查子树，text_pattern_ops 索引） */
    boolean existsByPathStartingWith(String path);

    /** path 前缀子树计数（上层进度聚合的叶子统计） */
    long countByPathStartingWith(String path);

    /** 按业务键取（D-88 / REQ-1；URL 允许直接用业务键定位） */
    Optional<WorkItem> findByKey(String key);

    /** 清点某版本未关闭的致命/严重缺陷（publish 门禁 4230 阻塞清单的清点口径） */
    long countByBlockedReleaseIdAndSeverityInAndStatusNotIn(
            UUID blockedReleaseId, Collection<String> severity, Collection<String> status);

    /** 某版本未关闭的致命/严重缺陷清单（4230 阻塞清单明细） */
    List<WorkItem> findByBlockedReleaseIdAndSeverityInAndStatusNotIn(
            UUID blockedReleaseId, Collection<String> severity, Collection<String> status);

    /** 某迭代未完成工作项（sprint complete 滚动对象；status 保持只挪 sprint_id，红线⑤） */
    List<WorkItem> findBySprintIdAndStatusNotIn(UUID sprintId, Collection<String> status);

    /**
     * 我的待办（GET /me/summary 只读投影，M2-W4 体验修复）：指派给本人且未完结的工作项，
     * 截止日近的在前，前 5 条。完结口径与 SprintService.FINISHED_STATUSES 对齐（另排除 rejected 需求）。
     */
    List<WorkItem> findTop5ByAssigneeIdAndStatusNotInOrderByDueDateAscIdAsc(
            UUID assigneeId, Collection<String> status);

    /** 挂条目且挂版本的工作项 (条目, 版本) 去重分布（rollup releaseIds 聚合源，M2-INC-1 W1） */
    @Query(value = "SELECT wi.roadmap_item_id AS \"itemId\", wi.release_id AS \"releaseId\" "
            + "FROM prd.work_item wi "
            + "WHERE wi.roadmap_item_id IS NOT NULL AND wi.release_id IS NOT NULL "
            + "GROUP BY wi.roadmap_item_id, wi.release_id", nativeQuery = true)
    List<ItemReleaseView> workItemReleaseDistribution();

    // ==================== 门禁口径统一 native 查询（质量整改 M1） ====================
    // 05 §6.1 SQL 原样，字面量内联保证口径唯一、参数化 NOT IN 难证部分索引命中的问题消除：
    // SELECT count(*) FROM prd.work_item WHERE blocked_release_id=:id
    //   AND severity IN ('致命','严重') AND status NOT IN ('已关闭','回归通过')

    /** 清点某版本未关闭的致命/严重缺陷（门禁 L1 唯一清点口径，字面量内联照抄 05 §6.1） */
    @Query(value = "SELECT count(*) FROM prd.work_item WHERE blocked_release_id = :id "
            + "AND severity IN ('致命','严重') AND status NOT IN ('已关闭','回归通过')",
            nativeQuery = true)
    long countOpenBlockingDefects(@Param("id") UUID releaseId);

    /** 某版本未关闭的致命/严重缺陷清单（4230 details 与 blocked_defect_ids 投影来源） */
    @Query(value = "SELECT id AS id, key AS key, severity AS severity, assignee_id AS assignee "
            + "FROM prd.work_item WHERE blocked_release_id = :id "
            + "AND severity IN ('致命','严重') AND status NOT IN ('已关闭','回归通过')",
            nativeQuery = true)
    List<BlockingDefectView> findOpenBlockingDefects(@Param("id") UUID releaseId);

    // ==================== R-9 发布一致性：未完结工作项清点（publish 硬门禁 4231） ====================
    // 完结集与 WorkItem.doneStatusesOf / insight EfficiencyReportService.isDone 字节级一致：
    // task=done；test_task=passed；defect=已修复/回归通过/已关闭；requirement=delivered/closed。
    // 命中部分索引 idx_work_item_release（release_id WHERE NOT NULL）；按类型分组投影计数+key 样例。

    /** 某版本按类型分组的未完结工作项（4231 明细：类型 × 计数 × key 样例，逗号分隔按 key 序） */
    @Query(value = "SELECT type AS type, count(*) AS count, string_agg(key, ',' ORDER BY key) AS keys "
            + "FROM prd.work_item WHERE release_id = :id AND NOT ("
            + "(type = 'task' AND status = 'done') "
            + "OR (type = 'test_task' AND status = 'passed') "
            + "OR (type = 'defect' AND status IN ('已修复','回归通过','已关闭')) "
            + "OR (type = 'requirement' AND status IN ('delivered','closed')))"
            + " GROUP BY type ORDER BY type", nativeQuery = true)
    List<UnfinishedWorkItemView> findUnfinishedByReleaseGrouped(@Param("id") UUID releaseId);

    // ==================== 需求拆解任务计数（GET /work-items 列表 requirement 行投影） ====================
    // children 按 parent_id、type ∈ task/test_task/defect；done 字面量与 WorkItem.doneStatusesOf
    // 字节级一致（task done·closed / test_task passed / defect 已修复·回归通过·已关闭）。
    // 列表 size≤200，一次 IN 聚合防 N+1；无子行的父 id 不出现在结果（装配处兜 0/0）。

    /** 一批需求行的子任务统计（taskCount/taskDoneCount，按 parent_id 分组） */
    @Query(value = "SELECT parent_id AS \"parentId\", count(*) AS \"taskCount\", "
            + "count(*) FILTER (WHERE (type = 'task' AND status IN ('done','closed')) "
            + "OR (type = 'test_task' AND status = 'passed') "
            + "OR (type = 'defect' AND status IN ('已修复','回归通过','已关闭'))) AS \"taskDoneCount\" "
            + "FROM prd.work_item WHERE parent_id IN (:ids) AND type IN ('task','test_task','defect') "
            + "GROUP BY parent_id", nativeQuery = true)
    List<ChildCountView> childStatsByParentIds(@Param("ids") Collection<UUID> parentIds);
}
