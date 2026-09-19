package cn.teamone.prd.app;

import cn.teamone.prd.domain.RoadmapItem;
import cn.teamone.prd.domain.StrategicGoal;
import cn.teamone.prd.repo.GoalOverviewAggView;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.RoadmapAggView;
import cn.teamone.prd.repo.RoadmapItemRepository;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.StrategicGoalRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GoalService rollup/overview 组装层单测（R-5 AC③ · work_item 直连口径，投影级 Mockito 测试）：
 * 条目路径计数零回归、直挂哨兵行生成与口径（计数&gt;0 才输出）、双路径计数互不污染。
 * 注意：mock 一律先建后桩（不得在 when(...) 参数里嵌套创建/打桩 mock）。
 */
class GoalServiceRollupTest {

    private StrategicGoalRepository goals;
    private RoadmapItemRepository roadmaps;
    private WorkItemRepository workItems;
    private ReleaseRepository releases;
    private ProductRepository products;
    private PermissionService permissions;
    private Refs refs;
    private GoalService service;

    private static final UUID GOAL_ID = UUID.randomUUID();
    private static final UUID ITEM_A = UUID.randomUUID();
    private static final UUID ITEM_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        goals = mock(StrategicGoalRepository.class);
        roadmaps = mock(RoadmapItemRepository.class);
        workItems = mock(WorkItemRepository.class);
        releases = mock(ReleaseRepository.class);
        products = mock(ProductRepository.class);
        permissions = mock(PermissionService.class);
        refs = mock(Refs.class);
        service = new GoalService(goals, roadmaps, workItems, releases, products, permissions, refs);
    }

    // ---------------- 造数：投影 mock（先建后桩） ----------------

    private StrategicGoal goal() {
        StrategicGoal g = mock(StrategicGoal.class);
        when(g.getId()).thenReturn(GOAL_ID);
        when(g.getName()).thenReturn("G-1 平台效能提升");
        return g;
    }

    private RoadmapItem item(UUID id, String name) {
        RoadmapItem r = mock(RoadmapItem.class);
        when(r.getId()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.getProductId()).thenReturn(null);
        when(r.getReleaseId()).thenReturn(null);
        return r;
    }

    /** 条目行聚合投影（id 非空 = 条目路径） */
    private RoadmapAggView agg(UUID id, long total, long done) {
        RoadmapAggView v = mock(RoadmapAggView.class);
        when(v.getId()).thenReturn(id);
        when(v.getName()).thenReturn("RM-x");
        when(v.getGoalId()).thenReturn(GOAL_ID);
        when(v.getTotal()).thenReturn(total);
        when(v.getDoneCount()).thenReturn(done);
        return v;
    }

    /** 直挂哨兵行投影（id = null） */
    private RoadmapAggView sentinel(long total, long done) {
        RoadmapAggView v = agg(null, total, done);
        when(v.getName()).thenReturn(GoalService.DIRECT_SENTINEL_NAME);
        return v;
    }

    private void stubRollupCommon() {
        StrategicGoal g = goal();
        when(refs.goal(GOAL_ID.toString())).thenReturn(g);
        when(workItems.workItemReleaseDistribution()).thenReturn(List.of());
        when(releases.findAll()).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> rollup) {
        return (List<Map<String, Object>>) rollup.get("roadmapItems");
    }

    // ---------------- rollup ----------------

    /** 条目路径计数零回归：无直挂工作项时行集与改造前完全一致，不产生哨兵行 */
    @Test
    void rollupKeepsItemPathCountsAndOmitsSentinelWhenNoDirectItems() {
        stubRollupCommon();
        RoadmapItem itemA = item(ITEM_A, "RM-A");
        RoadmapItem itemB = item(ITEM_B, "RM-B");
        RoadmapAggView aggA = agg(ITEM_A, 3, 1);
        RoadmapAggView aggB = agg(ITEM_B, 2, 2);
        when(roadmaps.findByGoalIdOrderByCreatedAtAsc(GOAL_ID)).thenReturn(List.of(itemA, itemB));
        when(roadmaps.rollupByGoal(GOAL_ID)).thenReturn(List.of(aggA, aggB));

        Map<String, Object> res = service.rollup(GOAL_ID.toString());
        List<Map<String, Object>> items = rows(res);

        assertEquals(2, items.size());
        assertEquals(ITEM_A, items.get(0).get("id"));
        assertEquals(3L, items.get(0).get("total"));
        assertEquals(1L, items.get(0).get("workItemDone"));
        assertEquals(ITEM_B, items.get(1).get("id"));
        assertEquals(2L, items.get(1).get("total"));
        assertEquals(2L, items.get(1).get("workItemDone"));
        assertTrue(items.stream().allMatch(r -> r.get("id") != null), "无直挂时不得出现哨兵行");
    }

    /** 直挂哨兵行生成：id=null、名称哨兵、计数独立（不摊入条目行），排在条目行后 */
    @Test
    void rollupAppendsDirectSentinelRowWhenCountPositive() {
        stubRollupCommon();
        RoadmapItem itemA = item(ITEM_A, "RM-A");
        RoadmapAggView aggA = agg(ITEM_A, 3, 1);
        RoadmapAggView directAgg = sentinel(2, 1);
        when(roadmaps.findByGoalIdOrderByCreatedAtAsc(GOAL_ID)).thenReturn(List.of(itemA));
        when(roadmaps.rollupByGoal(GOAL_ID)).thenReturn(List.of(aggA, directAgg));

        List<Map<String, Object>> items = rows(service.rollup(GOAL_ID.toString()));

        assertEquals(2, items.size());
        // 条目行计数零回归（直挂项不摊入）
        assertEquals(ITEM_A, items.get(0).get("id"));
        assertEquals(3L, items.get(0).get("total"));
        assertEquals(1L, items.get(0).get("workItemDone"));
        // 哨兵行契约
        Map<String, Object> direct = items.get(1);
        assertNull(direct.get("id"), "哨兵行 id 必须为 null");
        assertEquals(GoalService.DIRECT_SENTINEL_NAME, direct.get("name"));
        assertEquals(2L, direct.get("total"));
        assertEquals(1L, direct.get("workItemDone"));
        assertNull(direct.get("productId"));
        assertNull(direct.get("productName"));
        assertTrue(((List<?>) direct.get("releaseIds")).isEmpty(), "直挂工作项无条目归属，无版本投影");
    }

    /** 哨兵行仅在计数 > 0 时输出（rollupByGoal 返回零计数哨兵时不得出现） */
    @Test
    void rollupOmitsSentinelRowWhenDirectCountIsZero() {
        stubRollupCommon();
        RoadmapItem itemA = item(ITEM_A, "RM-A");
        RoadmapAggView aggA = agg(ITEM_A, 3, 1);
        RoadmapAggView zeroDirect = sentinel(0, 0);
        when(roadmaps.findByGoalIdOrderByCreatedAtAsc(GOAL_ID)).thenReturn(List.of(itemA));
        when(roadmaps.rollupByGoal(GOAL_ID)).thenReturn(List.of(aggA, zeroDirect));

        List<Map<String, Object>> items = rows(service.rollup(GOAL_ID.toString()));

        assertEquals(1, items.size());
        assertEquals(ITEM_A, items.get(0).get("id"));
    }

    // ---------------- overview ----------------

    private GoalOverviewAggView ov(UUID id, String name, long total, long done,
                                   String doneHours, String inProgressHours, String todoHours,
                                   long taskCount, long taskDone, long requirementCount, long requirementDone,
                                   long defectCount, long defectDone) {
        GoalOverviewAggView v = mock(GoalOverviewAggView.class);
        when(v.getId()).thenReturn(id);
        when(v.getName()).thenReturn(name);
        when(v.getReleaseId()).thenReturn(null);
        when(v.getTotal()).thenReturn(total);
        when(v.getDoneCount()).thenReturn(done);
        when(v.getDoneHours()).thenReturn(new BigDecimal(doneHours));
        when(v.getInProgressHours()).thenReturn(new BigDecimal(inProgressHours));
        when(v.getTodoHours()).thenReturn(new BigDecimal(todoHours));
        when(v.getTaskCount()).thenReturn(taskCount);
        when(v.getTaskDone()).thenReturn(taskDone);
        when(v.getRequirementCount()).thenReturn(requirementCount);
        when(v.getRequirementDone()).thenReturn(requirementDone);
        when(v.getDefectCount()).thenReturn(defectCount);
        when(v.getDefectDone()).thenReturn(defectDone);
        return v;
    }

    private GoalOverviewAggView itemOv(UUID id, String name, long total, long done) {
        return ov(id, name, total, done, "8.0", "4.0", "2.0", 2, 1, 1, 0, 1, 0);
    }

    /** overview 哨兵行：计数并入 goal 级 total/done/completionRate，虚拟行携带全口径桶 */
    @Test
    void overviewMergesDirectSentinelIntoGoalTotals() {
        StrategicGoal g = goal();
        GoalOverviewAggView item = itemOv(ITEM_A, "RM-A", 3, 1);
        GoalOverviewAggView direct = ov(null, GoalService.DIRECT_SENTINEL_NAME, 2, 1,
                "5.0", "3.0", "0.0", 1, 1, 1, 0, 0, 0);
        when(goals.findAll(any(Sort.class))).thenReturn(List.of(g));
        when(roadmaps.overviewByGoal(GOAL_ID)).thenReturn(List.of(item, direct));

        Map<String, Object> res = service.overview();
        Map<String, Object> goalRow = ((List<Map<String, Object>>) res.get("goals")).get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) goalRow.get("items");

        // goal 级合计并入哨兵（3+2 / 1+1），完成率 = 2/5 = 40.0
        assertEquals(5L, goalRow.get("total"));
        assertEquals(2L, goalRow.get("done"));
        assertEquals(new BigDecimal("40.0"), goalRow.get("completionRate"));
        assertEquals(2, items.size());
        // 条目行零回归
        assertEquals(ITEM_A, items.get(0).get("id"));
        assertEquals(3L, items.get(0).get("total"));
        assertEquals(1L, items.get(0).get("done"));
        // 哨兵虚拟行全口径
        Map<String, Object> directRow = items.get(1);
        assertNull(directRow.get("id"));
        assertEquals(GoalService.DIRECT_SENTINEL_NAME, directRow.get("name"));
        assertEquals(2L, directRow.get("total"));
        assertEquals(1L, directRow.get("done"));
        assertEquals(new BigDecimal("5.0"), directRow.get("doneHours"));
        assertEquals(new BigDecimal("3.0"), directRow.get("inProgressHours"));
        assertEquals(new BigDecimal("0.0"), directRow.get("todoHours"));
        assertNull(directRow.get("releaseId"));
        assertEquals(1L, directRow.get("taskCount"));
        assertEquals(1L, directRow.get("taskDone"));
        assertEquals(1L, directRow.get("requirementCount"));
        assertEquals(0L, directRow.get("requirementDone"));
        assertEquals(0L, directRow.get("defectCount"));
        assertEquals(0L, directRow.get("defectDone"));
    }

    /** overview 无直挂（零计数哨兵）时行集与 goal 级合计不回归 */
    @Test
    void overviewOmitsSentinelAndKeepsTotalsWhenNoDirectItems() {
        StrategicGoal g = goal();
        GoalOverviewAggView item = itemOv(ITEM_A, "RM-A", 3, 1);
        GoalOverviewAggView zeroDirect = ov(null, GoalService.DIRECT_SENTINEL_NAME, 0, 0,
                "0.0", "0.0", "0.0", 0, 0, 0, 0, 0, 0);
        when(goals.findAll(any(Sort.class))).thenReturn(List.of(g));
        when(roadmaps.overviewByGoal(GOAL_ID)).thenReturn(List.of(item, zeroDirect));

        Map<String, Object> res = service.overview();
        Map<String, Object> goalRow = ((List<Map<String, Object>>) res.get("goals")).get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) goalRow.get("items");

        assertEquals(3L, goalRow.get("total"));
        assertEquals(1L, goalRow.get("done"));
        assertEquals(1, items.size());
        assertEquals(ITEM_A, items.get(0).get("id"));
        assertTrue(items.stream().allMatch(r -> r.get("id") != null));
        // doneHours 仍来自条目行（口径不漂移）
        assertEquals(new BigDecimal("8.0"), items.get(0).get("doneHours"));
    }

    /** 双路径去重（组装层视角）：一个工作项同挂条目+goal 时，SQL 只在条目行计一次——
     *  条目行计数即唯一来源，哨兵行只收 roadmap_item_id IS NULL 的直挂项；
     *  此处锁定两行计数相加不重复放大（SQL 级守卫由 SqlContractTest 钉死） */
    @Test
    void dualPathWorkItemCountsOnceViaItemRowOnly() {
        stubRollupCommon();
        RoadmapItem itemA = item(ITEM_A, "RM-A");
        RoadmapAggView aggA = agg(ITEM_A, 1, 0);
        RoadmapAggView directAgg = sentinel(1, 1);
        when(roadmaps.findByGoalIdOrderByCreatedAtAsc(GOAL_ID)).thenReturn(List.of(itemA));
        when(roadmaps.rollupByGoal(GOAL_ID)).thenReturn(List.of(aggA, directAgg));

        List<Map<String, Object>> items = rows(service.rollup(GOAL_ID.toString()));

        assertEquals(1L, items.get(0).get("total"), "双路径工作项只在条目行计一次");
        assertEquals(1L, items.get(1).get("total"), "哨兵行只含纯直挂项");
        assertFalse(items.get(0).get("id").equals(items.get(1).get("id")));
    }
}
