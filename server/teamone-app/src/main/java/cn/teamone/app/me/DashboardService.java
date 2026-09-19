package cn.teamone.app.me;

import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.insight.repo.ConflictSnapshotRepository;
import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.Sprint;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.RoadmapAggView;
import cn.teamone.prd.repo.RoadmapItemRepository;
import cn.teamone.prd.repo.SprintRepository;
import cn.teamone.prd.repo.StrategicGoalRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作台聚合读侧（GET /api/v1/me/summary，05 §3.3 platform 段已登记；M2-W4 体验修复 P1-1）。
 *
 * <p>一次返回工作台四指标卡 + 我的待办 + 我参与的话题的全部原料：
 * {greetingName, activeSprint, goals, myRedConflicts, releaseGate, myTodos, myTopics}。
 * 进度/计数一律查询侧聚合（红线①）：目标进度复用 rollupByGoal 的 COUNT FILTER 口径，
 * 门禁阻塞数复用 countOpenBlockingDefects 的 05 §6.1 字面量口径。</p>
 *
 * <p>落位说明：聚合需跨 prd/collab/insight 三域读仓，按 R3/R4/R6 模块方向只能落
 * 装配层 app（组合根）；纯只读、零写侧、零事件。读端点登录态（Controller Actor.require）。</p>
 */
@Service
public class DashboardService {

    /** 「已完结」口径（对齐 SprintService.FINISHED_STATUSES + rejected 需求终态） */
    private static final List<String> OPEN_EXCLUDED = List.of(
            WorkItem.STATUS_DONE, WorkItem.STATUS_REQ_CLOSED,
            WorkItem.STATUS_DEFECT_REGRESSION_PASSED, WorkItem.STATUS_DEFECT_CLOSED,
            WorkItem.STATUS_PASSED, "rejected");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AppUserRepository users;
    private final SprintRepository sprints;
    private final StrategicGoalRepository goals;
    private final WorkItemRepository workItems;
    private final ReleaseRepository releases;
    private final RoadmapItemRepository roadmaps;
    private final ConversationMemberRepository convMembers;
    private final ConversationRepository conversations;
    private final ConflictSnapshotRepository conflictSnapshots;

    public DashboardService(AppUserRepository users, SprintRepository sprints,
                            StrategicGoalRepository goals, WorkItemRepository workItems,
                            ReleaseRepository releases, RoadmapItemRepository roadmaps,
                            ConversationMemberRepository convMembers, ConversationRepository conversations,
                            ConflictSnapshotRepository conflictSnapshots) {
        this.users = users;
        this.sprints = sprints;
        this.goals = goals;
        this.workItems = workItems;
        this.releases = releases;
        this.roadmaps = roadmaps;
        this.convMembers = convMembers;
        this.conversations = conversations;
        this.conflictSnapshots = conflictSnapshots;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID me) {
        AppUser user = users.findById(me).orElse(null);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("greetingName", user == null ? "" : user.getDisplayName());
        res.put("activeSprint", activeSprint());
        res.put("goals", goalCards());
        res.put("myRedConflicts", conflictSnapshots.countUnresolvedRedOfUser(me.toString()));
        res.put("releaseGate", releaseGate());
        res.put("myTodos", myTodos(me));
        res.put("myTopics", myTopics(me));
        return res;
    }

    // ==================== 活跃迭代（最早开始且未完成） ====================

    private Map<String, Object> activeSprint() {
        Sprint s = sprints.findByCompletedAtIsNullOrderByStartDateAsc().stream().findFirst().orElse(null);
        if (s == null) {
            return null;
        }
        Collection<WorkItem> open = workItems.findBySprintIdAndStatusNotIn(s.getId(), OPEN_EXCLUDED);
        double allocated = open.stream()
                .map(WorkItem::getEstimateHours)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .sum();
        int capacity = s.getCapacityHours() == null ? 0 : s.getCapacityHours();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("progress", capacity > 0 ? Math.round(Math.min(100d, allocated / capacity * 100d)) : 0);
        m.put("allocatedHours", allocated);
        m.put("capacityHours", capacity);
        m.put("dueDate", s.getDueDate() == null ? null : s.getDueDate().format(DATE));
        return m;
    }

    // ==================== 战略目标进度卡（rollup 口径聚合） ====================

    private List<Map<String, Object>> goalCards() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var g : goals.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "createdAt"))) {
            long done = 0;
            long total = 0;
            for (RoadmapAggView row : roadmaps.rollupByGoal(g.getId())) {
                done += row.getDoneCount();
                total += row.getTotal();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("key", goalKey(g));
            m.put("name", g.getName());
            m.put("progress", total > 0 ? Math.round(done * 100d / total) : 0);
            m.put("status", "active"); // prd.strategic_goal 无状态列（05 §2.3），存活目标统一 active
            list.add(m);
        }
        return list;
    }

    /** 目标无业务 key 列：名称首 token 形如 G-x 时直接用作 key，否则取 id 短码 */
    private String goalKey(cn.teamone.prd.domain.StrategicGoal g) {
        String name = g.getName() == null ? "" : g.getName().trim();
        int sp = name.indexOf(' ');
        String head = sp > 0 ? name.substring(0, sp) : name;
        if (head.matches("[A-Za-z]+-\\d+")) {
            return head;
        }
        return g.getId().toString().substring(0, 8);
    }

    // ==================== 发布门禁（阻塞版本优先，其次最近待发布） ====================

    private Map<String, Object> releaseGate() {
        Release pick = releases.findAll().stream()
                .filter(r -> !"released".equals(r.getStatus()))
                .min(Comparator
                        .comparing((Release r) -> r.isBlocked() ? 0 : 1) // blocked 优先
                        .thenComparing(r -> r.getPlanDate() == null ? LocalDate.MAX : r.getPlanDate()))
                .orElse(null);
        if (pick == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pick.getId());
        m.put("key", pick.getKey());
        m.put("name", pick.getName());
        m.put("status", pick.getStatus());
        m.put("blocked", pick.isBlocked());
        m.put("blockingCount", workItems.countOpenBlockingDefects(pick.getId()));
        m.put("planDate", pick.getPlanDate() == null ? null : pick.getPlanDate().format(DATE));
        return m;
    }

    // ==================== 我的待办（指派本人且未完结，截止近的在前 5 条） ====================

    private List<Map<String, Object>> myTodos(UUID me) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (WorkItem w : workItems.findTop5ByAssigneeIdAndStatusNotInOrderByDueDateAscIdAsc(me, OPEN_EXCLUDED)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("key", w.getKey());
            m.put("title", w.getTitle());
            m.put("type", w.getType());
            m.put("dueDate", w.getDueDate() == null ? null : w.getDueDate().format(DATE));
            m.put("priority", w.getPriority());
            m.put("status", w.getStatus());
            list.add(m);
        }
        return list;
    }

    // ==================== 我参与的话题（本人成员会话的 topic，最近活跃前 5） ====================

    private List<Map<String, Object>> myTopics(UUID me) {
        List<cn.teamone.collab.domain.Conversation> topics = new ArrayList<>();
        for (UUID cid : convMembers.findByUserId(me).stream().map(cn.teamone.collab.domain.ConversationMember::getConversationId).toList()) {
            conversations.findById(cid)
                    .filter(c -> "topic".equals(c.getType()) && c.getArchivedAt() == null)
                    .ifPresent(topics::add);
        }
        return topics.stream()
                .sorted(Comparator.comparing(cn.teamone.collab.domain.Conversation::getLastMessageAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("title", c.getName());
                    m.put("kind", c.getType());
                    m.put("lastActivity", c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString());
                    return m;
                })
                .toList();
    }
}
