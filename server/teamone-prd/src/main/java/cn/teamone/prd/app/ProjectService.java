package cn.teamone.prd.app;

import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Project;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.ProjectRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目交付应用服务（⑥o 客制化双轨，docs/v2/15 §4 /api/v1/projects）。
 *
 * <p>产品是唯一的工程账本：项目详情=「该项目的客制化需求视图」=
 * product_id=项目产品 ∧ source_project_id=项目。度量（15 §6，分母为 0 时 rate=0 防除零）：
 * customRate = 项目客制化工作项数 / 产品全部工作项数（持续走高=产品覆盖不足）；
 * promoteRate = 已回流客制化数 / 项目客制化数（持续走低=产品在漏需求信号）。</p>
 *
 * <p>鉴权：读端点登录态；写端点 service 内四步链——按项目交付的产品校验 product edit。</p>
 *
 * @author Ivan Yang, 2026-09-28
 */
@Service
public class ProjectService {

    private static final List<String> STATUSES = List.of(
            Project.STATUS_DELIVERING, Project.STATUS_ACCEPTED, Project.STATUS_CLOSED);

    /** 建项目请求（name 唯一必填；productId 必填，接受 uuid 或业务键；status 缺省 delivering） */
    public record ProjectSpec(String name, String customerName, String status, String productId,
                              String managerId, LocalDate startDate, LocalDate planAcceptDate) {}

    /** 更新请求（null=不变更；managerId 空串=置空；name 不可改） */
    public record ProjectUpdateSpec(String customerName, String status, String managerId,
                                    LocalDate startDate, LocalDate planAcceptDate) {}

    private final ProjectRepository projects;
    private final WorkItemRepository workItems;
    private final ProductRepository products;
    private final PermissionService permissions;
    private final Refs refs;

    public ProjectService(ProjectRepository projects, WorkItemRepository workItems,
                          ProductRepository products, PermissionService permissions, Refs refs) {
        this.projects = projects;
        this.workItems = workItems;
        this.products = products;
        this.permissions = permissions;
        this.refs = refs;
    }

    // ==================== 查询 ====================

    /** 列表：status/productId 过滤，行含度量（productTotal/customTotal/promotedTotal/customRate/promoteRate）+ productName */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String productId) {
        UUID productFilter = productId == null || productId.isBlank() ? null : refs.product(productId).getId();
        Specification<Project> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (productFilter != null) {
                ps.add(cb.equal(root.get("productId"), productFilter));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        List<Project> rows = projects.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<UUID, String> productNames = productNamesOf(rows.stream().map(Project::getProductId).toList());
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Project p : rows) {
            Map<String, Object> view = Views.of(p);
            view.put("productName", productNames.get(p.getProductId()));
            view.putAll(metricsOf(p));
            items.add(view);
        }
        return items;
    }

    /** 详情：项目字段 + productName + 度量 + 客制化需求条目（id/key/title/status/origin/promotedToId） */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Project p = refs.project(id);
        Map<String, Object> view = Views.of(p);
        view.put("productName", products.findById(p.getProductId()).map(Product::getName).orElse(null));
        view.putAll(metricsOf(p));
        view.put("customItems", customItemsOf(p.getId()));
        return view;
    }

    // ==================== 写入 ====================

    @Transactional
    public Map<String, Object> create(ProjectSpec spec, UUID actorId) {
        String name = requireText(spec.name(), "name 必填");
        UUID productId = refs.product(requireText(spec.productId(), "productId 必填")).getId();
        permissions.require(actorId, "product", productId, "edit");
        if (projects.existsByName(name)) {
            throw new BusinessException(ErrorCode.PRD_4257);
        }

        Project p = new Project();
        p.setName(name);
        p.setCustomerName(spec.customerName());
        p.setStatus(resolveStatus(spec.status()));
        p.setProductId(productId);
        p.setManagerId(refs.userIdOrNull(spec.managerId()));
        p.setStartDate(spec.startDate());
        p.setPlanAcceptDate(spec.planAcceptDate());
        return assembleDetail(projects.save(p));
    }

    /** 更新（If-Match 乐观锁，同工作项惯例：不匹配 409 且 details 带最新 version） */
    @Transactional
    public Map<String, Object> update(String id, ProjectUpdateSpec spec, Integer ifMatch, UUID actorId) {
        Project p = refs.project(id);
        permissions.require(actorId, "product", p.getProductId(), "edit");

        if (ifMatch == null) {
            throw new BusinessException(ErrorCode.PLT_4000, "缺少 If-Match 版本头");
        }
        if (ifMatch != p.getVersion()) {
            throw new BusinessException(ErrorCode.PLT_4091, "版本冲突，请刷新后重试",
                    List.of("currentVersion=" + p.getVersion()));
        }

        if (spec.customerName() != null) {
            p.setCustomerName(spec.customerName());
        }
        if (spec.status() != null) {
            p.setStatus(resolveStatus(spec.status()));
        }
        if (spec.managerId() != null) {
            p.setManagerId(spec.managerId().isBlank() ? null : refs.userId(spec.managerId()));
        }
        if (spec.startDate() != null) {
            p.setStartDate(spec.startDate());
        }
        if (spec.planAcceptDate() != null) {
            p.setPlanAcceptDate(spec.planAcceptDate());
        }
        return assembleDetail(p);
    }

    // ==================== 内部 ====================

    /** 度量（15 §6）：customRate=客制/产品全部；promoteRate=已回流/客制；分母 0 → rate=0 */
    private Map<String, Object> metricsOf(Project p) {
        long productTotal = workItems.countByProductId(p.getProductId());
        long customTotal = workItems.countBySourceProjectIdAndOrigin(
                p.getId(), WorkItem.ORIGIN_PROJECT_CUSTOM);
        long promotedTotal = workItems.countBySourceProjectIdAndOriginAndPromotedToIdIsNotNull(
                p.getId(), WorkItem.ORIGIN_PROJECT_CUSTOM);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productTotal", productTotal);
        m.put("customTotal", customTotal);
        m.put("promotedTotal", promotedTotal);
        m.put("customRate", productTotal == 0 ? 0.0 : (double) customTotal / productTotal);
        m.put("promoteRate", customTotal == 0 ? 0.0 : (double) promotedTotal / customTotal);
        return m;
    }

    /** 客制化需求条目（创建时间倒序；需求挂产品、source_project_id 记来源） */
    private List<Map<String, Object>> customItemsOf(UUID projectId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (WorkItem wi : workItems.findBySourceProjectIdAndOriginOrderByCreatedAtDesc(
                projectId, WorkItem.ORIGIN_PROJECT_CUSTOM)) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", wi.getId());
            row.put("key", wi.getKey());
            row.put("title", wi.getTitle());
            row.put("status", wi.getStatus());
            row.put("origin", wi.getOrigin());
            row.put("promotedToId", wi.getPromotedToId());
            items.add(row);
        }
        return items;
    }

    /** 详情装配（create/update 返回与 GET /{id} 同形，省一次读） */
    private Map<String, Object> assembleDetail(Project p) {
        Map<String, Object> view = Views.of(p);
        view.put("productName", products.findById(p.getProductId()).map(Product::getName).orElse(null));
        view.putAll(metricsOf(p));
        view.put("customItems", customItemsOf(p.getId()));
        return view;
    }

    /** 一批产品的名字（列表装配防 N+1，一次 findAllById） */
    private Map<UUID, String> productNamesOf(List<UUID> productIds) {
        Map<UUID, String> names = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Product p : products.findAllById(productIds.stream().distinct().toList())) {
                names.put(p.getId(), p.getName());
            }
        }
        return names;
    }

    /** status 归一（缺省 delivering；非法值 400） */
    private String resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return Project.STATUS_DELIVERING;
        }
        if (!STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.PLT_4000, "status 非法: " + status);
        }
        return status;
    }

    private String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, message);
        }
        return v;
    }
}
