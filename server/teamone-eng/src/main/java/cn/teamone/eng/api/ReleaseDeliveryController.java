package cn.teamone.eng.api;

import cn.teamone.eng.app.ReleaseDeliveryService;
import cn.teamone.eng.app.RepoPermChecker;
import cn.teamone.eng.dto.DeploymentRequest;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 版本交付联动端点（R-9 发布一致性 · B2 批）：版本详情「构建→测试→部署」阶段真实展示。
 *
 * <p>路径沿用 /api/v1/releases 前缀但落 eng 模块（prd ⊥ eng 互禁编译依赖，ArchUnit R3/R5；
 * {id} 为 release UUID 逻辑引用）。</p>
 *
 * <p>ACL 接入（⑥j-A M-b B1 · docs/v2/13 §2.4/Q5 裁决）：读端点（pipelines/deployments 清单）
 * 维持登录态放行——release 为 prd 域资源且无稳定 repo 映射（一个版本可关联多仓运行），
 * 属仓库 ACL 范围外（挂账：v2.1 若 prd 域建立 release 级 ACL 再收口）；
 * <b>登记部署改挂仓库角色</b>：请求带 pipelineRunId 且能解析 repo_id→repo 时按
 * {@code register-deployment}（Maintainer+）判定；解析不到（未挂流水线）回退平台级
 * {@code platform:manage}（Q5 裁决回退口径，堵 G3 授权粒度断层）。</p>
 */
@RestController
@RequestMapping("/api/v1/releases/{id}")
public class ReleaseDeliveryController {

    private final ReleaseDeliveryService delivery;
    private final RepoPermChecker permChecker;
    private final PermissionService permissions;

    public ReleaseDeliveryController(ReleaseDeliveryService delivery,
                                     RepoPermChecker permChecker,
                                     PermissionService permissions) {
        this.delivery = delivery;
        this.permChecker = permChecker;
        this.permissions = permissions;
    }

    /**
     * 某版本的流水线运行清单（createdAt 倒序，最新 20 条；空清单=尚未关联流水线）。
     *
     * <p>ACL（M-b B1）：登录态放行 view——release 无 repo 映射（prd 资源，ACL 范围外，挂账注明）。</p>
     */
    @GetMapping("/pipelines")
    public Map<String, Object> pipelines(@PathVariable String id) {
        Actor.require();
        return Map.of("items", delivery.pipelinesOfRelease(ReleaseDeliveryService.releaseId(id)));
    }

    /**
     * 某版本的部署登记清单（deployed_at 倒序）。
     *
     * <p>ACL（M-b B1）：登录态放行 view（同上，prd 资源 ACL 范围外）。</p>
     */
    @GetMapping("/deployments")
    public Map<String, Object> deployments(@PathVariable String id) {
        Actor.require();
        return Map.of("items", delivery.deploymentsOfRelease(ReleaseDeliveryService.releaseId(id)));
    }

    /**
     * 登记部署（真实执行联动属 M4/M5，本端点只登记留痕 + 审计 release.deploy）。
     *
     * <p>ACL（M-b B1 · Q5 裁决）：请求带 pipelineRunId 且运行可解析 repo_id → 仓库
     * {@code register-deployment}（能力矩阵 Maintainer+，§2.3）；未携带/解析不到 → 回退
     * 平台级 {@code platform:manage}（四步链，平台管理员口径；运行 id 非法的 404 由
     * 登记服务在后置校验抛出）。两路口径互斥不串联（§3.1 分轨）。</p>
     */
    @PostMapping("/deployments")
    public Map<String, Object> registerDeployment(@PathVariable String id,
                                                  @AuthenticationPrincipal AppUser me,
                                                  @RequestBody DeploymentRequest req) {
        UUID actor = Actor.require();
        if (me == null) {
            throw new PermissionDeniedException(ErrorCode.PLT_4010, "未认证", List.of());
        }
        Optional<UUID> repoId = delivery.deploymentAnchorRepo(
                ReleaseDeliveryService.releaseId(id), req == null ? null : req.pipelineRunId());
        if (repoId.isPresent()) {
            // 主口径：仓库角色 register-deployment（Maintainer+）
            permChecker.require(me.getId(), repoId.get(), RepoActions.REGISTER_DEPLOYMENT);
        } else {
            // Q5 回退口径：未挂流水线（或运行不可解析）→ platform:manage（四步链断言）
            permissions.require(me.getId(), "platform",
                    PermissionService.PLATFORM_RESOURCE_ID, "platform:manage");
        }
        return delivery.register(ReleaseDeliveryService.releaseId(id), req, actor);
    }

    /** 当前主体（与 prd api Actor 同构：JwtAuthFilter 以 AppUser 为 principal） */
    private static final class Actor {

        /** 取当前登录用户 id；上下文无主体抛 T1-PLT-4010（401） */
        static UUID require() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AppUser me) {
                return me.getId();
            }
            throw new PermissionDeniedException(ErrorCode.PLT_4010, "未认证", null);
        }
    }
}
