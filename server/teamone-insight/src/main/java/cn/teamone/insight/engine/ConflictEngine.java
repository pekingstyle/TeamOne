package cn.teamone.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 冲突批算纯函数核（02 FR-v2-04 CF-1~CF-6；原型 web/src/data/store.ts computeConflicts()
 * 逐行平移——阈值/哨兵/比较符逐字一致，单测基线 ConflictEngineTest 固化）。
 *
 * <p><b>公式映射差异表（方向审查只允许两处，其余逐字）</b>：</p>
 * <table>
 *   <tr><th>原型</th><th>真实系统</th></tr>
 *   <tr><td>workdays(start,end)=剔周末+HOLIDAYS 内存集</td>
 *       <td>platform.calendar is_workday（差异①，V8 种子 2026-2027+国庆三天）</td></tr>
 *   <tr><td>HOLIDAYS=new Set('2026-10-01/02/03')</td>
 *       <td>V8 种子 UPDATE（差异②，可运维 UPDATE 维护）</td></tr>
 * </table>
 *
 * <p>其余映射：u.dailyCapacityHours→app_user.daily_capacity_hours；detail 文案中人名以
 * userId/business key 表达（只读白名单不含 display_name，不影响判决）；status 完结集
 * {done,closed,已关闭,回归通过} 按 02 §三方向审查定稿（修正原型把中文完结态漏进 open 的笔误）。</p>
 */
public final class ConflictEngine {

    /** 黄色预警阈值：h ≥ cap×0.85（原型 `h >= cap * 0.85`，逐字） */
    public static final BigDecimal YELLOW_RATIO = new BigDecimal("0.85");
    /** CF-3 挤压阈值：gap < 7 天（自然日口径，原型 daysBetween 逐字） */
    public static final int CF3_SQUEEZE_DAYS = 7;
    /** 迭代截止哨兵：sprint 缺行/缺 due_date 时（原型 `?? '9999-12-31'`，逐字） */
    public static final LocalDate SPRINT_END_SENTINEL = LocalDate.of(9999, 12, 31);
    /** 原型「进行中」字面量（CF-2 bothActive 判定用，逐字） */
    public static final String STATUS_IN_PROGRESS = "in_progress";

    /** kind 常量（=prd.conflict_snapshot.kind，V7 CHECK） */
    public static final String K_CF1 = "CF-1";
    public static final String K_CF2 = "CF-2";
    public static final String K_CF3 = "CF-3";
    public static final String K_CF4 = "CF-4";
    public static final String K_CF5 = "CF-5";
    public static final String K_CF6 = "CF-6";

    /**
     * open 口径（02 §三方向审查定稿，逐字）：status∉{done,closed,已关闭,回归通过} 且 start/due 齐备。
     * 原型在 computeConflicts 内 filter；真实系统该判定由批算/热力图装配共用（同一实现，不漂移）。
     */
    public static boolean isOpen(String status, LocalDate startDate, LocalDate dueDate) {
        return !STATUS_FINISHED.contains(status) && startDate != null && dueDate != null;
    }

    /** 原型 open filter 的完结集（done/closed 英文 + defect 中文两态） */
    private static final Set<String> STATUS_FINISHED = Set.of("done", "closed", "已关闭", "回归通过");

    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MM-dd");

    private ConflictEngine() {
    }

    /**
     * 主入口（纯函数，无 IO 无时钟）：输入 {@link EngineInput} → 判决草稿清单。
     * 排序=红色优先、同级按 kind 字典序（原型 sort 平移，type 换 kind，方向审查定稿）。
     */
    public static List<ConflictDraft> compute(EngineInput in) {
        List<ConflictDraft> items = new ArrayList<>();

        // ---- 步骤 1：工时摊平（原型步骤 1：per = estimateHours / workdays(start,due).length） ----
        Map<UUID, Map<LocalDate, BigDecimal>> load = amortize(in.openItems(), in.workdays());

        // ---- CF-1 人员超载 / CF-4 跨产品争用（原型同一循环） ----
        for (Map.Entry<UUID, Map<LocalDate, BigDecimal>> e : load.entrySet()) {
            UUID uid = e.getKey();
            BigDecimal cap = in.capacityByUser().get(uid);
            if (cap == null) {
                continue; // 原型 userById 缺人 continue
            }
            List<ItemView> mine = openOfUser(in.openItems(), uid);
            for (Map.Entry<LocalDate, BigDecimal> day : e.getValue().entrySet()) {
                LocalDate d = day.getKey();
                BigDecimal h = day.getValue();
                if (h.compareTo(cap) > 0) {
                    List<ItemView> spanning = spanning(mine, d);
                    Set<UUID> prods = new LinkedHashSet<>();
                    spanning.forEach(t -> {
                        if (t.productId() != null) {
                            prods.add(t.productId());
                        }
                    });
                    if (prods.size() >= 2) {
                        items.add(new ConflictDraft(K_CF4, "red", "user", uid, uid,
                                uid + " " + mmdd(d) + " 跨产品负载 " + h(h) + "h > 容量 " + cap + "h（"
                                        + String.join(" + ", prods.stream().map(UUID::toString).toList()) + "）",
                                spanning.stream().map(ItemView::id).toList(),
                                d, d, eventsOf(spanning),
                                K_CF4 + ":" + uid + ":" + d));
                    } else {
                        items.add(new ConflictDraft(K_CF1, "red", "user", uid, uid,
                                uid + " " + mmdd(d) + " 负载 " + h(h) + "h > 容量 " + cap + "h",
                                spanning.stream().map(ItemView::id).toList(),
                                d, d, eventsOf(spanning),
                                K_CF1 + ":" + uid + ":" + d));
                    }
                } else if (h.compareTo(cap.multiply(YELLOW_RATIO)) >= 0) {
                    items.add(new ConflictDraft(K_CF1, "yellow", "user", uid, uid,
                            uid + " " + mmdd(d) + " 负载 " + h(h) + "h / " + cap + "h（≥85%）",
                            List.of(),
                            d, d, List.of(),
                            K_CF1 + ":" + uid + ":" + d + ":y"));
                }
            }
        }

        // ---- CF-2 时间区间重叠（同人两个未完成任务：分属不同迭代 或 均为进行中） ----
        for (UUID uid : usersOf(in.openItems())) {
            List<ItemView> ts = openOfUser(in.openItems(), uid).stream()
                    .sorted(Comparator.comparing(ItemView::startDate).thenComparing(ItemView::id))
                    .toList();
            for (int i = 0; i < ts.size() - 1; i++) {
                for (int j = i + 1; j < ts.size(); j++) {
                    ItemView a = ts.get(i);
                    ItemView b = ts.get(j);
                    boolean overlap = !a.startDate().isAfter(b.dueDate()) && !b.startDate().isAfter(a.dueDate());
                    boolean differentSprint = !Objects.equals(a.sprintId(), b.sprintId());
                    boolean bothActive = STATUS_IN_PROGRESS.equals(a.status()) && STATUS_IN_PROGRESS.equals(b.status());
                    if (overlap && (differentSprint || bothActive) && !a.id().equals(b.id())) {
                        // 窗口取两区间的事实交集（overlap 判定已保证非空）
                        LocalDate winStart = a.startDate().isAfter(b.startDate()) ? a.startDate() : b.startDate();
                        LocalDate winEnd = a.dueDate().isBefore(b.dueDate()) ? a.dueDate() : b.dueDate();
                        items.add(new ConflictDraft(K_CF2, "red", "user", uid, uid,
                                uid + "：「" + a.key() + "」与「" + b.key() + "」时间区间重叠（"
                                        + (differentSprint ? "跨迭代" : "同时进行中") + "）",
                                List.of(a.id(), b.id()),
                                winStart, winEnd, eventsOf(a, b),
                                K_CF2 + ":" + uid + ":" + a.id() + ":" + b.id()));
                    }
                }
            }
        }

        // ---- CF-3 里程碑挤压（同产品相邻发布：r2 冻结距 r1 发布 < 7 天，自然日口径） ----
        // 当事人增量模式（onlyUser≠null）跳过：产品级冲突不随单人重算重写，只由每日全量维护
        if (in.onlyUser() == null) {
            Map<UUID, List<ReleaseView>> byProduct = new LinkedHashMap<>();
            for (ReleaseView r : in.releases()) {
                if (r.productId() != null && r.planDate() != null) {
                    byProduct.computeIfAbsent(r.productId(), k -> new ArrayList<>()).add(r);
                }
            }
            for (List<ReleaseView> arr : byProduct.values()) {
                List<ReleaseView> sorted = arr.stream()
                        .sorted(Comparator.comparing(ReleaseView::planDate).thenComparing(ReleaseView::id))
                        .toList();
                for (int i = 0; i < sorted.size() - 1; i++) {
                    ReleaseView r1 = sorted.get(i);
                    ReleaseView r2 = sorted.get(i + 1);
                    if (r1.planDate() == null || r2.codeFreezeDate() == null) {
                        continue; // 原型 daysBetween(NaN)<7 恒 false 的等价守卫
                    }
                    long gap = ChronoUnit.DAYS.between(r1.planDate(), r2.codeFreezeDate()); // 自然日
                    if (gap < CF3_SQUEEZE_DAYS) {
                        items.add(new ConflictDraft(K_CF3, "yellow", "release", r2.id(), null,
                                r2.name() + " 冻结（" + mmdd(r2.codeFreezeDate()) + "）距 " + r1.name()
                                        + " 发布（" + mmdd(r1.planDate()) + "）仅 " + Math.max(gap, 0) + " 天（< 7 天）",
                                List.of(),
                                r1.planDate(), r2.codeFreezeDate(), List.of(),
                                K_CF3 + ":" + r2.id() + ":" + r1.id()));
                    }
                }
            }
        }

        // ---- CF-5 依赖倒挂（blocks 方向 from 阻塞 to，红线⑤；对侧取全量索引） ----
        for (ItemView t : in.openItems()) {
            for (UUID fromId : in.blockedFromByTo().getOrDefault(t.id(), List.of())) {
                ItemView b = in.allItemsById().get(fromId);
                if (b == null) {
                    continue;
                }
                boolean dueInversion = t.dueDate() != null && b.dueDate() != null
                        && t.dueDate().isBefore(b.dueDate());
                boolean sprintInversion = t.sprintId() != null && b.sprintId() != null
                        && sprintEnd(in, t.sprintId()).isBefore(sprintEnd(in, b.sprintId()));
                if (dueInversion || sprintInversion) {
                    items.add(new ConflictDraft(K_CF5, "red", "task", t.id(), t.assigneeId(),
                            "「" + t.key() + "」截止晚于其依赖「" + b.key() + "」的截止",
                            List.of(t.id(), b.id()),
                            t.startDate(), t.dueDate(), eventsOf(t, b),
                            K_CF5 + ":" + t.id() + ":" + b.id()));
                }
            }
        }

        // ---- CF-6 Deadline 越级（先迭代后版本，原型 which 取向逐字） ----
        for (ItemView t : in.openItems()) {
            LocalDate se = t.sprintId() != null ? sprintEnd(in, t.sprintId()) : null;
            LocalDate rp = t.releaseId() != null ? releasePlan(in.releases(), t.releaseId()) : null;
            if (t.dueDate() != null && ((se != null && t.dueDate().isAfter(se))
                    || (rp != null && t.dueDate().isAfter(rp)))) {
                String which = se != null && t.dueDate().isAfter(se)
                        ? "迭代截止 " + se
                        : "版本发布日 " + rp;
                items.add(new ConflictDraft(K_CF6, "red", "task", t.id(), t.assigneeId(),
                        "「" + t.key() + "」截止 " + t.dueDate() + " 晚于" + which,
                        List.of(t.id()),
                        t.startDate(), t.dueDate(), eventsOf(t),
                        K_CF6 + ":" + t.id()));
            }
        }

        // red 优先，同级 kind 字典序（Java sort 稳定，同原型）
        items.sort((a, b) -> a.severity().equals(b.severity())
                ? a.kind().compareTo(b.kind())
                : a.severity().equals("red") ? -1 : 1);
        return items;
    }

    /**
     * 工时摊平（步骤 1 独立出口，热力图实时聚合复用同一实现——原料口径恒一致）。
     * estimateHours=null 或负责人为空的行不参与负载（无摊销原料，等价原型无该字段场景）。
     */
    public static Map<UUID, Map<LocalDate, BigDecimal>> amortize(List<ItemView> open, Set<LocalDate> workdays) {
        Map<UUID, Map<LocalDate, BigDecimal>> load = new LinkedHashMap<>();
        for (ItemView t : open) {
            if (t.assigneeId() == null || t.estimateHours() == null
                    || t.startDate() == null || t.dueDate() == null) {
                continue;
            }
            List<LocalDate> span = workdaysBetween(t.startDate(), t.dueDate(), workdays);
            if (span.isEmpty()) {
                continue; // 原型 `if (span.length === 0) continue`
            }
            BigDecimal per = t.estimateHours().divide(BigDecimal.valueOf(span.size()), 6, RoundingMode.HALF_UP); // MF：累加前高精度（原型浮点全精度，仅显示舍入——低 scale 边界可翻转黄/红判决）
            Map<LocalDate, BigDecimal> byDay = load.computeIfAbsent(t.assigneeId(), k -> new LinkedHashMap<>());
            for (LocalDate d : span) {
                byDay.merge(d, per, BigDecimal::add);
            }
        }
        return load;
    }

    /** 工作日区间（含头尾；原型 workdays() 平移，周末+节假日判定外包给 calendar 集合） */
    public static List<LocalDate> workdaysBetween(LocalDate start, LocalDate end, Set<LocalDate> workdays) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (workdays.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    // ==================== 内部（原型助手平移） ====================

    /** 原型 sprintEnd()：缺行/缺值取哨兵 '9999-12-31'（逐字） */
    private static LocalDate sprintEnd(EngineInput in, UUID sprintId) {
        return in.sprintEndById().getOrDefault(sprintId, SPRINT_END_SENTINEL);
    }

    private static LocalDate releasePlan(List<ReleaseView> releases, UUID releaseId) {
        return releases.stream()
                .filter(r -> releaseId.equals(r.id()) && r.planDate() != null)
                .map(ReleaseView::planDate)
                .findFirst()
                .orElse(null);
    }

    private static List<ItemView> openOfUser(List<ItemView> open, UUID uid) {
        return open.stream().filter(t -> uid.equals(t.assigneeId())).toList();
    }

    private static List<UUID> usersOf(List<ItemView> open) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (ItemView t : open) {
            if (t.assigneeId() != null) {
                ids.add(t.assigneeId());
            }
        }
        return List.copyOf(ids);
    }

    /** 当日跨度内的本人任务（原型 `t.startDate <= d && t.dueDate >= d` 过滤） */
    private static List<ItemView> spanning(List<ItemView> mine, LocalDate d) {
        return mine.stream()
                .filter(t -> !t.startDate().isAfter(d) && !t.dueDate().isBefore(d))
                .toList();
    }

    /**
     * events 结构化行（契约键名 key/title/start/due）：全部取工作项原值，
     * 缺则 null（绝不编造）；黄色 CF-1 无关联任务即空表 → payload 缺省 events。
     */
    private static List<ConflictDraft.Event> eventsOf(List<ItemView> items) {
        List<ConflictDraft.Event> out = new ArrayList<>(items.size());
        for (ItemView t : items) {
            out.add(new ConflictDraft.Event(t.key(), t.title(), t.startDate(), t.dueDate()));
        }
        return out;
    }

    private static List<ConflictDraft.Event> eventsOf(ItemView... items) {
        return eventsOf(List.of(items));
    }

    /** toFixed(1) 等价（摊销已 scale=1，直接输出） */
    private static String h(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** 原型 d.slice(5)（MM-dd） */
    private static String mmdd(LocalDate d) {
        return MMDD.format(d);
    }
}
