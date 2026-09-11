package cn.teamone.app.config;

import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.auth.RequirePerm;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** @RequirePerm 的执行点：从安全上下文取当前用户 → 四步授权链断言 */
@Aspect
@Component
public class RequirePermAspect {

    private final PermissionService permissions;

    public RequirePermAspect(PermissionService permissions) {
        this.permissions = permissions;
    }

    @Before("@annotation(perm)")
    public void check(JoinPoint joinPoint, RequirePerm perm) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof AppUser me)) {
            throw new cn.teamone.shared.api.PermissionDeniedException(
                    cn.teamone.shared.api.ErrorCode.PLT_4010, "未认证", null);
        }
        UUID resourceId = perm.resourceId().isBlank()
                ? PermissionService.PLATFORM_RESOURCE_ID
                : UUID.fromString(perm.resourceId());
        permissions.require(me.getId(), perm.resourceType(), resourceId, perm.action());
    }
}
