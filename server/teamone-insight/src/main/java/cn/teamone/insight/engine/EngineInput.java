package cn.teamone.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 引擎输入（纯数据，无行为；装配自 insight 只读映射仓库）。
 *
 * @param openItems        开放工作项视图（status∉{done,closed,已关闭,回归通过} 且有 start/due）
 * @param allItemsById     全量工作项索引（CF-5 依赖对侧允许非开放状态，原型 workItemById 口径）
 * @param capacityByUser   当日容量（platform.app_user.daily_capacity_hours；缺人=原型 userById 空→跳过）
 * @param sprintEndById    迭代截止（prd.sprint.due_date；缺行/缺值由引擎取哨兵）
 * @param releases         版本全集（CF-3/CF-6；当事人增量模式传空清单=CF-3 产品级跳过）
 * @param workdays         工作日集合（platform.calendar is_workday——公式映射允许差异①）
 * @param blockedFromByTo  blocks 反查：toItemId → fromItemIds（relation='blocks'，红线⑤方向）
 * @param onlyUser         非 null=事件驱动增量（只重算该当事人）；CF-3 产品级冲突随之跳过
 */
public record EngineInput(
        List<ItemView> openItems,
        Map<UUID, ItemView> allItemsById,
        Map<UUID, BigDecimal> capacityByUser,
        Map<UUID, LocalDate> sprintEndById,
        List<ReleaseView> releases,
        Set<LocalDate> workdays,
        Map<UUID, List<UUID>> blockedFromByTo,
        UUID onlyUser) {

    public EngineInput {
        openItems = openItems == null ? List.of() : List.copyOf(openItems);
        allItemsById = allItemsById == null ? Map.of() : Map.copyOf(allItemsById);
        capacityByUser = capacityByUser == null ? Map.of() : Map.copyOf(capacityByUser);
        sprintEndById = sprintEndById == null ? Map.of() : Map.copyOf(sprintEndById);
        releases = releases == null ? List.of() : List.copyOf(releases);
        workdays = workdays == null ? Set.of() : Set.copyOf(workdays);
        blockedFromByTo = blockedFromByTo == null ? Map.of() : Map.copyOf(blockedFromByTo);
    }

    /** 等价便利构造（全量模式，onlyUser=null） */
    public EngineInput(List<ItemView> openItems, Map<UUID, ItemView> allItemsById,
                       Map<UUID, BigDecimal> capacityByUser, Map<UUID, LocalDate> sprintEndById,
                       List<ReleaseView> releases, Set<LocalDate> workdays,
                       Map<UUID, List<UUID>> blockedFromByTo) {
        this(openItems, allItemsById, capacityByUser, sprintEndById, releases, workdays, blockedFromByTo, null);
    }
}
