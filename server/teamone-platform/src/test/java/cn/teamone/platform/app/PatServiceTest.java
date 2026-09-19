package cn.teamone.platform.app;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.PersonalAccessToken;
import cn.teamone.platform.dto.PatDto.CreatePatRequest;
import cn.teamone.platform.dto.PatDto.CreatePatResponse;
import cn.teamone.platform.dto.PatDto.PatSummary;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.PersonalAccessTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 个人访问令牌（PAT）应用服务单元测试（P0 企业治理底座 · 覆盖生成、哈希验签、撤销与过期判定）。
 */
class PatServiceTest {

    private PersonalAccessTokenRepository tokenRepo;
    private AppUserRepository userRepo;
    private AuditService audit;
    private PatService service;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tokenRepo = mock(PersonalAccessTokenRepository.class);
        userRepo = mock(AppUserRepository.class);
        audit = mock(AuditService.class);
        service = new PatService(tokenRepo, userRepo, audit);
    }

    /**
     * 正向测试：生成新令牌，返回包含 t1_pat_ 前缀的明文串，库内保存 SHA-256 哈希。
     */
    @Test
    void testCreateTokenSuccess() {
        CreatePatRequest req = new CreatePatRequest("本地终端 CLI", "repo:read,repo:write", 30);

        when(tokenRepo.save(any(PersonalAccessToken.class))).thenAnswer(inv -> {
            PersonalAccessToken t = inv.getArgument(0);
            return t;
        });

        CreatePatResponse res = service.createToken(USER_ID, req);

        assertNotNull(res.rawToken());
        assertTrue(res.rawToken().startsWith(PatService.PAT_PREFIX));
        assertEquals("本地终端 CLI", res.name());
        assertEquals("repo:read,repo:write", res.scopes());
        assertNotNull(res.expiresAt());
        assertTrue(res.tokenPrefix().startsWith(PatService.PAT_PREFIX));

        // 验证持久化调用并核验 SHA-256 密文
        verify(tokenRepo).save(argThat(t -> {
            assertEquals(USER_ID, t.getUserId());
            assertNotNull(t.getTokenHash());
            assertEquals(64, t.getTokenHash().length());
            return true;
        }));

        verify(audit).record(eq(USER_ID), eq("token.create"), eq("pat"), any(), any());
    }

    /**
     * 正向测试：通过明文令牌有效完成认证，刷新活跃时间戳并返回活跃用户。
     */
    @Test
    void testAuthenticateSuccess() {
        String rawToken = PatService.PAT_PREFIX + "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        String tokenHash = PatService.sha256Hex(rawToken);

        PersonalAccessToken pat = new PersonalAccessToken(
                USER_ID, "CI Runner", tokenHash, "t1_pat_abc...", "all",
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        AppUser activeUser = new AppUser();
        activeUser.setUsername("ci_bot");
        activeUser.setStatus(AppUser.Status.ACTIVE);

        when(tokenRepo.findByTokenHash(tokenHash)).thenReturn(Optional.of(pat));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(activeUser));

        Optional<AppUser> authenticated = service.authenticate(rawToken);

        assertTrue(authenticated.isPresent());
        assertEquals("ci_bot", authenticated.get().getUsername());

        // 验证活跃时间推进
        verify(tokenRepo).updateLastUsedAt(eq(pat.getId()), any());
    }

    /**
     * 反向测试：已过期令牌认证返回 empty。
     */
    @Test
    void testAuthenticateExpiredReturnsEmpty() {
        String rawToken = PatService.PAT_PREFIX + "expired123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        String tokenHash = PatService.sha256Hex(rawToken);

        // 过期时间设在 1 小时前
        PersonalAccessToken pat = new PersonalAccessToken(
                USER_ID, "过期令牌", tokenHash, "t1_pat_exp...", "all",
                Instant.now().minus(1, ChronoUnit.HOURS)
        );

        when(tokenRepo.findByTokenHash(tokenHash)).thenReturn(Optional.of(pat));

        Optional<AppUser> authenticated = service.authenticate(rawToken);

        assertTrue(authenticated.isEmpty());
        verify(tokenRepo, never()).updateLastUsedAt(any(), any());
    }

    /**
     * 反向测试：用户处于停用 (DISABLED) 状态时认证拒绝。
     */
    @Test
    void testAuthenticateDisabledUserReturnsEmpty() {
        String rawToken = PatService.PAT_PREFIX + "disabled12345678abcdef0123456789abcdef0123456789abcdef0123456789";
        String tokenHash = PatService.sha256Hex(rawToken);

        PersonalAccessToken pat = new PersonalAccessToken(
                USER_ID, "停用用户令牌", tokenHash, "t1_pat_dis...", "all", null
        );

        AppUser disabledUser = new AppUser();
        disabledUser.setStatus(AppUser.Status.DISABLED);

        when(tokenRepo.findByTokenHash(tokenHash)).thenReturn(Optional.of(pat));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(disabledUser));

        Optional<AppUser> authenticated = service.authenticate(rawToken);

        assertTrue(authenticated.isEmpty());
    }

    /**
     * 正向测试：列出用户令牌返回脱敏视图。
     */
    @Test
    void testListTokensReturnsMaskedView() {
        PersonalAccessToken t1 = new PersonalAccessToken(USER_ID, "Token 1", "hash1", "t1_pat_111...", "all", null);
        PersonalAccessToken t2 = new PersonalAccessToken(USER_ID, "Token 2", "hash2", "t1_pat_222...", "repo:read", Instant.now().minus(1, ChronoUnit.DAYS));

        when(tokenRepo.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(t1, t2));

        List<PatSummary> summaries = service.listTokens(USER_ID);

        assertEquals(2, summaries.size());
        assertEquals("Token 1", summaries.get(0).name());
        assertFalse(summaries.get(0).expired());
        assertTrue(summaries.get(1).expired());
    }

    /**
     * 正向测试：安全吊销自身令牌。
     */
    @Test
    void testRevokeTokenSuccess() {
        UUID tokenId = UUID.randomUUID();
        PersonalAccessToken t = new PersonalAccessToken(USER_ID, "To Revoke", "hash", "t1_pat_rev...", "all", null);

        when(tokenRepo.findByIdAndUserId(tokenId, USER_ID)).thenReturn(Optional.of(t));

        service.revokeToken(USER_ID, tokenId);

        verify(tokenRepo).delete(t);
        verify(audit).record(eq(USER_ID), eq("token.revoke"), eq("pat"), eq(tokenId.toString()), any());
    }
}
