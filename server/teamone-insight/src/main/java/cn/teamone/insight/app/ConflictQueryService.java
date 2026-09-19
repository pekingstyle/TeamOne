package cn.teamone.insight.app;

import cn.teamone.insight.domain.InsightWorkItem;
import cn.teamone.insight.engine.ConflictEngine;
import cn.teamone.insight.engine.ItemView;
import cn.teamone.insight.repo.ConflictSnapshotRepository;
import cn.teamone.insight.repo.InsightCalendarRepository;
import cn.teamone.insight.repo.InsightWorkItemRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冲突读侧（05 §3.3 prd 段 GET /conflicts 登记的 insight 实现；INC-1 红线②：
 * 快照只读查询，读端点不实时计算判决）+ 负载热力图（02 FR-v2-04）。
 *
 * <p>热力图红线②：响应只有「人员×日负载小时」原料矩阵（amortize 实时聚合 work_item+calendar），
 * <b>不含任何 CF 判决字段</b>（无 severity/kind/容量比值判决——前端着色自行按原料渲染）。</p>
 */
@Service
public class ConflictQueryService {

    /** 快照查询上限（单批快照远小于此；防御全表翻页滥用） */
    private static final int SNAPSHOT_LIMIT = 500;

    private final ConflictSnapshotRepository snapshots;
    private final InsightWorkItemRepository workItems;
    private final InsightCalendarRepository calendars;

    public ConflictQueryService(ConflictSnapshotRepository snapshots,
                                InsightWorkItemRepository workItems,
                                InsightCalendarRepository calendars) {
        this.snapshots = snapshots;
        this.workItems = workItems;
        this.calendars = calendars;
    }

    // ==================== GET /conflicts?kind=&since= ====================

    /**
     * 快照查询（detected_at DESC）。
     *
     * @param kind  CF-1..CF-6 可选过滤
     * @param since detected_at 下界（可选，ISO-8601 instant）
     * @return {items:[{kind,payload,detectedAt,resolvedAt}]}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshots(String kind, Instant since) {
        Specification<cn.teamone.insight.domain.ConflictSnapshot> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (kind != null && !kind.isBlank()) {
                ps.add(cb.equal(root.get("kind"), kind));
            }
            if (since != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("detectedAt"), since));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        var rows = snapshots.findAll(spec,
                PageRequest.of(0, SNAPSHOT_LIMIT, Sort.by(Sort.Direction.DESC, "detectedAt")))
                .getContent();
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (var row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", row.getKind());
            m.put("payload", row.getPayload());
            m.put("detectedAt", row.getDetectedAt());
            m.put("resolvedAt", row.getResolvedAt());
            items.add(m);
        }
        return Map.of("items", items);
    }

    // ==================== GET /conflicts/heatmap?days=90 ====================

    /**
     * 人员×日负载小时矩阵（原料与批算步骤 1 同源：{@link ConflictEngine#amortize}）。
     * cells 只含负载 &gt; 0 的格子（50 人×90 天无负载格子不占带宽）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> heatmap(int days) {
        int safeDays = Math.min(Math.max(days, 1), 180);
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(safeDays - 1L);

        Set<LocalDate> workdays = new LinkedHashSet<>();
        calendars.findAll().forEach(c -> {
            if (c.isWorkday()) {
                workdays.add(c.getCalDate());
            }
        });

        // open 口径与批算装配一致（ConflictEngine.isOpen：完结四态排除；requirement 不参与）
        List<ItemView> open = new ArrayList<>();
        for (InsightWorkItem w : workItems.findAll()) {
            if ("requirement".equals(w.getType())
                    || !ConflictEngine.isOpen(w.getStatus(), w.getStartDate(), w.getDueDate())
                    || w.getEstimateHours() == null || w.getAssigneeId() == null) {
                continue;
            }
            open.add(new ItemView(w.getId(), w.getKey(), w.getAssigneeId(), w.getProductId(),
                    w.getSprintId(), w.getReleaseId(), w.getStatus(), w.getStartDate(),
                    w.getDueDate(), w.getEstimateHours()));
        }
        Map<UUID, Map<LocalDate, BigDecimal>> load = ConflictEngine.amortize(open, workdays);

        List<String> dayList = new ArrayList<>(safeDays);
        for (LocalDate d = today; !d.isAfter(end); d = d.plusDays(1)) {
            dayList.add(d.toString());
        }

        List<Map<String, Object>> users = new ArrayList<>();
        List<Map<String, Object>> cells = new ArrayList<>();
        for (Map.Entry<UUID, Map<LocalDate, BigDecimal>> e : load.entrySet()) {
            users.add(Map.of("userId", e.getKey().toString()));
            for (LocalDate d = today; !d.isAfter(end); d = d.plusDays(1)) {
                BigDecimal h = e.getValue().get(d);
                if (h != null && h.signum() > 0) {
                    cells.add(Map.of(
                            "userId", e.getKey().toString(),
                            "date", d.toString(),
                            "hours", h.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", today.toString());
        out.put("users", users);
        out.put("days", dayList);
        out.put("cells", cells);
        return out;
    }
}
