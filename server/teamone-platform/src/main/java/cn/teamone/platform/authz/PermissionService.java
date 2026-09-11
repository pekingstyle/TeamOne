package cn.teamone.platform.authz;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.ResourceAcl;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.DepartmentRepository;
import cn.teamone.platform.repo.ResourceAclRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;

/**
 * 四步短路授权链（架构设计 §1.5）：
 * 1. 平台角色 OWNER/ADMIN → allow
 * 2. 资源所属部门负责人 → allow
 * 3. 组件负责人（M0 无组件，占位跳过）
 * 4. resource_acl 精确条目（deny 优先）
 * 5. 默认拒绝
 * 决策缓存（Valkey）自 W1 接入：整条链结果缓存 5min，W3 acl.changed 事件精准失效；
 * 缓存只是加速器，真相始终在此查询链。
 */
@Service
public class PermissionService {

    public static final UUID PLATFORM_RESOURCE_ID = new UUID(0L, 0L); // uuid_nil：平台级资源

    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final ResourceAclRepository acls;
    private final Optional<DecisionCache> cache;

    public PermissionService(AppUserRepository users, DepartmentRepository departments,
                             ResourceAclRepository acls, Optional<DecisionCache> cache) {
        this.users = users;
        this.departments = departments;
        this.acls = acls;
        this.cache = cache == null ? Optional.empty() : cache;
    }

    public boolean check(UUID userId, String resourceType, UUID resourceId, String action) {
        // 决策缓存优先（miss/故障回退查库；W1 纪律见 DecisionCache 注释）
        Optional<Boolean> cached = cache.flatMap(c -> c.get(userId, resourceType, resourceId, action));
        if (cached.isPresent()) {
            return cached.get();
        }
        boolean allowed = computeCheck(userId, resourceType, resourceId, action);
        cache.ifPresent(c -> c.put(userId, resourceType, resourceId, action, allowed));
        return allowed;
    }

    /** 四步短路链本体（无缓存路径） */
    private boolean computeCheck(UUID userId, String resourceType, UUID resourceId, String action) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != AppUser.Status.ACTIVE) {
            return false;
        }
        // 1. 平台角色
        if (user.getPlatformRole() == AppUser.PlatformRole.OWNER
                || user.getPlatformRole() == AppUser.PlatformRole.ADMIN) {
            return true;
        }
        // 2. 部门负责人（直接主管）
        if (user.getDepartmentId() != null
                && departments.findById(user.getDepartmentId())
                    .map(d -> userId.equals(d.getLeadUserId()))
                    .orElse(false)) {
            return true;
        }
        // 3. 组件负责人：组件实体属 prd 域，M0 起占位（接入后在此短路）
        // 4. 资源级 ACL：deny 优先
        List<UUID> resourceIds = new ArrayList<>(List.of(PLATFORM_RESOURCE_ID));
        if (resourceId != null) {
            resourceIds.add(resourceId);
        }
        List<ResourceAcl> relevant = acls.findRelevant(
                userId, user.getDepartmentId() == null ? PLATFORM_RESOURCE_ID : user.getDepartmentId(),
                resourceType, resourceIds, action);
        boolean allowed = relevant.stream().anyMatch(a -> a.getEffect() == ResourceAcl.Effect.ALLOW);
        boolean denied = relevant.stream().anyMatch(a -> a.getEffect() == ResourceAcl.Effect.DENY);
        return allowed && !denied;
    }

    /** 供 AOP 切面调用的断言式入口：不通过即抛 T1-PLT-4030 */
    public void require(UUID userId, String resourceType, UUID resourceId, String action) {
        if (!check(userId, resourceType, resourceId, action)) {
            throw new PermissionDeniedException(
                    ErrorCode.PLT_4030,
                    "无权限：" + resourceType + (resourceId == null ? "" : "#" + resourceId) + " " + action,
                    List.of("action=" + action, "resourceType=" + resourceType));
        }
    }
}
