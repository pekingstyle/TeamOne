package cn.teamone.app.auth;

import cn.teamone.platform.app.PatService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 访问认证网关拦截器（支持 JWT 双令牌与 PAT 个人访问令牌双轨模式）。
 *
 * <ul>
 *   <li><b>PAT 令牌分支</b>：若令牌具备 {@code t1_pat_} 前缀，调用 {@link PatService} 比对 SHA-256 哈希；</li>
 *   <li><b>JWT 令牌分支</b>：普通 JWT 解析验签并提取用户 ID；</li>
 *   <li>认证成功后统一装配 {@link AppUser} 实体作为 Principal 注入 SecurityContext。</li>
 * </ul>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final AppUserRepository users;
    private final PatService patService;

    public JwtAuthFilter(JwtService jwt, AppUserRepository users, PatService patService) {
        this.jwt = jwt;
        this.users = users;
        this.patService = patService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            // 优先采用标准 Authorization 头（兼容现有 API、测试、Git CLI 与外部自动化脚本）
            token = header.substring(7);
        } else if (request.getCookies() != null) {
            // M3-INC-3 V-20：支持从生产级 httpOnly 安全 Cookie 读取访问令牌（防范 XSS 劫持）
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if ("teamone_access_token".equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    token = c.getValue();
                    break;
                }
            }
        }

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // ① 判定是否为 PAT 个人访问令牌（t1_pat_ 专属前缀）
            if (token.startsWith(PatService.PAT_PREFIX)) {
                patService.authenticate(token).ifPresent(u -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            u, null, List.of(() -> "ROLE_" + u.getPlatformRole().name()));
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            } else {
                // ② 普通 JWT 访问令牌验签
                jwt.verifyAccess(token).ifPresent(uid -> users.findById(uid)
                        .filter(u -> u.getStatus() == AppUser.Status.ACTIVE)
                        .ifPresent(u -> {
                            var auth = new UsernamePasswordAuthenticationToken(
                                    u, null, List.of(() -> "ROLE_" + u.getPlatformRole().name()));
                            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }));
            }
        }
        chain.doFilter(request, response);
    }
}
