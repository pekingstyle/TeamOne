package cn.teamone.eng.api;

import cn.teamone.eng.app.ReleaseDeliveryService;
import cn.teamone.eng.dto.DeploymentRequest;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import cn.teamone.shared.auth.RequirePerm;
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
import java.util.UUID;

/**
 * 版本交付联动端点（R-9 发布一致性 · B2 批）：版本详情「构建→测试→部署」阶段真实展示。
 *
 * <p>路径沿用 /api/v1/releases 前缀但落 eng 模块（prd ⊥ eng 互禁编译依赖，ArchUnit R3/R5；
 * {id} 为 release UUID 逻辑引用）。读端点登录态（与 prd 版本端点同族）；登记端点平台级
 * platform:manage（发布的产品级 product:edit 授权链在 prd 域内，eng 跨域不可查，取
 * 同族管理员权限）+ 审计留痕。</p>
 */
@RestController
@RequestMapping("/api/v1/releases/{id}")
public class ReleaseDeliveryController {

    private final ReleaseDeliveryService delivery;

    public ReleaseDeliveryController(ReleaseDeliveryService delivery) {
        this.delivery = delivery;
    }

    /** 某版本的流水线运行清单（createdAt 倒序，最新 20 条；空清单=尚未关联流水线） */
    @GetMapping("/pipelines")
    public Map<String, Object> pipelines(@PathVariable String id) {
        Actor.require();
        return Map.of("items", delivery.pipelinesOfRelease(ReleaseDeliveryService.releaseId(id)));
    }

    /** 某版本的部署登记清单（deployed_at 倒序） */
    @GetMapping("/deployments")
    public Map<String, Object> deployments(@PathVariable String id) {
        Actor.require();
        return Map.of("items", delivery.deploymentsOfRelease(ReleaseDeliveryService.releaseId(id)));
    }

    /** 登记部署（platform:manage 管理员同族 + 审计 release.deploy；真实执行联动属 M4/M5） */
    @PostMapping("/deployments")
    @RequirePerm(resourceType = "platform", action = "platform:manage")
    public Map<String, Object> registerDeployment(@PathVariable String id,
                                                  @AuthenticationPrincipal AppUser me,
                                                  @RequestBody DeploymentRequest req) {
        UUID actor = Actor.require();
        if (me == null) {
            throw new PermissionDeniedException(ErrorCode.PLT_4010, "未认证", List.of());
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
