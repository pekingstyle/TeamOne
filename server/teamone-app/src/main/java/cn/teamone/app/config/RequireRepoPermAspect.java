package cn.teamone.app.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.teamone.eng.app.RepoLocator;
import cn.teamone.eng.domain.Repository;
import cn.teamone.platform.authz.RepoAclService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import cn.teamone.shared.auth.RequireRepoPerm;
import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code @RequireRepoPerm} 的执行点（⑥i-A1 M-a · docs/v2/13 §4.4 两路定位器之一）：
 * 从安全上下文取当前用户 → 从请求 URI 提取 {@code /api/v1/repos/{idOrName}/...} 路径段
 * → 经 {@link RepoLocator} 归一（UUID 或仓库名）为 repoId → 仓库五步判定链断言
 * （{@link RepoAclService#require}，不过即 403 T1-PLT-4030）。
 *
 * <p>路径形态约定：① {@code /api/v1/repos/{idOrName}/...}（含 members）→ 正常断言；
 * ② {@code /api/v1/repos}（列表端点，无 idOrName 段）→ 无单一资源可断言，
 * ACL 语义 = 方法内结果集 PRIVATE 过滤（A6，GET /repos 列表过滤自负）→ 放行切面；
 * ③ 其他路径 → 注解误用（无 /repos 前缀端点必须走服务层回退 RepoPermChecker，
 * §4.4 第二路）→ fail-closed 抛异常，防止「标注了却没生效」的静默安全洞。</p>
 *
 * <p>M-a 范围（总监裁决零收紧口径）：仅 members 三端点 + GET /repos 列表标注本注解
 * 做真实验证；其余约 50 个端点的标注属 M-b，本批不做、不改变任何存量端点行为。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@Aspect
@Component
public class RequireRepoPermAspect {

    /** /api/v1/repos[/idOrName][/rest]：group(1)=idOrName（列表端点为 null） */
    private static final Pattern REPOS_PATH = Pattern.compile("^/api/v1/repos(?:/([^/]+))?(?:/.*)?$");

    private final RepoAclService repoAcl;
    private final RepoLocator locator;

    public RequireRepoPermAspect(RepoAclService repoAcl, RepoLocator locator) {
        this.repoAcl = repoAcl;
        this.locator = locator;
    }

    @Before("@annotation(perm)")
    public void check(JoinPoint joinPoint, RequireRepoPerm perm) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof AppUser me)) {
            throw new PermissionDeniedException(ErrorCode.PLT_4010, "未认证", null);
        }
        HttpServletRequest request = ((ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes()).getRequest();
        Matcher m = REPOS_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            // 注解误用 fail-closed：非 /repos 路径端点无法定位 repoId，必须服务层回退显式判定
            throw new IllegalStateException(
                    "@RequireRepoPerm 仅支持 /api/v1/repos/** 路径端点（当前 " + request.getRequestURI()
                            + "），无 /repos 前缀端点请改用服务层 RepoPermChecker 回退");
        }
        String idOrName = m.group(1);
        if (idOrName == null) {
            // 列表端点（GET /repos）：切面无单一资源可断言，过滤由方法内结果集实现（A6）
            return;
        }
        Repository repo = locator.resolve(idOrName);
        repoAcl.require(me.getId(), repo.getId(), perm.action());
    }
}
