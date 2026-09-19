package cn.teamone.app.auth;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * 登录 / 刷新 / 登出 / 我的信息。
 * 刷新令牌：随机 384bit，库内仅存 SHA-256 哈希；refresh 旋转（旧令牌立即吊销）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AppUserRepository users;
    private final RefreshTokenStore refreshStore;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ObjectMapper om;
    private final AuditService audit;
    private final long refreshTtlDays;

    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthController(AppUserRepository users, RefreshTokenStore refreshStore,
                          PasswordEncoder encoder, JwtService jwt, ObjectMapper om,
                          AuditService audit,
                          @Value("${teamone.jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        this.users = users;
        this.refreshStore = refreshStore;
        this.encoder = encoder;
        this.jwt = jwt;
        this.om = om;
        this.audit = audit;
        this.refreshTtlDays = refreshTtlDays;
    }

    public record LoginReq(String username, String password) {}

    /**
     * 用户投影（登录/刷新/me 三处同形）。R-11：补 themePreference（light/dark/system，
     * NULL=未设置由前端视同 system）——登录/刷新后前端据此应用主题（后端值优先）。
     */
    public record UserView(UUID id, String username, String displayName, String title,
                           String platformRole, int dailyCapacityHours, String themePreference) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getTitle(),
                    u.getPlatformRole().name(), u.getDailyCapacityHours(), u.getThemePreference());
        }
    }

    public record TokenRes(String accessToken, String refreshToken, String tokenType,
                           long expiresIn, UserView user) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginReq req, HttpServletResponse response) throws Exception {
        var user = users.findByUsername(req.username() == null ? "" : req.username())
                .filter(u -> encoder.matches(req.password() == null ? "" : req.password(), u.getPasswordHash()))
                .filter(u -> u.getStatus() == AppUser.Status.ACTIVE);
        if (user.isEmpty()) {
            // 登录失败审计（C-3/V-9）：无事务调用→AuditService 自开短事务独立落库（语义已确认）；
            // 用户名缺省记 anonymous（不区分用户名/密码错误，防枚举口径不变）
            String attempted = req.username() == null || req.username().isBlank()
                    ? "anonymous" : req.username();
            audit.record(null, "auth.login.failed", "platform", null, Map.of("username", attempted));
            return error(HttpStatus.UNAUTHORIZED, cn.teamone.shared.api.ErrorCode.PLT_4011, null);
        }
        // 登录成功审计（C-3/V-9）：auth.login / platform / detail{ok:true}
        audit.record(user.get().getId(), "auth.login", "platform", null, Map.of("ok", true));
        TokenRes tokenRes = issue(user.get());
        attachCookies(response, tokenRes.accessToken(), tokenRes.refreshToken());
        return ResponseEntity.ok(tokenRes);
    }

    public record RefreshReq(String refreshToken) {}

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody(required = false) RefreshReq req,
                                    @CookieValue(name = "teamone_refresh_token", required = false) String cookieRefresh,
                                    HttpServletResponse response) throws Exception {
        String tokenStr = (req != null && req.refreshToken() != null && !req.refreshToken().isBlank())
                ? req.refreshToken()
                : cookieRefresh;
        if (tokenStr == null || tokenStr.isBlank()) {
            return error(HttpStatus.UNAUTHORIZED, cn.teamone.shared.api.ErrorCode.PLT_4012, null);
        }
        String hash = sha256(tokenStr);
        var userId = refreshStore.findValidUserId(hash);
        if (userId.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, cn.teamone.shared.api.ErrorCode.PLT_4012, null);
        }
        var user = users.findById(userId.get())
                .filter(u -> u.getStatus() == AppUser.Status.ACTIVE);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, cn.teamone.shared.api.ErrorCode.PLT_4012, null);
        }
        refreshStore.revoke(hash); // 旋转：旧令牌立即作废
        TokenRes tokenRes = issue(user.get());
        attachCookies(response, tokenRes.accessToken(), tokenRes.refreshToken());
        return ResponseEntity.ok(tokenRes);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) RefreshReq req,
                                   @CookieValue(name = "teamone_refresh_token", required = false) String cookieRefresh,
                                   HttpServletResponse response) {
        String tokenStr = (req != null && req.refreshToken() != null && !req.refreshToken().isBlank())
                ? req.refreshToken()
                : cookieRefresh;
        if (tokenStr != null && !tokenStr.isBlank()) {
            refreshStore.revoke(sha256(tokenStr));
        }
        clearCookies(response);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser me) {
        return ResponseEntity.ok(UserView.of(me));
    }

    /**
     * 将 JWT 访问令牌与刷新令牌写入 httpOnly 安全 Cookie（防范客户端脚本 XSS 劫持）
     */
    private void attachCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        if (response == null) return;
        ResponseCookie accessCookie = ResponseCookie.from("teamone_access_token", accessToken)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(jwt.accessTtlSeconds())
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from("teamone_refresh_token", refreshToken)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(refreshTtlDays * 86400)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * 登出时销毁相关 httpOnly 安全 Cookie
     */
    private void clearCookies(HttpServletResponse response) {
        if (response == null) return;
        ResponseCookie accessCookie = ResponseCookie.from("teamone_access_token", "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from("teamone_refresh_token", "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    private TokenRes issue(AppUser user) throws Exception {
        String refresh = newToken();
        refreshStore.store(sha256(refresh), user.getId(),
                Instant.now().plusSeconds(refreshTtlDays * 86400));
        return new TokenRes(jwt.issueAccess(user.getId()), refresh, "Bearer",
                jwt.accessTtlSeconds(), UserView.of(user));
    }

    private static String newToken() {
        byte[] buf = new byte[48];
        RANDOM.nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<?> error(HttpStatus status, cn.teamone.shared.api.ErrorCode ec, String message) throws Exception {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(om.writeValueAsString(cn.teamone.shared.api.ApiError.of(ec, message)));
    }
}
