package cn.teamone.insight.app;

import cn.teamone.insight.domain.ConflictSnapshot;
import cn.teamone.insight.domain.InsightWorkItem;
import cn.teamone.insight.engine.ConflictDraft;
import cn.teamone.insight.engine.ConflictEngine;
import cn.teamone.insight.engine.EngineInput;
import cn.teamone.insight.engine.ItemView;
import cn.teamone.insight.engine.ReleaseView;
import cn.teamone.insight.repo.ConflictSnapshotRepository;
import cn.teamone.insight.repo.InsightCalendarRepository;
import cn.teamone.insight.repo.InsightReleaseRepository;
import cn.teamone.insight.repo.InsightSprintRepository;
import cn.teamone.insight.repo.InsightUserCapacityRepository;
import cn.teamone.insight.repo.InsightWorkItemLinkRepository;
import cn.teamone.insight.repo.InsightWorkItemRepository;
import cn.teamone.platform.infra.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冲突批算服务（05 §6.1 冲突批任务；08 §5 W2）。
 *
 * <p><b>同入口纯函数</b>（红线⑥）：全量 recomputeAll 与增量 recomputeForUser 都走
 * {@link ConflictEngine#compute}——只是输入装配范围不同（02 FR-v2-04「每日全量+变更增量」）。</p>
 *
 * <p><b>快照重写</b>（红线②⑥）：删当日旧行再插，幂等不追加；红色新增判定=本轮红色指纹
 * ∉ 存量未消解红色指纹（删除前先取基线），命中才 outbox(conflict.detected{userId,redCount})，
 * 事件按当事人聚合并以 user 为聚合 id（同当事人事件同分片保序）。</p>
 *
 * <p>open 口径（02 §三方向审查定稿，修正原型把中文完结态漏进 open 的笔误）：
 * status∉{done,closed,已关闭,回归通过} 且 start/due 齐备；type=requirement 不参与
 * （原型 workItems 仅 task/testtask/defect，需求进度走评审流）。</p>
 */
@Service
public class ConflictBatchService {

    private static final Logger log = LoggerFactory.getLogger(ConflictBatchService.class);

    /** blocks 关系字面量（prd.work_item_link CHECK；红线⑤ from 阻塞 to 全局唯一） */
    private static final String RELATION_BLOCKS = "blocks";

    private final InsightWorkItemRepository workItems;
    private final InsightCalendarRepository calendars;
    private final InsightReleaseRepository releases;
    private final InsightSprintRepository sprints;
    private final InsightUserCapacityRepository users;
    private final InsightWorkItemLinkRepository links;
    private final ConflictSnapshotRepository snapshots;
    private final OutboxWriter outbox;

    public ConflictBatchService(InsightWorkItemRepository workItems,
                                InsightCalendarRepository calendars,
                                InsightReleaseRepository releases,
                                InsightSprintRepository sprints,
                                InsightUserCapacityRepository users,
                                InsightWorkItemLinkRepository links,
                                ConflictSnapshotRepository snapshots,
                                OutboxWriter outbox) {
        this.workItems = workItems;
        this.calendars = calendars;
        this.releases = releases;
        this.sprints = sprints;
        this.users = users;
        this.links = links;
        this.snapshots = snapshots;
        this.outbox = outbox;
    }

    // ==================== 全量（每日 02:00 调度 / admin 手动） ====================

    /** @return 汇总 {total, red, yellow, redNew, notifiedUsers}（admin 端点回显/日志用） */
    @Transactional
    public Map<String, Object> recomputeAll() {
        EngineInput in = loadInputs(null);
        List<ConflictDraft> drafts = ConflictEngine.compute(in);
        return rewrite(drafts, null);
    }

    // ==================== 增量（workitem.updated 事件驱动） ====================

    /** 只重算该当事人（同入口纯函数；CF-3 产品级随之跳过，由每日全量维护）。 */
    @Transactional
    public void recomputeForUser(UUID userId) {
        EngineInput in = loadInputs(userId);
        List<ConflictDraft> drafts = ConflictEngine.compute(in);
        rewrite(drafts, userId);
    }

    // ==================== 输入装配（只读映射 → 引擎输入） ====================

    private EngineInput loadInputs(UUID onlyUser) {
        List<InsightWorkItem> all = workItems.findAll();
        Map<UUID, ItemView> byId = new LinkedHashMap<>();
        List<ItemView> open = new ArrayList<>();
        for (InsightWorkItem w : all) {
            ItemView v = new ItemView(w.getId(), w.getKey(), w.getTitle(), w.getAssigneeId(), w.getProductId(),
                    w.getSprintId(), w.getReleaseId(), w.getStatus(), w.getStartDate(),
                    w.getDueDate(), w.getEstimateHours());
            byId.put(v.id(), v);
            if (ConflictEngine.isOpen(v.status(), v.startDate(), v.dueDate())
                    && !"requirement".equals(w.getType())) {
                open.add(v);
            }
        }
        if (onlyUser != null) {
            open = open.stream().filter(t -> onlyUser.equals(t.assigneeId())).toList();
        }

        Set<LocalDate> workdays = new HashSet<>();
        calendars.findAll().forEach(c -> {
            if (c.isWorkday()) {
                workdays.add(c.getCalDate());
            }
        });

        Map<UUID, BigDecimal> caps = new LinkedHashMap<>();
        users.findAll().forEach(u -> caps.put(u.getId(), BigDecimal.valueOf(u.getDailyCapacityHours())));

        // sprint.due_date NULL 不入表——引擎 getOrDefault 落哨兵 '9999-12-31'（Map 禁 null 值）
        Map<UUID, LocalDate> sprintEnds = new LinkedHashMap<>();
        sprints.findAll().forEach(s -> {
            if (s.getDueDate() != null) {
                sprintEnds.put(s.getId(), s.getDueDate());
            }
        });

        List<ReleaseView> allReleases = new ArrayList<>();
        releases.findAll().forEach(r -> allReleases.add(new ReleaseView(r.getId(), r.getKey(),
                r.getName(), r.getProductId(), r.getPlanDate(), r.getCodeFreezeDate())));
        // CF-3 产品级：增量模式传空清单跳过（引擎 onlyUser 口径），只由每日全量维护
        List<ReleaseView> releaseViews = onlyUser != null ? List.of() : allReleases;

        // blocks 反查（红线⑤）：from 阻塞 to → 引擎按 to 找 from
        Map<UUID, List<UUID>> blockedFromByTo = new LinkedHashMap<>();
        links.findByRelation(RELATION_BLOCKS).forEach(l ->
                blockedFromByTo.computeIfAbsent(l.getToItemId(), k -> new ArrayList<>()).add(l.getFromItemId()));

        return new EngineInput(open, byId, caps, sprintEnds, releaseViews, workdays,
                blockedFromByTo, onlyUser);
    }

    // ==================== 快照重写（删当日旧行再插 + 红色新增事件） ====================

    private Map<String, Object> rewrite(List<ConflictDraft> drafts, UUID onlyUser) {
        // 红色新增基线：删除/插入前先取存量未消解红色指纹（含历史日与今日早前批次）
        Set<String> prevRed = new HashSet<>(snapshots.unresolvedRedFingerprints());

        if (onlyUser == null) {
            snapshots.deleteTodaysRows();
        } else {
            snapshots.deleteTodaysRowsOfUser(onlyUser.toString());
        }

        Instant now = Instant.now();
        Map<UUID, Integer> redNewByUser = new LinkedHashMap<>();
        int red = 0;
        for (ConflictDraft d : drafts) {
            ConflictSnapshot row = new ConflictSnapshot();
            row.setKind(d.kind());
            row.setPayload(toPayload(d));
            row.setDetectedAt(now);
            snapshots.save(row);
            if (ConflictSnapshot.SEVERITY_RED.equals(d.severity())) {
                red++;
                if (d.userId() != null && !prevRed.contains(d.fp())) {
                    redNewByUser.merge(d.userId(), 1, Integer::sum);
                }
            }
        }

        // 红色新增 → conflict.detected（05 §5.1 W2 增订行：payload{userId,redCount}）
        redNewByUser.forEach((userId, count) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId.toString());
            payload.put("redCount", count);
            outbox.append("user", userId, "conflict.detected", payload, null);
        });

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", drafts.size());
        summary.put("red", red);
        summary.put("yellow", drafts.size() - red);
        summary.put("redNew", redNewByUser.values().stream().mapToInt(Integer::intValue).sum());
        summary.put("notifiedUsers", redNewByUser.size());
        if (onlyUser != null || !drafts.isEmpty()) {
            log.info("[conflict-batch] scope={} total={} red={} redNew={} notified={}",
                    onlyUser == null ? "all" : onlyUser, drafts.size(), red,
                    summary.get("redNew"), redNewByUser.size());
        }
        return summary;
    }

    /**
     * 快照载荷（读端点原样透出；userId 缺省即 CF-3 产品级行）。
     * 结构化补充（尽力而为，键名固定）：window={start,end}（ISO yyyy-MM-dd，单日 start==end），
     * events=[{key,title,start,due}]——引擎拿不到即为 null/空表，序列化时整个键缺省。
     */
    private static Map<String, Object> toPayload(ConflictDraft d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("severity", d.severity());
        p.put("subjectType", d.subjectType());
        p.put("subjectId", d.subjectId().toString());
        if (d.userId() != null) {
            p.put("userId", d.userId().toString());
        }
        p.put("detail", d.detail());
        p.put("relatedTaskIds", d.relatedTaskIds().stream().map(UUID::toString).toList());
        if (d.windowStart() != null && d.windowEnd() != null) {
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("start", d.windowStart().toString());
            window.put("end", d.windowEnd().toString());
            p.put("window", window);
        }
        if (!d.events().isEmpty()) {
            List<Map<String, Object>> events = new ArrayList<>(d.events().size());
            for (ConflictDraft.Event e : d.events()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("key", e.key());
                if (e.title() != null) {
                    ev.put("title", e.title());
                }
                if (e.start() != null) {
                    ev.put("start", e.start().toString());
                }
                if (e.due() != null) {
                    ev.put("due", e.due().toString());
                }
                events.add(ev);
            }
            p.put("events", events);
        }
        p.put("fp", d.fp());
        return p;
    }
}
