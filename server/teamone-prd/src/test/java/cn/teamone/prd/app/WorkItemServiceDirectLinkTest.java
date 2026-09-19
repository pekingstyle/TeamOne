package cn.teamone.prd.app;

import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.RoadmapItem;
import cn.teamone.prd.domain.StrategicGoal;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.infra.IdempotencyService;
import cn.teamone.platform.infra.OutboxWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkItemService 双路径数据收敛单测（R-5 AC③ · work_item 直连口径）：
 * 写 roadmap_item_id 时自动派生 goal_id = 条目.goal_id（条目无 goal 则置空）；
 * 直挂 goal_id 不动 roadmap_item_id——保证新数据双路径一致（Mockito 单元测试，不碰真库）。
 */
class WorkItemServiceDirectLinkTest {

    private WorkItemRepository workItems;
    private KeySequenceService sequences;
    private GateService gateService;
    private PermissionService permissions;
    private IdempotencyService idempotency;
    private OutboxWriter outbox;
    private Refs refs;
    private EntityManager em;
    private WorkItemService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID GOAL_ID = UUID.randomUUID();
    private static final UUID OTHER_GOAL_ID = UUID.randomUUID();
    private static final UUID RM_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String RM_REF = RM_ID.toString();
    private static final String GOAL_REF = GOAL_ID.toString();

    @BeforeEach
    void setUp() {
        workItems = mock(WorkItemRepository.class);
        sequences = mock(KeySequenceService.class);
        gateService = mock(GateService.class);
        permissions = mock(PermissionService.class);
        idempotency = mock(IdempotencyService.class);
        outbox = mock(OutboxWriter.class);
        refs = mock(Refs.class);
        em = mock(EntityManager.class);
        service = new WorkItemService(workItems, sequences, gateService, permissions,
                idempotency, outbox, refs, em);
        when(workItems.saveAndFlush(any(WorkItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sequences.nextKey(any())).thenReturn("T-201");
        // 先建 mock 再打桩（避免在 when(...) 未完成时嵌套创建/打桩另一个 mock）
        Product p = product();
        when(refs.product(PRODUCT_ID.toString())).thenReturn(p);
    }

    private Product product() {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(PRODUCT_ID);
        return p;
    }

    private StrategicGoal goal(UUID id) {
        StrategicGoal g = mock(StrategicGoal.class);
        when(g.getId()).thenReturn(id);
        return g;
    }

    private RoadmapItem roadmapItem(UUID goalId) {
        RoadmapItem item = mock(RoadmapItem.class);
        when(item.getId()).thenReturn(RM_ID);
        when(item.getGoalId()).thenReturn(goalId);
        return item;
    }

    /** 创建请求（挂 productId 保 path 根；goalId/roadmapItemId 按用例组合） */
    private WorkItemService.CreateSpec createSpec(String goalId, String roadmapItemId) {
        return new WorkItemService.CreateSpec("task", "直连口径用例", null, null, null, null,
                PRODUCT_ID.toString(), null, null, goalId, null, null, roadmapItemId,
                null, null, null, null, null, null, null, null);
    }

    private WorkItem capturedCreate() {
        ArgumentCaptor<WorkItem> captor = ArgumentCaptor.forClass(WorkItem.class);
        verify(workItems).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    /** 挂条目创建：goal_id 派生自条目（条目路径权威，显式 goalId 同传时被条目归属覆盖） */
    @Test
    void createDerivesGoalIdFromRoadmapItemOverridingExplicitGoal() {
        StrategicGoal g = goal(GOAL_ID);
        RoadmapItem item = roadmapItem(OTHER_GOAL_ID);
        when(refs.goal(GOAL_REF)).thenReturn(g);
        when(refs.roadmapItem(RM_REF)).thenReturn(item);

        service.create(createSpec(GOAL_REF, RM_REF), ACTOR_ID, null);

        WorkItem saved = capturedCreate();
        assertEquals(RM_ID, saved.getRoadmapItemId());
        assertEquals(OTHER_GOAL_ID, saved.getGoalId(), "goal_id 必须派生自条目而非显式直挂值");
    }

    /** 直挂目标创建：goal_id 落库、roadmap_item_id 保持空（直挂路径不反向派生条目） */
    @Test
    void createWithGoalIdOnlyKeepsRoadmapItemIdNull() {
        StrategicGoal g = goal(GOAL_ID);
        when(refs.goal(GOAL_REF)).thenReturn(g);

        service.create(createSpec(GOAL_REF, null), ACTOR_ID, null);

        WorkItem saved = capturedCreate();
        assertEquals(GOAL_ID, saved.getGoalId());
        assertNull(saved.getRoadmapItemId());
    }

    /** 条目无 goal 时置空（即使显式传了 goalId——避免直挂值与条目归属漂移出第三态） */
    @Test
    void createClearsGoalIdWhenRoadmapItemHasNoGoal() {
        StrategicGoal g = goal(GOAL_ID);
        RoadmapItem item = roadmapItem(null);
        when(refs.goal(GOAL_REF)).thenReturn(g);
        when(refs.roadmapItem(RM_REF)).thenReturn(item);

        service.create(createSpec(GOAL_REF, RM_REF), ACTOR_ID, null);

        WorkItem saved = capturedCreate();
        assertEquals(RM_ID, saved.getRoadmapItemId());
        assertNull(saved.getGoalId(), "条目无 goal 必须置空，不得残留显式直挂值");
    }

    /** 更新改挂条目：goal_id 按新条目重派生 */
    @Test
    void updateDerivesGoalIdFromNewRoadmapItem() {
        WorkItem wi = workItemMock();
        RoadmapItem item = roadmapItem(OTHER_GOAL_ID);
        when(refs.workItem("T-201")).thenReturn(wi);
        when(refs.roadmapItem(RM_REF)).thenReturn(item);

        service.update("T-201", updateSpec(RM_REF), 1, ACTOR_ID);

        verify(wi).setRoadmapItemId(RM_ID);
        verify(wi).setGoalId(OTHER_GOAL_ID);
    }

    /** 更新改挂「无 goal 条目」：goal_id 置空（与 create 同规则，双路径不漂移） */
    @Test
    void updateClearsGoalIdWhenNewRoadmapItemHasNoGoal() {
        WorkItem wi = workItemMock();
        RoadmapItem item = roadmapItem(null);
        when(refs.workItem("T-201")).thenReturn(wi);
        when(refs.roadmapItem(RM_REF)).thenReturn(item);

        service.update("T-201", updateSpec(RM_REF), 1, ACTOR_ID);

        verify(wi).setRoadmapItemId(RM_ID);
        verify(wi).setGoalId((UUID) null);
    }

    /** 更新未触碰 roadmap_item_id：直挂 goal_id 不受影响（不派生也不清空） */
    @Test
    void updateWithoutRoadmapItemLeavesGoalIdUntouched() {
        WorkItem wi = workItemMock();
        when(refs.workItem("T-201")).thenReturn(wi);

        service.update("T-201", updateSpec(null), 1, ACTOR_ID);

        verify(wi, never()).setRoadmapItemId(any());
        verify(wi, never()).setGoalId(any());
    }

    private WorkItem workItemMock() {
        WorkItem wi = mock(WorkItem.class);
        when(wi.getVersion()).thenReturn(1);
        when(wi.getProductId()).thenReturn(PRODUCT_ID);
        return wi;
    }

    /** 更新请求（null=不变更；仅按用例填 roadmapItemId） */
    private WorkItemService.UpdateSpec updateSpec(String roadmapItemId) {
        return new WorkItemService.UpdateSpec(null, null, null, null, null, null, null,
                roadmapItemId, null, null, null, null, null, null, null);
    }
}
