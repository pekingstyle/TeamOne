package cn.teamone.platform.app;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.PersonalAccessToken;
import cn.teamone.platform.dto.PatDto.CreatePatRequest;
import cn.teamone.platform.dto.PatDto.CreatePatResponse;
import cn.teamone.platform.dto.PatDto.PatSummary;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.PersonalAccessTokenRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 个人访问令牌（PAT）应用服务（P0 企业治理底座 · 开发者终端与外部自动化鉴权凭据引擎）。
 *
 * <p>安全规则：
 * <ul>
 *   <li>令牌采用 256 位加密级强随机数生成，标准前缀为 {@code t1_pat_}；</li>
 *   <li>库内仅保存单向 SHA-256 哈希值，绝对不留存可逆明文；</li>
 *   <li>明文令牌仅在创建成功时返回且展示一次，离开界面后不可复现；</li>
 *   <li>每次鉴权成功异步推进 {@code last_used_at} 记录活跃状态。</li>
 * </ul>
 * </p>
 */
@Service
public class PatService {

    public static final String PAT_PREFIX = "t1_pat_";

    private final PersonalAccessTokenRepository tokenRepo;
    private final AppUserRepository userRepo;
    private final AuditService audit;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public PatService(PersonalAccessTokenRepository tokenRepo,
                      AppUserRepository userRepo,
                      AuditService audit) {
        this.tokenRepo = tokenRepo;
        this.userRepo = userRepo;
        this.audit = audit;
    }

    /**
     * 为指定用户创建新的个人访问令牌（PAT）。
     *
     * @param userId 拥有者用户 ID
     * @param req 创建请求参数（名称、权限作用域、有效期天数）
     * @return 包含完整明文令牌的创建响应（仅此一次返回）
     */
    @Transactional
    public CreatePatResponse createToken(UUID userId, CreatePatRequest req) {
        if (req.name() == null || req.name().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "令牌名称(name)不能为空");
        }

        // ① 生成 32 字节（256 bit）强随机密文串
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String hex = HexFormat.of().formatHex(randomBytes);
        String rawToken = PAT_PREFIX + hex;

        // ② 计算脱敏前缀（用于列表友好辨识）与单向 SHA-256 哈希
        String tokenPrefix = rawToken.substring(0, Math.min(15, rawToken.length())) + "...";
        String tokenHash = sha256Hex(rawToken);

        // ③ 计算过期时间
        Instant expiresAt = null;
        if (req.expiresDays() != null && req.expiresDays() > 0) {
            expiresAt = Instant.now().plus(req.expiresDays(), ChronoUnit.DAYS);
        }

        String scopes = (req.scopes() == null || req.scopes().trim().isEmpty()) ? "all" : req.scopes().trim();

        // ④ 持久化实体
        PersonalAccessToken token = new PersonalAccessToken(
                userId, req.name().trim(), tokenHash, tokenPrefix, scopes, expiresAt);
        PersonalAccessToken saved = tokenRepo.save(token);

        // ⑤ 审计留痕
        String resourceId = saved.getId() != null ? saved.getId().toString() : saved.getTokenPrefix();
        audit.record(userId, "token.create", "pat", resourceId,
                Map.of("name", saved.getName(), "prefix", tokenPrefix, "scopes", scopes));

        return new CreatePatResponse(
                saved.getId(),
                saved.getName(),
                rawToken,
                tokenPrefix,
                saved.getScopes(),
                saved.getExpiresAt(),
                saved.getCreatedAt()
        );
    }

    /**
     * 查询指定用户创建的全部个人访问令牌（脱敏视图）。
     *
     * @param userId 所属用户 ID
     * @return 脱敏后的令牌列表
     */
    @Transactional(readOnly = true)
    public List<PatSummary> listTokens(UUID userId) {
        return tokenRepo.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(PatSummary::fromEntity)
                .toList();
    }

    /**
     * 撤销/销毁个人访问令牌。
     *
     * @param userId 所属用户 ID
     * @param tokenId 待销毁令牌 ID
     */
    @Transactional
    public void revokeToken(UUID userId, UUID tokenId) {
        PersonalAccessToken token = tokenRepo.findByIdAndUserId(tokenId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "令牌不存在或已被撤销"));

        tokenRepo.delete(token);

        audit.record(userId, "token.revoke", "pat", tokenId.toString(),
                Map.of("name", token.getName(), "prefix", token.getTokenPrefix()));
    }

    /**
     * 使用原始明文令牌执行身份认证校验（供网关拦截器 JwtAuthFilter 与 CLI 调用）。
     *
     * @param rawToken 用户 HTTP Header 传入的 Bearer Token
     * @return 成功鉴权则返回对应有效且未被禁用的 AppUser 实例，失败返回 empty
     */
    @Transactional
    public Optional<AppUser> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(PAT_PREFIX)) {
            return Optional.empty();
        }

        String tokenHash = sha256Hex(rawToken);
        Optional<PersonalAccessToken> opt = tokenRepo.findByTokenHash(tokenHash);
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        PersonalAccessToken pat = opt.get();
        // 校验过期性
        if (pat.isExpired()) {
            return Optional.empty();
        }

        // 异步更新活跃时间戳
        tokenRepo.updateLastUsedAt(pat.getId(), Instant.now());

        // 提取用户并校验正常激活状态
        return userRepo.findById(pat.getUserId())
                .filter(u -> u.getStatus() == AppUser.Status.ACTIVE);
    }

    /** 计算 UTF-8 字符串的 SHA-256 64位十六进制小写哈希 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
