package cn.teamone.app.me;

import cn.teamone.app.auth.AuthController;
import cn.teamone.app.auth.RefreshTokenStore;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 个人设置端点（R-11）：仅本人字段（昵称/主题偏好/每日容量）与改密。
 *
 * <p>与 /api/v1/me/summary（DashboardController）同挂 /api/v1/me 前缀；
 * 用户名/平台角色/状态等治理字段不在本人可改范围（走管理端 UsersController）。</p>
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    /** 主题偏好合法值（V20 列约束在应用侧校验；NULL 视同 system） */
    private static final Set<String> THEMES = Set.of("light", "dark", "system");

    /** 新密码最小长度（R-11 AC：强度 ≥8；与管理端重置口径 6 不同——本人改密从严） */
    private static final int MIN_PASSWORD_LEN = 8;

    private final AppUserRepository users;
    private final RefreshTokenStore refreshStore;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public MeController(AppUserRepository users, RefreshTokenStore refreshStore,
                        PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.refreshStore = refreshStore;
        this.encoder = encoder;
        this.audit = audit;
    }

    /** PUT /api/v1/me 请求体：全部字段可选，null = 不修改 */
    public record UpdateMeReq(String displayName, String themePreference, Integer dailyCapacityHours) {}

    /** 改密请求体（旧密必填校验；新密 ≥8 位；currentRefreshToken 可选=当前设备豁免下线） */
    public record ChangePasswordReq(String oldPassword, String newPassword, String currentRefreshToken) {}

    /**
     * 修改本人资料（昵称/主题偏好/每日容量）。
     * 返回与 /auth/me 同形的投影，前端据此即时刷新 AuthContext 与侧栏展示名。
     */
    @PutMapping
    @Transactional
    public AuthController.UserView updateMe(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser me,
                                            @RequestBody UpdateMeReq req) {
        if (req.displayName() != null) {
            String name = req.displayName().trim();
            if (name.isEmpty() || name.length() > 64) {
                throw new BusinessException(ErrorCode.PLT_4000, "昵称须为 1~64 个字符");
            }
            me.setDisplayName(name);
        }
        if (req.themePreference() != null) {
            if (!THEMES.contains(req.themePreference())) {
                throw new BusinessException(ErrorCode.PLT_4000, "主题偏好仅支持 light/dark/system");
            }
            me.setThemePreference(req.themePreference());
        }
        if (req.dailyCapacityHours() != null) {
            int h = req.dailyCapacityHours();
            if (h < 1 || h > 24) {
                throw new BusinessException(ErrorCode.PLT_4000, "每日容量须在 1~24 小时之间");
            }
            me.setDailyCapacityHours(h);
        }
        AppUser saved = users.save(me);
        return new AuthController.UserView(saved.getId(), saved.getUsername(), saved.getDisplayName(),
                saved.getTitle(), saved.getPlatformRole().name(), saved.getDailyCapacityHours(),
                saved.getThemePreference());
    }

    /**
     * 修改本人密码：校验旧密 → 新密 ≥8 位 → 落新哈希 → 作废该用户<b>其他</b>刷新令牌
     * （其他设备 refresh 即 401 强制重登；当前设备经 currentRefreshToken 豁免保持登录，
     * 未携带则全端下线）。JWT 访问令牌保持到期（≤15min）自然失效，与旋转先例一致。
     */
    @PostMapping("/password")
    @Transactional
    public Map<String, Object> changePassword(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser me,
                                              @RequestBody ChangePasswordReq req,
                                              @org.springframework.web.bind.annotation.CookieValue(
                                                      name = "teamone_refresh_token", required = false) String cookieRefresh) {
        String oldPwd = req.oldPassword() == null ? "" : req.oldPassword();
        if (!encoder.matches(oldPwd, me.getPasswordHash())) {
            // 旧密错误：明确业务错误（前端在表单内提示，不触发登出广播）
            throw new BusinessException(ErrorCode.PLT_4000, "旧密码不正确");
        }
        String newPwd = req.newPassword() == null ? "" : req.newPassword();
        if (newPwd.length() < MIN_PASSWORD_LEN) {
            throw new BusinessException(ErrorCode.PLT_4000, "新密码强度不足（至少 " + MIN_PASSWORD_LEN + " 位）");
        }
        me.setPasswordHash(encoder.encode(newPwd));
        users.save(me);
        // 豁免哈希：body 显式携带优先，回退 httpOnly Cookie（refreshStore 只认 SHA-256 哈希）
        String currentRefresh = (req.currentRefreshToken() != null && !req.currentRefreshToken().isBlank())
                ? req.currentRefreshToken() : cookieRefresh;
        String exemptHash = currentRefresh == null ? null : sha256(currentRefresh);
        refreshStore.revokeAllForUser(me.getId(), exemptHash);
        audit.record(me.getId(), "me.password.changed", "platform", me.getId().toString(), Map.of("ok", true));
        return Map.of("ok", true, "message", "密码已修改，其他设备已退出登录");
    }

    /** 刷新令牌哈希（与 AuthController 同口径：SHA-256 hex；明文永不落库） */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
