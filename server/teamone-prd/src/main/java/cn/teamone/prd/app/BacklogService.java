package cn.teamone.prd.app;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.domain.WorkItemLink;
import cn.teamone.prd.repo.WorkItemLinkRepository;
import cn.teamone.prd.repo.WorkItemRepository;

/**
 * 需求池排序服务（⑥s · docs/v2/16 §2.2 打分 v2）。
 *
 * <p>v2 吸收用户评审（v1 缺依赖与执行侧信号）：score = 优先级 + 战略对齐 + 客户信号 + 时效
 * + 就绪度（无未完结前置阻塞 +15 / 被阻塞 −25 并标记阻塞者）+ 执行热度（子任务已开工 +10）
 * + 任务紧急度（开放子任务最高优先级 P0 +10 / P1 +6）。每行返回分项 breakdown——
 * 排序必须可解释，不搞黑盒；依赖只看 blocks 直接一层（多层传播不可解释）。</p>
 *
 * <p>池口径：type=requirement 且 status ∈ {draft, pending_review, accepted}（in_dev 起
 * 视为已排期，不再入池）。体量 storyPoints≥13 仅标记「建议分解」，不参与加减。</p>
 *
 * @author Ivan Yang, 2026-09-30
 */
@Service
public class BacklogService {

    /** 打分权重（docs/v2/16 §2.2 v2；固定口径不开放配置） */
    static final Map<String, Integer> PRIORITY_SCORE = Map.of(
            WorkItem.PRIORITY_P0, 40, WorkItem.PRIORITY_P1, 32,
            WorkItem.PRIORITY_P2, 24, WorkItem.PRIORITY_P3, 16);
    static final int GOAL_ALIGN_SCORE = 20;
    static final int CUSTOM_ORIGIN_SCORE = 15;
    static final int PROMOTED_SCORE = 10;
    static final int AGE_SCORE_PER_WEEK = 1;
    static final int AGE_SCORE_CAP = 10;
    static final int READY_SCORE = 15;
    static final int BLOCKED_PENALTY = -25;
    static final int HEAT_SCORE = 10;
    static final int TASK_URGENCY_P0 = 10;
    static final int TASK_URGENCY_P1 = 6;
    static final int SPLIT_THRESHOLD_POINTS = 13;

    private static final List<String> POOL_STATUSES = List.of(
            WorkItem.STATUS_REQ_DRAFT, WorkItem.STATUS_REQ_PENDING_REVIEW, WorkItem.STATUS_REQ_ACCEPTED);

    private final WorkItemRepository workItems;
    private final WorkItemLinkRepository links;

    public BacklogService(WorkItemRepository workItems, WorkItemLinkRepository links) {
        this.workItems = workItems;
        this.links = links;
    }

    /** 需求池行（score + 可解释分项 + 依赖/子任务信号） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pool(String productId) {
        List<WorkItem> items = workItems.findByTypeAndStatusIn(WorkItem.TYPE_REQUIREMENT, POOL_STATUSES);
        if (productId != null && !productId.isBlank()) {
            UUID pid = UUID.fromString(productId.trim());
            items = items.stream().filter(w -> pid.equals(w.getProductId())).toList();
        }
        if (items.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = items.stream().map(WorkItem::getId).toList();

        // 子任务三列视图（执行热度 / 任务紧急度 / 进度统计）
        Map<UUID, List<WorkItemRepository.ChildView>> childrenByParent = workItems
                .childViewsByParentIds(ids).stream()
                .collect(Collectors.groupingBy(WorkItemRepository.ChildView::getParentId));

        // blocks 阻塞探测（直接一层）：to=池内项、from=未完结工作项
        Map<UUID, List<String>> blockedByKeys = new LinkedHashMap<>();
        Map<UUID, List<String>> blocksOthers = new LinkedHashMap<>();
        Map<UUID, WorkItem> related = new LinkedHashMap<>();
        // QA 随手项：to 侧查询结果缓存复用（原先第二轮循环重复查询）
        Map<UUID, List<WorkItemLink>> inboundByItem = new LinkedHashMap<>();
        for (WorkItem item : items) {
            List<WorkItemLink> inbound = links.findByToItemId(item.getId());
            inboundByItem.put(item.getId(), inbound);
            for (WorkItemLink l : inbound) {
                if (WorkItemLink.RELATION_BLOCKS.equals(l.getRelation())) {
                    related.put(l.getFromItemId(), null);
                }
            }
            for (WorkItemLink l : links.findByFromItemId(item.getId())) {
                if (WorkItemLink.RELATION_BLOCKS.equals(l.getRelation())) {
                    related.put(l.getToItemId(), null);
                    blocksOthers.computeIfAbsent(item.getId(), k -> new ArrayList<>()).add(l.getToItemId().toString());
                }
            }
        }
        workItems.findAllById(related.keySet()).forEach(w -> related.put(w.getId(), w));
        for (WorkItem item : items) {
            for (WorkItemLink l : inboundByItem.getOrDefault(item.getId(), List.of())) {
                if (!WorkItemLink.RELATION_BLOCKS.equals(l.getRelation())) {
                    continue;
                }
                WorkItem blocker = related.get(l.getFromItemId());
                if (blocker != null && !WorkItem.doneStatusesOf(blocker.getType()).contains(blocker.getStatus())) {
                    blockedByKeys.computeIfAbsent(item.getId(), k -> new ArrayList<>()).add(blocker.getKey());
                }
            }
        }

        Instant now = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (WorkItem w : items) {
            List<WorkItemRepository.ChildView> children = childrenByParent.getOrDefault(w.getId(), List.of());
            long openChildren = children.stream()
                    .filter(c -> !WorkItem.doneStatusesOf(c.getType()).contains(c.getStatus())).count();
            long doneChildren = children.size() - openChildren;
            boolean started = children.stream()
                    .anyMatch(c -> "in_progress".equals(c.getStatus()) || WorkItem.STATUS_DONE.equals(c.getStatus())
                            || WorkItem.STATUS_CLOSED.equals(c.getStatus()));

            Map<String, Integer> bd = new LinkedHashMap<>();
            // priority 列可空（种子数据）——空值按 P2 计；Map.of 不容 null key，先归一
            String pr = w.getPriority() == null ? WorkItem.PRIORITY_P2 : w.getPriority();
            bd.put("priority", PRIORITY_SCORE.getOrDefault(pr, PRIORITY_SCORE.get(WorkItem.PRIORITY_P2)));
            bd.put("goal", w.getGoalId() != null ? GOAL_ALIGN_SCORE : 0);
            int customer = 0;
            if (WorkItem.ORIGIN_PROJECT_CUSTOM.equals(w.getOrigin())) {
                customer += CUSTOM_ORIGIN_SCORE;
            }
            if (w.getPromotedToId() != null) {
                customer += PROMOTED_SCORE;
            }
            bd.put("customer", customer);
            long days = Duration.between(w.getCreatedAt(), now).toDays();
            bd.put("age", (int) Math.min(AGE_SCORE_CAP, days / 7));

            List<String> blockers = blockedByKeys.getOrDefault(w.getId(), List.of());
            boolean blocked = !blockers.isEmpty();
            bd.put("ready", blocked ? BLOCKED_PENALTY : READY_SCORE);

            // 执行侧信号（热度/任务紧急度）以「能开工」为前提：被未完结前置阻塞时两者置零——
            // 否则出现「被阻塞却因开工热度排前」的自相矛盾排序
            bd.put("heat", (!blocked && started) ? HEAT_SCORE : 0);
            int taskUrgency = 0;
            if (!blocked && openChildren > 0) {
                boolean hasP0 = children.stream().anyMatch(c ->
                        !WorkItem.doneStatusesOf(c.getType()).contains(c.getStatus())
                                && WorkItem.PRIORITY_P0.equals(c.getPriority()));
                boolean hasP1 = children.stream().anyMatch(c ->
                        !WorkItem.doneStatusesOf(c.getType()).contains(c.getStatus())
                                && WorkItem.PRIORITY_P1.equals(c.getPriority()));
                taskUrgency = hasP0 ? TASK_URGENCY_P0 : (hasP1 ? TASK_URGENCY_P1 : 0);
            }
            bd.put("taskUrgency", taskUrgency);

            int score = bd.values().stream().mapToInt(Integer::intValue).sum();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", w.getId());
            row.put("key", w.getKey());
            row.put("title", w.getTitle());
            row.put("status", w.getStatus());
            row.put("priority", w.getPriority());
            row.put("storyPoints", w.getStoryPoints());
            row.put("origin", w.getOrigin());
            row.put("promotedToId", w.getPromotedToId());
            row.put("goalId", w.getGoalId());
            row.put("productId", w.getProductId());
            row.put("createdAt", w.getCreatedAt());
            row.put("score", score);
            row.put("breakdown", bd);
            row.put("blockedBy", blockers);
            row.put("blocksCount", blocksOthers.getOrDefault(w.getId(), List.of()).size());
            row.put("taskStats", Map.of("total", children.size(), "open", openChildren, "done", doneChildren));
            row.put("suggestsSplit", w.getStoryPoints() != null
                    && w.getStoryPoints().intValue() >= SPLIT_THRESHOLD_POINTS);
            rows.add(row);
        }
        // 分组排序：可开工组在前（按分），被阻塞组沉底（组内按分）——被阻塞排前面是浪费
        rows.sort(Comparator
                .comparing((Map<String, Object> r) -> !((List<?>) r.get("blockedBy")).isEmpty())
                .thenComparing(Comparator.<Map<String, Object>>comparingInt(r -> (int) r.get("score")).reversed())
                .thenComparing(r -> (Instant) r.get("createdAt")));
        rows.forEach(r -> r.remove("createdAt"));
        return rows;
    }
}
