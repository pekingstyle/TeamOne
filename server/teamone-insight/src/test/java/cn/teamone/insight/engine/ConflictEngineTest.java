package cn.teamone.insight.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲突纯函数基线单测（红线③：原型基线先行——用例取值=原型 store.ts 演示数据同构场景，
 * 阈值 0.85 / &lt;7 / 哨兵 9999-12-31 逐字固化；公式依据 02 §三 + 05 §6.1）。
 *
 * <p>日历基线：2026-09-07(一)~2026-09-11(五) 五个工作日 + 2026-09-12/13 周末 +
 * 2026-10-01~03 国庆（V8 种子同构），模拟 calendar 差异映射①。</p>
 */
class ConflictEngineTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 7);  // 周一
    static final LocalDate D2 = LocalDate.of(2026, 9, 8);
    static final LocalDate D3 = LocalDate.of(2026, 9, 9);
    static final LocalDate D4 = LocalDate.of(2026, 9, 10);
    static final LocalDate D5 = LocalDate.of(2026, 9, 11); // 周五
    static final LocalDate SAT = LocalDate.of(2026, 9, 12);
    static final LocalDate SUN = LocalDate.of(2026, 9, 13);

    static final Set<LocalDate> WORKDAYS = Set.of(D1, D2, D3, D4, D5,
            LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15),
            LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18));

    static final UUID U1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID U2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID S2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final UUID R1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID R2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    static final UUID R3 = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    static ItemView item(String key, UUID assignee, UUID product, UUID sprint, UUID release,
                         String status, LocalDate start, LocalDate due, String estimate) {
        return new ItemView(UUID.nameUUIDFromBytes(key.getBytes()), key, assignee, product,
                sprint, release, status, start, due, estimate == null ? null : new BigDecimal(estimate));
    }

    static EngineInput input(List<ItemView> open, List<ItemView> all,
                             Map<UUID, BigDecimal> caps, Map<UUID, LocalDate> sprintEnds,
                             List<ReleaseView> releases, Map<UUID, List<UUID>> blocks) {
        Map<UUID, ItemView> byId = new java.util.LinkedHashMap<>();
        all.forEach(t -> byId.put(t.id(), t));
        return new EngineInput(open, byId, caps, sprintEnds, releases, WORKDAYS, blocks);
    }

    static EngineInput emptyInput(List<ItemView> open) {
        return input(open, open, Map.of(U1, BigDecimal.valueOf(8)), Map.of(), List.of(), Map.of());
    }

    // ==================== CF-1 人员超载（红/黄 + 0.85 阈值） ====================

    @Test
    void cf1_overload_red_when_daily_load_exceeds_capacity() {
        // 单工作日 24h > 8h → red（原型 24 > 8；单日一行，逐日独立判决）
        ItemView t = item("T-1", U1, P1, null, null, "todo", D1, D1, "24");
        List<ConflictDraft> out = ConflictEngine.compute(emptyInput(List.of(t)));
        assertThat(out).hasSize(1);
        ConflictDraft c = out.get(0);
        assertThat(c.kind()).isEqualTo("CF-1");
        assertThat(c.severity()).isEqualTo("red");
        assertThat(c.subjectType()).isEqualTo("user");
        assertThat(c.userId()).isEqualTo(U1);
        assertThat(c.detail()).contains("24.0h > 容量 8h").contains("09-07");
        assertThat(c.relatedTaskIds()).containsExactly(t.id());
    }

    @Test
    void cf1_yellow_at_85pct_boundary_and_below_red() {
        // 单日 8.0h：不 > 8（非红），但 8 ≥ 8×0.85=6.8 → yellow（阈值 0.85 逐字）
        ItemView t = item("T-1", U1, P1, null, null, "todo", D1, D1, "8");
        List<ConflictDraft> out = ConflictEngine.compute(emptyInput(List.of(t)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo("CF-1");
        assertThat(out.get(0).severity()).isEqualTo("yellow");
        assertThat(out.get(0).detail()).contains("8.0h / 8h（≥85%）");
        assertThat(out.get(0).relatedTaskIds()).isEmpty(); // 原型黄色 relatedTaskIds=[]
    }

    @Test
    void cf1_quiet_load_produces_no_conflict() {
        // 16h 摊 5 个工作日 = 3.2h/日 < 6.8 → 无冲突（无冲突基线组）
        ItemView t = item("T-1", U1, P1, null, null, "todo", D1, D5, "16");
        assertThat(ConflictEngine.compute(emptyInput(List.of(t)))).isEmpty();
    }

    @Test
    void cf1_amortization_keeps_full_precision_before_threshold_compare() {
        // MF（质量审查⑤）：摊销必须累加前保持高精度——单日两任务 4.21h+4.2h：
        //   原型浮点全精度 8.41 > 8 → red；低 scale(1) 预舍入 4.2+4.2=8.4 不会 >8 → 误判 yellow（判决翻转）
        ItemView a = item("T-1", U1, P1, null, null, "todo", D1, D1, "4.21");
        ItemView b = item("T-2", U1, P1, null, null, "todo", D1, D1, "4.2");
        List<ConflictDraft> out = ConflictEngine.compute(emptyInput(List.of(a, b)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo("CF-1");
        assertThat(out.get(0).severity()).isEqualTo("red"); // 低精度实现只会给 yellow
    }

    // ==================== CF-4 跨产品争用 ====================

    @Test
    void cf4_overload_day_spanning_two_products_beats_cf1() {
        // 同一超载日两个任务（8h+8h=16h > 8h）跨 P1/P2 → CF-4 red（替代 CF-1）
        ItemView a = item("T-1", U1, P1, null, null, "todo", D1, D1, "8");
        ItemView b = item("T-2", U1, P2, null, null, "todo", D1, D1, "8");
        List<ConflictDraft> out = ConflictEngine.compute(emptyInput(List.of(a, b)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo("CF-4");
        assertThat(out.get(0).severity()).isEqualTo("red");
        assertThat(out.get(0).detail()).contains("跨产品负载 16.0h > 容量 8h");
        assertThat(out.get(0).relatedTaskIds()).containsExactly(a.id(), b.id());
    }

    // ==================== CF-2 时间区间重叠 ====================

    @Test
    void cf2_red_for_overlapping_items_in_different_sprints() {
        ItemView a = item("T-1", U1, P1, S1, null, "todo", D1, D3, "8");
        ItemView b = item("T-2", U1, P1, S2, null, "todo", D3, D5, "8"); // D3 相交、跨迭代
        List<ConflictDraft> out = ConflictEngine.compute(emptyInput(List.of(a, b)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo("CF-2");
        assertThat(out.get(0).severity()).isEqualTo("red");
        assertThat(out.get(0).detail()).contains("「T-1」与「T-2」时间区间重叠（跨迭代）");
        assertThat(out.get(0).relatedTaskIds()).containsExactly(a.id(), b.id());
    }

    @Test
    void cf2_red_when_both_in_progress_even_same_sprint() {
        ItemView a = item("T-1", U1, P1, S1, null, "in_progress", D1, D3, "8");
        ItemView b = item("T-2", U1, P1, S1, null, "in_progress", D1, D3, "8"); // 同迭代、双进行中
        List<ConflictDraft> out = ConflictEngine.compute(emptyInput(List.of(a, b)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).detail()).contains("（同时进行中）");
    }

    @Test
    void cf2_ignores_same_sprint_non_active_pair() {
        // 同迭代且非双进行中 → 不命中（原型 overlap&&(diff||both) 逐字）
        ItemView a = item("T-1", U1, P1, S1, null, "todo", D1, D3, "8");
        ItemView b = item("T-2", U1, P1, S1, null, "todo", D1, D3, "8");
        assertThat(ConflictEngine.compute(emptyInput(List.of(a, b)))).isEmpty();
    }

    // ==================== CF-3 里程碑挤压（自然日 &lt;7） ====================

    @Test
    void cf3_yellow_when_freeze_within_6_natural_days_of_previous_release() {
        // r1 发布 09-09 → r2 冻结 09-10：自然日 gap=1 < 7 → yellow（<7 逐字）
        ReleaseView r1 = new ReleaseView(R1, "v2.4.0", "v2.4.0", P1,
                D3, LocalDate.of(2026, 9, 1));
        ReleaseView r2 = new ReleaseView(R2, "v2.5.0", "v2.5.0", P1,
                LocalDate.of(2026, 10, 20), D4);
        EngineInput in = input(List.of(), List.of(), Map.of(U1, BigDecimal.valueOf(8)),
                Map.of(), List.of(r1, r2), Map.of());
        List<ConflictDraft> out = ConflictEngine.compute(in);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo("CF-3");
        assertThat(out.get(0).severity()).isEqualTo("yellow");
        assertThat(out.get(0).subjectType()).isEqualTo("release");
        assertThat(out.get(0).subjectId()).isEqualTo(R2);
        assertThat(out.get(0).detail()).contains("仅 1 天（< 7 天）");
    }

    @Test
    void cf3_quiet_when_gap_is_seven_or_more_days() {
        // gap=7 → 不命中（边界：<7 严格）
        ReleaseView r1 = new ReleaseView(R1, "v2.4.0", "v2.4.0", P1,
                LocalDate.of(2026, 9, 10), null);
        ReleaseView r2 = new ReleaseView(R2, "v2.5.0", "v2.5.0", P1,
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 9, 17));
        EngineInput in = input(List.of(), List.of(), Map.of(U1, BigDecimal.valueOf(8)),
                Map.of(), List.of(r1, r2), Map.of());
        assertThat(ConflictEngine.compute(in)).isEmpty();
    }

    // ==================== CF-5 依赖倒挂（blocks: from 阻塞 to） ====================

    @Test
    void cf5_red_when_blocked_item_due_before_its_blocker() {
        // T-1(to) 被 T-2(from) 阻塞：to.due(D1) < from.due(D3) → 倒挂 red（主体=to）
        // U1 容量给 16（8h 负载不触 CF-1 黄，保持单变量）
        ItemView to = item("T-1", U1, P1, S1, null, "todo", D1, D1, "8");
        ItemView from = item("T-2", U2, P1, S1, null, "todo", D1, D3, "8");
        EngineInput in = input(List.of(to), List.of(to, from), Map.of(U1, BigDecimal.valueOf(16)),
                Map.of(), List.of(), Map.of(to.id(), List.of(from.id())));
        List<ConflictDraft> out = ConflictEngine.compute(in);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo("CF-5");
        assertThat(out.get(0).severity()).isEqualTo("red");
        assertThat(out.get(0).subjectType()).isEqualTo("task");
        assertThat(out.get(0).subjectId()).isEqualTo(to.id());
        assertThat(out.get(0).detail()).contains("「T-1」截止晚于其依赖「T-2」的截止");
    }

    @Test
    void cf5_red_when_sprint_of_blocked_ends_before_blocker_sprint() {
        // 截止日不倒挂但迭代倒挂：end(to.sprint) < end(from.sprint) → red
        ItemView to = item("T-1", U1, P1, S1, null, "todo", D1, D3, "8");
        ItemView from = item("T-2", U2, P1, S2, null, "todo", D1, D3, "8");
        EngineInput in = input(List.of(to), List.of(to, from), Map.of(U1, BigDecimal.valueOf(8)),
                Map.of(S1, D3, S2, D5), List.of(), Map.of(to.id(), List.of(from.id())));
        assertThat(ConflictEngine.compute(in)).hasSize(1);
    }

    @Test
    void cf5_sentinel_9999_when_sprint_end_missing() {
        // from.sprint 无 due_date → 哨兵 9999-12-31（逐字）；end(S1)<9999 → 倒挂成立
        ItemView to = item("T-1", U1, P1, S1, null, "todo", D1, D3, "8");
        ItemView from = item("T-2", U2, P1, S2, null, "todo", D1, D3, "8");
        EngineInput in = input(List.of(to), List.of(to, from), Map.of(U1, BigDecimal.valueOf(8)),
                Map.of(S1, D3), List.of(), Map.of(to.id(), List.of(from.id())));
        assertThat(ConflictEngine.compute(in)).hasSize(1);
    }

    // ==================== CF-6 Deadline 越级 ====================

    @Test
    void cf6_red_when_due_after_sprint_end() {
        ItemView t = item("T-1", U1, P1, S1, null, "todo", D1, D5, "8");
        EngineInput in = input(List.of(t), List.of(t), Map.of(U1, BigDecimal.valueOf(8)),
                Map.of(S1, D3), List.of(), Map.of());
        List<ConflictDraft> out = ConflictEngine.compute(in);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo("CF-6");
        assertThat(out.get(0).detail()).contains("截止 2026-09-11 晚于迭代截止 2026-09-09");
    }

    @Test
    void cf6_red_when_due_after_release_plan_date() {
        ItemView t = item("T-1", U1, P1, null, R1, "todo", D1, D5, "8");
        ReleaseView r1 = new ReleaseView(R1, "v2.4.0", "v2.4.0", P1, D3, null);
        EngineInput in = input(List.of(t), List.of(t), Map.of(U1, BigDecimal.valueOf(8)),
                Map.of(), List.of(r1), Map.of());
        List<ConflictDraft> out = ConflictEngine.compute(in);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).detail()).contains("晚于版本发布日 2026-09-09");
    }

    // ==================== 排序与输入口径 ====================

    @Test
    void sort_red_first_then_kind_lexicographic() {
        ItemView overload = item("T-1", U1, P1, null, null, "todo", D1, D1, "24"); // CF-1 red
        ReleaseView r1 = new ReleaseView(R1, "v2.4.0", "v2.4.0", P1, D3, null);
        ReleaseView r2 = new ReleaseView(R2, "v2.5.0", "v2.5.0", P1, D4, D4);      // gap=1 <7 → CF-3
        EngineInput in = input(List.of(overload), List.of(overload),
                Map.of(U1, BigDecimal.valueOf(8)),
                Map.of(), List.of(r1, r2), Map.of());
        List<ConflictDraft> out = ConflictEngine.compute(in);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).kind()).isEqualTo("CF-1"); // red 优先
        assertThat(out.get(0).severity()).isEqualTo("red");
        assertThat(out.get(1).kind()).isEqualTo("CF-3"); // 黄在后
        assertThat(out.get(1).severity()).isEqualTo("yellow");
    }

    @Test
    void finished_or_dateless_items_are_excluded_from_open() {
        // 完结四态与缺日期行经装配过滤后不会进入 openItems——本组直接断言空输入仍无冲突，
        // 过滤判定本体见 open_filter_excludes_finished_and_dateless（ConflictEngine.isOpen）
        assertThat(ConflictEngine.compute(emptyInput(List.of()))).isEmpty();
    }

    @Test
    void only_user_mode_skips_product_level_cf3() {
        // 当事人增量模式：CF-3 产品级跳过（同输入只把 onlyUser 置位）
        ReleaseView r1 = new ReleaseView(R1, "v2.4.0", "v2.4.0", P1, D3, null);
        ReleaseView r2 = new ReleaseView(R2, "v2.5.0", "v2.5.0", P1, D4, D4);
        EngineInput full = input(List.of(), List.of(), Map.of(), Map.of(), List.of(r1, r2), Map.of());
        assertThat(ConflictEngine.compute(full)).hasSize(1);

        EngineInput scoped = new EngineInput(List.of(), Map.of(), Map.of(), Map.of(),
                List.of(r1, r2), WORKDAYS, Map.of(), U1);
        assertThat(ConflictEngine.compute(scoped)).isEmpty();
    }

    @Test
    void workdays_between_excludes_weekend_and_seed_holiday() {
        // 09-07(一)~09-18(五)：剔除周末（两周 × 5 工作日）
        List<LocalDate> span = ConflictEngine.workdaysBetween(D1, LocalDate.of(2026, 9, 18), WORKDAYS);
        assertThat(span).hasSize(10);
        assertThat(span).noneMatch(d -> d.getDayOfWeek().getValue() >= 6);
        // 国庆周：V8 种子把 10-01~03 UPDATE 为非工作日（is_workday=false 不进集合）
        // → 引擎只认集合，10-01~03 自然被剔（差异① workdays→calendar 的等价验证）
        Set<LocalDate> octoberCalendar = Set.of(
                LocalDate.of(2026, 9, 30),                       // 周三，工作日
                // 10-01(四)/10-02(五)/10-03(六)：种子节假日，不在集合
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6));
        List<LocalDate> oct = ConflictEngine.workdaysBetween(
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 7), octoberCalendar);
        assertThat(oct).containsExactly(LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6));
    }

    @Test
    void open_filter_excludes_finished_and_dateless() {
        // 完结四态（done/closed/已关闭/回归通过）与缺 start/due 不进 open（02 §三口径，
        // 装配共用 ConflictEngine.isOpen）
        assertThat(ConflictEngine.isOpen("todo", D1, D5)).isTrue();
        assertThat(ConflictEngine.isOpen("in_progress", D1, D5)).isTrue();
        assertThat(ConflictEngine.isOpen("修复中", D1, D5)).isTrue();
        assertThat(ConflictEngine.isOpen("done", D1, D5)).isFalse();
        assertThat(ConflictEngine.isOpen("closed", D1, D5)).isFalse();
        assertThat(ConflictEngine.isOpen("已关闭", D1, D5)).isFalse();
        assertThat(ConflictEngine.isOpen("回归通过", D1, D5)).isFalse();
        assertThat(ConflictEngine.isOpen("todo", null, D5)).isFalse();
        assertThat(ConflictEngine.isOpen("todo", D1, null)).isFalse();
    }
}
