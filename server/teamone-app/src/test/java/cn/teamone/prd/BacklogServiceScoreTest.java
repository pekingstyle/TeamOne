package cn.teamone.prd;

import cn.teamone.prd.app.BacklogService;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.domain.WorkItemLink;
import cn.teamone.prd.repo.WorkItemLinkRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 需求池打分 v2 单测（⑥s · docs/v2/16 §2.2）：依赖感知（被 blocks 前置阻塞 −25 且标记）
 * 与执行侧信号（子任务热度/任务紧急度）——v1 缺失的两类事实。
 *
 * @author Ivan Yang, 2026-09-30
 */
class BacklogServiceScoreTest {

    private final WorkItemRepository workItems = mock(WorkItemRepository.class);
    private final WorkItemLinkRepository links = mock(WorkItemLinkRepository.class);
    private final BacklogService service = new BacklogService(workItems, links);

    @Test
    void pool_scoresDependencyAndExecutionSignals() {
        UUID productId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();
        UUID freeId = UUID.randomUUID();

        // 被阻塞需求：P1 + 挂目标 + 客制化 + 滞留 14 天（+2）→ 基础 32+20+15+2=69，被阻塞 −25 → 44
        WorkItem blocked = req(blockedId, "REQ-B", "P1", productId, goalId,
                "project_custom", null, Instant.now().minus(14, ChronoUnit.DAYS));
        // 阻塞者：任务态、未完结（doneStatusesOf(task)=done/closed 不含 in_progress）
        WorkItem blocker = req(blockerId, "T-90", "P2", productId, null,
                "product", null, Instant.now().minus(1, ChronoUnit.DAYS));
        blocker.setType("task");
        blocker.setStatus("in_progress");
        // 自由需求：P2 无目标无客制 滞留 0 天 → 24+15(就绪)=39
        WorkItem free = req(freeId, "REQ-F", "P2", productId, null,
                "product", null, Instant.now());

        when(workItems.findByTypeAndStatusIn("requirement",
                List.of("draft", "pending_review", "accepted")))
                .thenReturn(List.of(blocked, free));
        when(links.findByToItemId(blockedId)).thenReturn(List.of(
                link(blockerId, blockedId, "blocks")));
        when(links.findByToItemId(freeId)).thenReturn(List.of());
        when(links.findByFromItemId(any(UUID.class))).thenReturn(List.of());
        when(workItems.findAllById(anyCollection())).thenReturn(List.of(blocker));
        // 被阻塞需求有两个子任务：一个 P0 开放（任务紧急度 +10）+ 一个 done（执行热度 +10）
        when(workItems.childViewsByParentIds(anyCollection())).thenReturn(List.of(
                child(blockedId, "P0", "todo", "task"),
                child(blockedId, "P2", "done", "task"),
                child(freeId, "P2", "todo", "task")));

        List<Map<String, Object>> rows = service.pool(productId.toString());

        assertEquals(2, rows.size());
        Map<String, Object> freeRow = rows.stream().filter(r -> "REQ-F".equals(r.get("key"))).findFirst().orElseThrow();
        Map<String, Object> blockedRow = rows.stream().filter(r -> "REQ-B".equals(r.get("key"))).findFirst().orElseThrow();
        assertEquals("REQ-F", rows.get(0).get("key"), "分组排序：可开工组在前");
        assertEquals(39, freeRow.get("score"), "就绪 +15 无其他加成：24+15=39");
        assertEquals(List.of(), freeRow.get("blockedBy"));


        assertEquals(List.of("T-90"), blockedRow.get("blockedBy"), "被未完结前置阻塞须标记阻塞者 key");
        @SuppressWarnings("unchecked")
        Map<String, Integer> bd = (Map<String, Integer>) blockedRow.get("breakdown");
        assertEquals(-25, bd.get("ready"), "被阻塞 −25（vs 就绪 +15，净差 40）");
        assertEquals(0, bd.get("taskUrgency"), "被阻塞时任务紧急度置零（执行信号以能开工为前提）");
        assertEquals(0, bd.get("heat"), "被阻塞时执行热度置零");
        assertEquals(44, blockedRow.get("score"), "32+20+15+2−25=44；被阻塞时 heat/taskUrgency 置零");
        assertTrue((Integer) freeRow.get("score") < (Integer) blockedRow.get("score"),
                "被阻塞组分更低（纸面分高但执行信号置零）；且分组排序下排后");
        // 分组排序：可开工 REQ-F 在前，被阻塞 REQ-B 沉底（即使其 breakdown 总分更高）
        assertEquals("REQ-F", rows.get(0).get("key"));
    }

    @Test
    void pool_splitFlagAndPromotionSignal() {
        UUID productId = UUID.randomUUID();
        UUID bigId = UUID.randomUUID();
        // 大体量已回流需求：P2=24 + 已回流 +10 = 34，storyPoints 13 → 建议分解
        WorkItem big = req(bigId, "REQ-S", "P2", productId, null,
                "product", UUID.randomUUID(), Instant.now());
        big.setStoryPoints(new java.math.BigDecimal("13"));
        when(workItems.findByTypeAndStatusIn("requirement",
                List.of("draft", "pending_review", "accepted"))).thenReturn(List.of(big));
        when(links.findByToItemId(any(UUID.class))).thenReturn(List.of());
        when(links.findByFromItemId(any(UUID.class))).thenReturn(List.of());
        when(workItems.childViewsByParentIds(anyCollection())).thenReturn(List.of());

        Map<String, Object> row = service.pool(productId.toString()).get(0);
        assertEquals(49, row.get("score"), "24+10（回流）+15（就绪）=49；体量不参与加减");
        assertEquals(true, row.get("suggestsSplit"), "storyPoints≥13 标记建议分解");
    }

    private WorkItem req(UUID id, String key, String priority, UUID productId, UUID goalId,
                         String origin, UUID promotedToId, Instant createdAt) {
        WorkItem w = new WorkItem();
        w.setKey(key);
        w.setType("requirement");
        w.setTitle("用例 " + key);
        w.setPriority(priority);
        w.setStatus("draft");
        w.setProductId(productId);
        w.setGoalId(goalId);
        w.setOrigin(origin);
        w.setPromotedToId(promotedToId);
        ReflectionTestUtils.setField(w, "id", id);
        ReflectionTestUtils.setField(w, "createdAt", createdAt);
        return w;
    }

    private WorkItemLink link(UUID fromId, UUID toId, String relation) {
        WorkItemLink l = new WorkItemLink();
        l.setFromItemId(fromId);
        l.setToItemId(toId);
        l.setRelation(relation);
        return l;
    }

    private WorkItemRepository.ChildView child(UUID parentId, String priority, String status, String type) {
        return new WorkItemRepository.ChildView() {
            @Override public UUID getParentId() { return parentId; }
            @Override public String getPriority() { return priority; }
            @Override public String getStatus() { return status; }
            @Override public String getType() { return type; }
        };
    }
}
