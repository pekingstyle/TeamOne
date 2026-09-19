package cn.teamone.prd.app;

import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.RoadmapItem;
import cn.teamone.prd.domain.StrategicGoal;
import cn.teamone.prd.repo.ItemReleaseView;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.RoadmapAggView;
import cn.teamone.prd.repo.RoadmapItemRepository;
import cn.teamone.prd.repo.StrategicGoalRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RoadMap 条目应用服务 + 双视角时间轴（M2-INC-1 W1 基础版，05 §3.3 prd 段）。
 *
 * <p>GET/POST /roadmap-items：goalId/productId/releaseId 关联链（引用字段接受 uuid 或业务键）。
 * GET /roadmap/timeline?view=goal|release：goal 视图=目标列表+各目条目+条目关联版本时间窗；
 * release 视图=版本泳道列表+条目（R-7/B5 起按 planDate 升序，条目=正向挂接 ∪ release.roadmapItemId 反查）。
 * 数据<b>全来自查询侧</b>——进度用 COUNT FILTER 聚合
 * （{@link RoadmapItemRepository#rollupAll()}），releaseIds 用工作项分布聚合（红线条 ①：
 * 状态/进度不落冗余列）。W2 在此补充下钻树逐级返回。</p>
 *
 * <p>鉴权：读端点登录态；写端点 service 内四步链（R-5/D1 起条目必挂产品，
 * 挂该产品 product edit）。</p>
 */
@Service
public class RoadmapService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 条目创建请求（R-5/D1：name/productId 必填；goalId/releaseId/ownerId 接受 uuid 或业务键/用户名） */
    public record CreateSpec(String name, String description, String productId, String goalId,
                             String releaseId, String ownerId, LocalDate startDate, LocalDate dueDate) {}

    private final RoadmapItemRepository roadmaps;
    private final StrategicGoalRepository goals;
    private final ReleaseRepository releases;
    private final WorkItemRepository workItems;
    private final PermissionService permissions;
    private final Refs refs;

    public RoadmapService(RoadmapItemRepository roadmaps, StrategicGoalRepository goals,
                          ReleaseRepository releases, WorkItemRepository workItems,
                          PermissionService permissions, Refs refs) {
        this.roadmaps = roadmaps;
        this.goals = goals;
        this.releases = releases;
        this.workItems = workItems;
        this.permissions = permissions;
        this.refs = refs;
    }

    // ==================== 条目 CRUD ====================

    @Transactional
    public Map<String, Object> create(CreateSpec spec, UUID actorId) {
        // R-5/D1：条目必挂产品（产品不做独立层，条目行必须可打产品标签；未挂产品的历史条目仍可读）
        if (spec.productId() == null || spec.productId().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "productId 必填（RoadMap 条目必须挂产品，R-5/D1）");
        }
        UUID productId = refs.product(spec.productId()).getId();
        permissions.require(actorId, "product", productId, "edit");
        RoadmapItem r = new RoadmapItem();
        r.setName(requireText(spec.name(), "name 必填"));
        r.setDescription(spec.description());
        r.setProductId(productId);
        r.setGoalId(spec.goalId() != null ? refs.goal(spec.goalId()).getId() : null);
        r.setReleaseId(spec.releaseId() != null ? refs.release(spec.releaseId()).getId() : null);
        r.setOwnerId(spec.ownerId() != null ? refs.userId(spec.ownerId()) : actorId);
        r.setStartDate(spec.startDate());
        r.setDueDate(spec.dueDate());
        return Views.of(roadmaps.save(r));
    }

    /** 列表（可选 goalId/productId/releaseId 过滤，uuid 或业务键） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String goalId, String productId, String releaseId) {
        UUID g = goalId != null && !goalId.isBlank() ? refs.goal(goalId).getId() : null;
        UUID p = productId != null && !productId.isBlank() ? refs.product(productId).getId() : null;
        UUID rel = releaseId != null && !releaseId.isBlank() ? refs.release(releaseId).getId() : null;
        return roadmaps.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                .filter(r -> g == null || g.equals(r.getGoalId()))
                .filter(r -> p == null || p.equals(r.getProductId()))
                .filter(r -> rel == null || rel.equals(r.getReleaseId()))
                .map(Views::of)
                .toList();
    }

    // ==================== 双视角时间轴（W1 基础版） ====================

    @Transactional(readOnly = true)
    public Map<String, Object> timeline(String view) {
        if (view == null || view.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "view 必填（goal|release）");
        }
        // R-5 AC③ 直连口径：rollupAll 行集含直挂工作项哨兵行（id=NULL、按 goal_id 分组）——
        // 条目级进度映射只收条目行（id 非空），哨兵行按 goalId 单独汇出（goal 视角目标级合计用；
        // release 视角与条目下钻不受影响——直挂工作项无条目归属，不进条目行集）
        Map<UUID, RoadmapAggView> agg = new LinkedHashMap<>();
        Map<UUID, RoadmapAggView> directByGoal = new LinkedHashMap<>();
        for (RoadmapAggView row : roadmaps.rollupAll()) {
            if (row.getId() == null) {
                if (row.getGoalId() != null) {
                    directByGoal.put(row.getGoalId(), row);
                }
            } else {
                agg.put(row.getId(), row);
            }
        }
        Map<UUID, Set<UUID>> itemReleases = workItems.workItemReleaseDistribution().stream()
                .collect(Collectors.groupingBy(ItemReleaseView::getItemId,
                        Collectors.mapping(ItemReleaseView::getReleaseId,
                                Collectors.toCollection(LinkedHashSet::new))));
        Map<UUID, Map<String, Object>> releaseViews = releases.findAll().stream()
                .collect(Collectors.toMap(Release::getId, this::releaseRef));
        List<RoadmapItem> allItems = roadmaps.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));

        Map<String, Object> res = new LinkedHashMap<>();
        switch (view) {
            case "goal" -> {
                res.put("view", "goal");
                List<Map<String, Object>> rows = new ArrayList<>();
                for (StrategicGoal g : goals.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))) {
                    List<Map<String, Object>> items = allItems.stream()
                            .filter(r -> g.getId().equals(r.getGoalId()))
                            .map(r -> itemRow(r, agg, itemReleases, releaseViews))
                            .toList();
                    // R-5 AC③：目标级合计并入直挂工作项哨兵计数（与 GoalService.overview/rollup
                    // 口径一致——直挂项计入目标进度；roadmapItems 条目行集不含哨兵，条目下钻不变）
                    RoadmapAggView direct = directByGoal.get(g.getId());
                    long done = items.stream().mapToLong(i -> (long) i.get("workItemDone")).sum()
                            + (direct == null ? 0 : direct.getDoneCount());
                    long total = items.stream().mapToLong(i -> (long) i.get("total")).sum()
                            + (direct == null ? 0 : direct.getTotal());
                    Map<String, Object> row = Views.of(g);
                    row.put("workItemDone", done);
                    row.put("total", total);
                    row.put("roadmapItems", items);
                    rows.add(row);
                }
                res.put("items", rows);
            }
            case "release" -> {
                res.put("view", "release");
                List<Release> sorted = releases.findAll().stream()
                        .sorted(Comparator.comparing(Release::getPlanDate,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Release rel : sorted) {
                    Map<String, Object> row = releaseRef(rel);
                    // R-7/B5：泳道条目 = 正向挂接（条目.releaseId）∪ 反查挂接（release.roadmap_item_id），
                    // 双向同时命中同一条目时按 id 去重（与交付页/目标页 rollup 口径一致）
                    LinkedHashMap<UUID, Map<String, Object>> mounted = new LinkedHashMap<>();
                    allItems.stream()
                            .filter(r -> rel.getId().equals(r.getReleaseId())
                                    || (rel.getRoadmapItemId() != null && rel.getRoadmapItemId().equals(r.getId())))
                            .map(r -> itemRow(r, agg, itemReleases, releaseViews))
                            .forEach(m -> mounted.putIfAbsent((UUID) m.get("id"), m));
                    row.put("roadmapItems", List.copyOf(mounted.values()));
                    rows.add(row);
                }
                res.put("items", rows);
            }
            default -> throw new BusinessException(ErrorCode.PLT_4000, "view 非法: " + view + "（goal|release）");
        }
        return res;
    }

    // ==================== 内部 ====================

    /** 条目时间轴行：时间窗 + 查询侧进度 + 关联版本引用（版本时间窗 planDate/codeFreezeDate） */
    private Map<String, Object> itemRow(RoadmapItem r, Map<UUID, RoadmapAggView> agg,
                                        Map<UUID, Set<UUID>> itemReleases,
                                        Map<UUID, Map<String, Object>> releaseViews) {
        RoadmapAggView a = agg.get(r.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("productId", r.getProductId());
        m.put("goalId", r.getGoalId());
        m.put("releaseId", r.getReleaseId());
        m.put("startDate", r.getStartDate() == null ? null : r.getStartDate().format(DATE));
        m.put("dueDate", r.getDueDate() == null ? null : r.getDueDate().format(DATE));
        m.put("workItemDone", a == null ? 0L : a.getDoneCount());
        m.put("total", a == null ? 0L : a.getTotal());
        LinkedHashSet<UUID> releaseIds = new LinkedHashSet<>();
        if (r.getReleaseId() != null) {
            releaseIds.add(r.getReleaseId());
        }
        releaseIds.addAll(itemReleases.getOrDefault(r.getId(), Set.of()));
        m.put("releaseIds", List.copyOf(releaseIds));
        if (r.getReleaseId() != null) {
            m.put("release", releaseViews.get(r.getReleaseId()));
        }
        return m;
    }

    /** 版本引用投影（时间窗两列 + 反查指针 + 门禁态；goal 视图条形挂版本标签 / release 视角泳道反查挂接用） */
    private Map<String, Object> releaseRef(Release r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("key", r.getKey());
        m.put("name", r.getName());
        m.put("status", r.getStatus());
        m.put("roadmapItemId", r.getRoadmapItemId());
        m.put("planDate", r.getPlanDate() == null ? null : r.getPlanDate().format(DATE));
        m.put("codeFreezeDate", r.getCodeFreezeDate() == null ? null : r.getCodeFreezeDate().format(DATE));
        return m;
    }

    private String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, message);
        }
        return v;
    }
}
