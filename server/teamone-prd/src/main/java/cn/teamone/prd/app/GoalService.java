package cn.teamone.prd.app;

import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.StrategicGoal;
import cn.teamone.prd.repo.GoalOverviewAggView;
import cn.teamone.prd.repo.ItemReleaseView;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.RoadmapAggView;
import cn.teamone.prd.repo.RoadmapItemRepository;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.StrategicGoalRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 战略目标应用服务（M2-INC-1 W1：05 §3.3 prd 段 GET/POST /goals + GET /goals/{id}/rollup
 * + GET /goals/overview 全目标概览）。
 *
 * <p>rollup = 查询侧 COUNT FILTER 聚合（{@link RoadmapItemRepository#rollupByGoal}，
 * 红线条 ①：状态/进度不落冗余列）；行投影 {id, name, workItemDone, total, releaseIds}——
 * prd.roadmap_item 无业务 key 列（06 §3 W2 建表定稿），行不携带 key 字段。
 * releaseIds = 条目自身 release_id ∪ 其工作项 release_id ∪ 反向指向本条目的版本
 * （release.roadmap_item_id，双向关联链对齐；去重保序，全部来自查询）。</p>
 *
 * <p>鉴权：读端点登录态（Controller Actor.require）；写端点 service 内四步链——
 * goal 不挂产品，取平台级资源（uuid_nil）校验 product edit。</p>
 */
@Service
public class GoalService {

    /** 创建请求（controller 由请求体反序列化；引用字段接受 uuid 或业务键/用户名） */
    public record CreateSpec(String name, String description, String ownerId, String parentId) {}

    private final StrategicGoalRepository goals;
    private final RoadmapItemRepository roadmaps;
    private final WorkItemRepository workItems;
    private final ReleaseRepository releases;
    private final ProductRepository products;
    private final PermissionService permissions;
    private final Refs refs;

    public GoalService(StrategicGoalRepository goals, RoadmapItemRepository roadmaps,
                       WorkItemRepository workItems, ReleaseRepository releases,
                       ProductRepository products, PermissionService permissions, Refs refs) {
        this.goals = goals;
        this.roadmaps = roadmaps;
        this.workItems = workItems;
        this.releases = releases;
        this.products = products;
        this.permissions = permissions;
        this.refs = refs;
    }

    // ==================== 写（四步链鉴权在 service 内） ====================

    @Transactional
    public Map<String, Object> create(CreateSpec spec, UUID actorId) {
        permissions.require(actorId, "product", PermissionService.PLATFORM_RESOURCE_ID, "edit");
        StrategicGoal goal = new StrategicGoal();
        goal.setName(requireText(spec.name(), "name 必填"));
        goal.setDescription(spec.description());
        goal.setOwnerId(spec.ownerId() != null ? refs.userId(spec.ownerId()) : actorId);
        goal.setParentId(spec.parentId() != null ? refs.goal(spec.parentId()).getId() : null);
        return Views.of(goals.save(goal));
    }

    // ==================== 读（登录态） ====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return goals.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                .map(Views::of)
                .toList();
    }

    /** 哨兵行名（R-5 AC③ 直连口径）：直挂目标（无条目归属）的工作项汇总行，id=null 哨兵 */
    static final String DIRECT_SENTINEL_NAME = "直挂目标的工作项";

    /**
     * 目标进度下钻（05 §3.3 /goals/{id}/rollup）：
     * {goalId, roadmapItems:[{id, name, productId, productName, workItemDone, total, releaseIds}]}。
     * R-5/D1：条目必挂产品——行补 productId/productName 投影，GoalsPage 条目行渲染产品标签 +
     * 「按产品」过滤（纯前端；聚合链不动：进度仍走 rollupByGoal 查询侧 COUNT FILTER）。
     *
     * <p>R-5 AC③ 直连口径：rollupByGoal 行集含直挂工作项哨兵行（id=NULL，直挂目标、
     * 无条目归属）——哨兵行不摊入条目行（避免重复计数），在其后追加虚拟行输出，
     * <b>仅在计数 &gt; 0 时</b>；GoalsPage 树渲染为「直挂目标」分组（无下钻）。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> rollup(String id) {
        StrategicGoal goal = refs.goal(id);
        // 哨兵行 id=NULL（不能进按 id 索引的聚合映射），单独保存
        RoadmapAggView direct = null;
        Map<UUID, RoadmapAggView> agg = new LinkedHashMap<>();
        for (RoadmapAggView row : roadmaps.rollupByGoal(goal.getId())) {
            if (row.getId() == null) {
                direct = row;
            } else {
                agg.put(row.getId(), row);
            }
        }
        Map<UUID, Set<UUID>> itemReleases = workItems.workItemReleaseDistribution().stream()
                .collect(Collectors.groupingBy(ItemReleaseView::getItemId,
                        Collectors.mapping(ItemReleaseView::getReleaseId,
                                Collectors.toCollection(LinkedHashSet::new))));
        // 反向补链：release.roadmap_item_id 指向条目的版本也计入（v2.4.0 ↔ RM-1 双向关联）
        Map<UUID, Set<UUID>> releasesByItem = releases.findAll().stream()
                .filter(r -> r.getRoadmapItemId() != null)
                .collect(Collectors.groupingBy(Release::getRoadmapItemId,
                        Collectors.mapping(Release::getId,
                                Collectors.toCollection(LinkedHashSet::new))));
        // R-5：productName 投影缓存（本目标条目涉及的产品 id 集合很小，逐 id 查询即可）
        Map<UUID, String> productNames = new java.util.HashMap<>();

        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : roadmaps.findByGoalIdOrderByCreatedAtAsc(goal.getId())) {
            RoadmapAggView a = agg.get(row.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("name", row.getName());
            m.put("productId", row.getProductId());
            m.put("productName", row.getProductId() == null ? null
                    : productNames.computeIfAbsent(row.getProductId(),
                            pid -> products.findById(pid).map(p -> p.getName()).orElse(null)));
            m.put("workItemDone", a == null ? 0L : a.getDoneCount());
            m.put("total", a == null ? 0L : a.getTotal());
            LinkedHashSet<UUID> releaseIds = new LinkedHashSet<>();
            if (row.getReleaseId() != null) {
                releaseIds.add(row.getReleaseId());
            }
            releaseIds.addAll(itemReleases.getOrDefault(row.getId(), Set.of()));
            releaseIds.addAll(releasesByItem.getOrDefault(row.getId(), Set.of()));
            m.put("releaseIds", List.copyOf(releaseIds));
            items.add(m);
        }
        // R-5 AC③ 直挂哨兵行：仅在计数 > 0 时输出（无下钻、无产品/版本投影——直挂需求无条目归属）
        if (direct != null && direct.getTotal() > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", null);
            m.put("name", DIRECT_SENTINEL_NAME);
            m.put("productId", null);
            m.put("productName", null);
            m.put("workItemDone", direct.getDoneCount());
            m.put("total", direct.getTotal());
            m.put("releaseIds", List.of());
            items.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("goalId", goal.getId());
        res.put("roadmapItems", items);
        return res;
    }

    /**
     * 全目标概览（GET /goals/overview）：{goals:[{goalId, name, total, done, completionRate,
     * items:[{id, name, done, total, doneHours, inProgressHours, todoHours, releaseId,
     * taskCount, taskDone, requirementCount, requirementDone, defectCount, defectDone}]}]}。
     *
     * <p>total/done 走 roadmap_item 链（{@link RoadmapItemRepository#overviewByGoal}，与
     * rollup 同口径，恒限 task/test_task/defect 三类型）；hours = estimate_hours 按状态三桶
     * SUM FILTER（tooltip 用，裁决 D8 工时降级）；类型计数三桶（B3 · R-1，test_task 并入
     * 任务桶）= 桑基末端度量；没有条目的目标也返回（items 空数组），
     * completionRate = done/total 百分比 1 位小数（无条目 0.0）。</p>
     *
     * <p>R-5 AC③ 直连口径：overviewByGoal 行集含直挂工作项哨兵行（id=NULL）——不摊入
     * 条目行，条目行之后追加虚拟行（<b>仅在计数 &gt; 0 时</b>）；goal 级 total/done
     * 与 completionRate 同步并入哨兵计数（直挂工作项计入目标完成率）。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        List<Map<String, Object>> goalList = new ArrayList<>();
        for (StrategicGoal g : goals.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))) {
            long total = 0;
            long done = 0;
            List<Map<String, Object>> items = new ArrayList<>();
            // 哨兵行 id=NULL（不进条目行循环），单独保存
            GoalOverviewAggView direct = null;
            for (GoalOverviewAggView row : roadmaps.overviewByGoal(g.getId())) {
                if (row.getId() == null) {
                    direct = row;
                    continue;
                }
                total += row.getTotal();
                done += row.getDoneCount();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.getId());
                m.put("name", row.getName());
                m.put("done", row.getDoneCount());
                m.put("total", row.getTotal());
                m.put("doneHours", row.getDoneHours());
                m.put("inProgressHours", row.getInProgressHours());
                m.put("todoHours", row.getTodoHours());
                m.put("releaseId", row.getReleaseId());
                m.put("taskCount", row.getTaskCount());
                m.put("taskDone", row.getTaskDone());
                m.put("requirementCount", row.getRequirementCount());
                m.put("requirementDone", row.getRequirementDone());
                m.put("defectCount", row.getDefectCount());
                m.put("defectDone", row.getDefectDone());
                items.add(m);
            }
            // 直挂哨兵行：计数并入 goal 级 total/done（完成率随动），行级虚拟行排在条目行后
            if (direct != null && direct.getTotal() > 0) {
                total += direct.getTotal();
                done += direct.getDoneCount();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", null);
                m.put("name", DIRECT_SENTINEL_NAME);
                m.put("done", direct.getDoneCount());
                m.put("total", direct.getTotal());
                m.put("doneHours", direct.getDoneHours());
                m.put("inProgressHours", direct.getInProgressHours());
                m.put("todoHours", direct.getTodoHours());
                m.put("releaseId", null);
                m.put("taskCount", direct.getTaskCount());
                m.put("taskDone", direct.getTaskDone());
                m.put("requirementCount", direct.getRequirementCount());
                m.put("requirementDone", direct.getRequirementDone());
                m.put("defectCount", direct.getDefectCount());
                m.put("defectDone", direct.getDefectDone());
                items.add(m);
            }
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("goalId", g.getId());
            gm.put("name", g.getName());
            gm.put("total", total);
            gm.put("done", done);
            gm.put("completionRate", completionRate(done, total));
            gm.put("items", items);
            goalList.add(gm);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("goals", goalList);
        return res;
    }

    // ==================== 内部 ====================

    private String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, message);
        }
        return v;
    }

    /** 完成率（done/total 百分比，1 位小数 HALF_UP；无条目 0.0，避免除零） */
    private static BigDecimal completionRate(long done, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(done * 100).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}
