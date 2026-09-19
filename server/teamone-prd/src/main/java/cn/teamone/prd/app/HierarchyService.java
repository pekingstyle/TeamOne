package cn.teamone.prd.app;

import cn.teamone.prd.domain.Component;
import cn.teamone.prd.domain.Product;
import cn.teamone.prd.repo.ComponentRepository;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 产品/组件应用服务（M2-INC-1 W1：05 §3.3 prd 段 GET/POST /products、GET/POST /components）。
 *
 * <p>key 为 bare repo 命名根（05 §6.3 {productKey}/{componentKey}.git），唯一且必填。
 * owner 缺省指向 admin（任务口径「owner 指 admin」，与 ProductSeeder 种子一致）；显式传
 * ownerId（uuid 或 username）时按传入解析。组件 productId 必填（product ──&lt; component）。</p>
 *
 * <p>鉴权：读端点登录态；写端点 service 内四步链——product 自身尚无 id 时取平台级资源
 * （uuid_nil）校验 product edit，component 挂其 productId。</p>
 */
@Service
public class HierarchyService {

    /** 产品创建请求（key 唯一必填；goalId/ownerId 可选，接受 uuid 或业务键/用户名） */
    public record ProductSpec(String key, String name, String description, String goalId, String ownerId) {}

    /** 组件创建请求（productId 必填） */
    public record ComponentSpec(String key, String name, String description, String productId, String ownerId) {}

    private final ProductRepository products;
    private final ComponentRepository components;
    private final AppUserRepository users;
    private final PermissionService permissions;
    private final Refs refs;

    public HierarchyService(ProductRepository products, ComponentRepository components,
                            AppUserRepository users, PermissionService permissions, Refs refs) {
        this.products = products;
        this.components = components;
        this.users = users;
        this.permissions = permissions;
        this.refs = refs;
    }

    // ==================== 产品 ====================

    @Transactional
    public Map<String, Object> createProduct(ProductSpec spec, UUID actorId) {
        permissions.require(actorId, "product", PermissionService.PLATFORM_RESOURCE_ID, "edit");
        String key = requireText(spec.key(), "key 必填（bare repo 命名根，如 p1）");
        if (products.findByKey(key).isPresent()) {
            throw new BusinessException(ErrorCode.PLT_4000, "产品 key 已存在: " + key);
        }
        Product p = new Product();
        p.setKey(key);
        p.setName(requireText(spec.name(), "name 必填"));
        p.setDescription(spec.description());
        p.setGoalId(spec.goalId() != null ? refs.goal(spec.goalId()).getId() : null);
        p.setOwnerId(resolveOwner(spec.ownerId(), actorId));
        return Views.of(products.save(p));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listProducts() {
        return products.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                .map(Views::of)
                .toList();
    }

    // ==================== 组件 ====================

    @Transactional
    public Map<String, Object> createComponent(ComponentSpec spec, UUID actorId) {
        String key = requireText(spec.key(), "key 必填（bare repo 名，如 pipeline-engine）");
        UUID productId = refs.product(requireText(spec.productId(), "productId 必填")).getId();
        permissions.require(actorId, "product", productId, "edit");
        if (components.findByKey(key).isPresent()) {
            throw new BusinessException(ErrorCode.PLT_4000, "组件 key 已存在: " + key);
        }
        Component c = new Component();
        c.setKey(key);
        c.setName(requireText(spec.name(), "name 必填"));
        c.setDescription(spec.description());
        c.setProductId(productId);
        c.setOwnerId(resolveOwner(spec.ownerId(), actorId));
        return Views.of(components.save(c));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listComponents(String productId) {
        // productId 可为 uuid 或业务键（与 createComponent 同口径，refs.product 统一解析）
        UUID pid = (productId == null || productId.isBlank()) ? null : refs.product(productId).getId();
        List<Component> rows = pid == null
                ? components.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
                : components.findByProductIdOrderByCreatedAtAsc(pid);
        return rows.stream().map(Views::of).toList();
    }

    // ==================== 内部 ====================

    /** owner 缺省指 admin（平台管理员）；传了 ownerId 按 uuid/username 解析；admin 不在则回退 actor */
    private UUID resolveOwner(String requested, UUID actorId) {
        if (requested != null && !requested.isBlank()) {
            return refs.userId(requested);
        }
        return users.findByUsername("admin").map(AppUser::getId).orElse(actorId);
    }

    private String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, message);
        }
        return v;
    }
}
