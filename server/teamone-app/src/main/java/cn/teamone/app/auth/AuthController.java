package cn.teamone.app.auth;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final long refreshTtlDays;

    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthController(AppUserRepository users, RefreshTokenStore refreshStore,
                          PasswordEncoder encoder, JwtService jwt, ObjectMapper om,
                          @Value("${teamone.jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        this.users = users;
        this.refreshStore = refreshStore;
        this.encoder = encoder;
        this.jwt = jwt;
        this.om = om;
        this.refreshTtlDays = refreshTtlDays;
    }

    public record LoginReq(String username, String password) {}

    public record UserView(UUID id, String username, String displayName, String title,
                           String platformRole, int dailyCapacityHours) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getTitle(),
                    u.getPlatformRole().name(), u.getDailyCapacityHours());
        }
    }

    public record TokenRes(String accessToken, String refreshToken, String tokenType,
                           long expiresIn, UserView user) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginReq req) throws Exception {
        var user = users.findByUsername(req.username() == null ? "" : req.username())
                .filter(u -> encoder.matches(req.password() == null ? "" : req.password(), u.getPasswordHash()))
                .filter(u -> u.getStatus() == AppUser.Status.ACTIVE);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, cn.teamone.shared.api.ErrorCode.PLT_4011, null);
        }
        return ResponseEntity.ok(issue(user.get()));
    }

    public record RefreshReq(String refreshToken) {}

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshReq req) throws Exception {
        String hash = sha256(req.refreshToken() == null ? "" : req.refreshToken());
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
        return ResponseEntity.ok(issue(user.get()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody RefreshReq req) {
        refreshStore.revoke(sha256(req.refreshToken() == null ? "" : req.refreshToken()));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser me) {
        return ResponseEntity.ok(UserView.of(me));
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
