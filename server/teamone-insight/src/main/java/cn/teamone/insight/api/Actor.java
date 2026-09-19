package cn.teamone.insight.api;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** 当前主体（与 prd.api.Actor 同构；insight 不 import prd，故自持一份）。 */
public final class Actor {

    private Actor() {
    }

    /** 取当前登录用户 id；上下文无主体抛 T1-PLT-4010（401） */
    public static UUID require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUser me) {
            return me.getId();
        }
        throw new PermissionDeniedException(ErrorCode.PLT_4010, "未认证", null);
    }
}
