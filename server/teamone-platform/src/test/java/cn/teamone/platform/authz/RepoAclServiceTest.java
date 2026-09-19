package cn.teamone.platform.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.RepoMember;
import cn.teamone.platform.domain.RepoMember.Effect;
import cn.teamone.platform.domain.RepoMember.Source;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.RepoMemberRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;

/**
 * 仓库五步判定链单元测试（⑥i-A1 M-a · M-a 验收标准 1「判定链五分支单测 100%」）。
 *
 * <p>覆盖：矩阵抽样（四角色 × 关键动作 ≥12 断言，逐格照抄 docs/v2/13 §2.3）、
 * 平台 OWNER/ADMIN 短路、非 ACTIVE 拒绝、DENY 优先、visibility 只读兜底（PRIVATE 不兜底）、
 * 键版本缓存（命中短路查库 / 成员变更后判定刷新 / 无 Valkey 降级直查）、
 * INHERITED 建仓人 Owner 的最后 Owner 保护（409）。</p>
 *
 * <p>矩阵/兜底类用例一律走无缓存实例（排除缓存串扰，专测链路本身）；
 * 缓存语义由内嵌 {@link FakeRepoAclCache}（复现「版本进键 + bump 失效」）专测。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
class RepoAclServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID REPO = UUID.randomUUID();

    private AppUserRepository users;
    private RepoMemberRepository members;
    private AuditService audit;
    private FakeRepoAclCache cache;
    private RepoAclService acl;
    private RepoMemberService memberService;

    /** 测试内嵌假缓存：真实复现键版本方案语义（懒建=1 / 版本进键 / bump 失效），供失效断言用 */
    static class FakeRepoAclCache implements DecisionCache {
        final Map<UUID, Long> versions = new HashMap<>();
        final Map<String, Boolean> decisions = new HashMap<>();
        int bumps;

        long version(UUID repoId) {
            return versions.computeIfAbsent(repoId, k -> 1L);
        }

        String decKey(UUID repoId, long ver, UUID userId, String action) {
            return "acl:dec:" + repoId + ":" + ver + ":" + userId + ":" + action;
        }

        @Override
        public Optional<Long> repoVersion(UUID repoId) {
            return Optional.of(version(repoId));
        }

        @Override
        public Optional<Boolean> getRepoDecision(UUID repoId, long v, UUID userId, String action) {
            return Optional.ofNullable(decisions.get(decKey(repoId, v, userId, action)));
        }

        @Override
        public void putRepoDecision(UUID repoId, long v, UUID userId, String action, boolean allowed) {
            decisions.put(decKey(repoId, v, userId, action), allowed);
        }

        @Override
        public void bumpRepoVersion(UUID repoId) {
            versions.merge(repoId, 1L, Long::sum);
            bumps++;
            // 版本进键语义：旧版本决策键全部失去可命中性（等价 DEL 该仓全部判定键）
            decisions.keySet().removeIf(k -> k.contains(repoId.toString()));
        }

        // —— 既有平台级三方法：本测试不涉及，空实现 ——
        @Override
        public Optional<Boolean> get(UUID userId, String resourceType, UUID resourceId, String action) {
            return Optional.empty();
        }

        @Override
        public void put(UUID userId, String resourceType, UUID resourceId, String action, boolean allowed) {
            // no-op
        }

        @Override
        public void evictUser(UUID userId) {
            // no-op
        }
    }

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        members = mock(RepoMemberRepository.class);
        audit = mock(AuditService.class);
        cache = new FakeRepoAclCache();
        // 默认主体：ACTIVE 平台 MEMBER（走仓库链第 2/3/4/5 步）
        when(users.findById(USER)).thenReturn(Optional.of(member(USER)));
        when(users.findById(TARGET)).thenReturn(Optional.of(member(TARGET)));
        // 默认链实例：无缓存（矩阵/兜底用例专测链路，排除缓存串扰）；visibility 端口默认 INTERNAL
        acl = new RepoAclService(users, members, visibilityPortOf("INTERNAL"), Optional.empty());
        memberService = new RepoMemberService(members, users, audit, Optional.of(cache));
    }

    // ==================== 工具 ====================

    private static AppUser member(UUID id) {
        AppUser u = new AppUser();
        u.setUsername("u-" + id.toString().substring(0, 8));
        u.setDisplayName("用户 " + id.toString().substring(0, 8));
        u.setPasswordHash("test-only");
        return u;
    }

    private static AppUser platformUser(UUID id, AppUser.PlatformRole role) {
        AppUser u = member(id);
        u.setPlatformRole(role);
        return u;
    }

    private static RepoMember row(UUID repoId, UUID userId, RepoRole role, Source source, Effect effect) {
        RepoMember m = new RepoMember();
        m.setRepoId(repoId);
        m.setSubjectUserId(userId);
        m.setRole(role);
        m.setSource(source);
        m.setEffect(effect);
        m.setGrantedBy(userId);
        m.setGrantedAt(Instant.now());
        return m;
    }

    private RepoVisibilityPort visibilityPortOf(String visibility) {
        RepoVisibilityPort port = mock(RepoVisibilityPort.class);
        when(port.visibilityOf(any())).thenReturn(visibility);
        return port;
    }

    private void stubRows(RepoMember... rows) {
        when(members.findByRepoId(REPO)).thenReturn(List.of(rows));
    }

    // ============ 矩阵抽样（docs/v2/13 §2.3 逐格；PRIVATE 仓排除 visibility 兜底干扰） ============

    @Test
    void matrix_reporter_readonly_plus_review() {
        stubRows(row(REPO, USER, RepoRole.REPORTER, Source.DIRECT, Effect.ALLOW));
        // Reporter ✓ 格：view / pull / create-mr / review
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.PULL));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.CREATE_MR));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.REVIEW));
        // Reporter ✗ 格：push / delete-branch / merge / manage-protection / manage-settings / trigger-pipeline
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.PUSH));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.DELETE_BRANCH));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MERGE));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_PROTECTION));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_SETTINGS));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.TRIGGER_PIPELINE));
    }

    @Test
    void matrix_developer_write_but_not_delete_or_admin() {
        stubRows(row(REPO, USER, RepoRole.DEVELOPER, Source.DIRECT, Effect.ALLOW));
        // Developer ✓ 格：push / create-branch / merge / trigger-pipeline / baseline:create
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.PUSH));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.CREATE_BRANCH));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.MERGE));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.TRIGGER_PIPELINE));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.BASELINE_CREATE));
        // Developer ✗ 格：delete-branch（Q7 保守值）/ manage-protection / manage-settings / baseline:approve
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.DELETE_BRANCH));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_PROTECTION));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_SETTINGS));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.BASELINE_APPROVE));
    }

    @Test
    void matrix_maintainer_ops_but_not_settings() {
        stubRows(row(REPO, USER, RepoRole.MAINTAINER, Source.DIRECT, Effect.ALLOW));
        // Maintainer ✓ 格：delete-branch / manage-protection / register-deployment / baseline:approve
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.DELETE_BRANCH));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_PROTECTION));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.REGISTER_DEPLOYMENT));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.BASELINE_APPROVE));
        // Maintainer ✗ 格：manage-settings（Owner 独占格，§2.3）
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_SETTINGS));
    }

    @Test
    void matrix_owner_full_set() {
        stubRows(row(REPO, USER, RepoRole.OWNER, Source.INHERITED, Effect.ALLOW));
        // Owner ✓：全矩阵含独占格 manage-settings（INHERITED 来源不影响能力位）
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_SETTINGS));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_PROTECTION));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.MERGE));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
    }

    @Test
    void multi_rows_take_highest_role_capability_union() {
        // 同一用户多行命中取能力最大角色（§1.1 能力并集；枚举声明序 = 能力升序）
        stubRows(row(REPO, USER, RepoRole.REPORTER, Source.DIRECT, Effect.ALLOW),
                row(REPO, USER, RepoRole.DEVELOPER, Source.DIRECT, Effect.ALLOW));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.MERGE));
        // 并集中没有的格仍拒绝
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_SETTINGS));
    }

    // ==================== 步骤 0：ACTIVE 校验 ====================

    @Test
    void inactive_user_denied_even_with_role() {
        AppUser disabled = member(USER);
        disabled.setStatus(AppUser.Status.DISABLED);
        when(users.findById(USER)).thenReturn(Optional.of(disabled));
        stubRows(row(REPO, USER, RepoRole.OWNER, Source.INHERITED, Effect.ALLOW));
        // 非 ACTIVE 一律拒绝（步骤 0 先于一切，Owner 角色也不放行）
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
    }

    // ==================== 步骤 1：平台 OWNER/ADMIN 短路 ====================

    @Test
    void platform_owner_and_admin_short_circuit_everything() {
        stubRows(); // 无任何 repo_member 行 + PRIVATE 仓仍放行（每仓库隐式能力全集）
        when(users.findById(ADMIN_ID))
                .thenReturn(Optional.of(platformUser(ADMIN_ID, AppUser.PlatformRole.ADMIN)));
        assertTrue(acl.checkRepoPerm(ADMIN_ID, REPO, RepoActions.MANAGE_SETTINGS));
        when(users.findById(ADMIN_ID))
                .thenReturn(Optional.of(platformUser(ADMIN_ID, AppUser.PlatformRole.OWNER)));
        assertTrue(acl.checkRepoPerm(ADMIN_ID, REPO, RepoActions.MANAGE_PROTECTION));
        // 对照：平台 MEMBER 无条目则无管理能力（双轨不串联）
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MANAGE_SETTINGS));
    }

    // ==================== 步骤 2：DENY 优先 ====================

    @Test
    void deny_row_overrides_role_capability_and_visibility_fallback() {
        // INTERNAL 仓 + deny 行（role 保留 developer 原值，§4.1 封禁语义）：
        // 即使 visibility 兜底可救 view、角色本可 push，deny 命中即拒
        stubRows(row(REPO, USER, RepoRole.DEVELOPER, Source.DIRECT, Effect.DENY));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.PUSH));
    }

    // ==================== 步骤 4：visibility 只读兜底 ====================

    @Test
    void visibility_fallback_view_only_non_private() {
        stubRows(); // 无成员行
        // INTERNAL：view 兜底放行（现网全员可读的存量行为，零收紧根基）；push 不兜底
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.PUSH));
        // PUBLIC（预留态，行为等同 INTERNAL）
        acl = new RepoAclService(users, members, visibilityPortOf("PUBLIC"), Optional.empty());
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
        // PRIVATE：不兜底（M-a PRIVATE 过滤能力的判定根基）
        acl = new RepoAclService(users, members, visibilityPortOf("PRIVATE"), Optional.empty());
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
    }

    @Test
    void private_repo_visible_to_member_but_not_stranger() {
        // 陌生 MEMBER（无行）→ PRIVATE 不可见；命中 repo_member（reporter）→ 可见
        acl = new RepoAclService(users, members, visibilityPortOf("PRIVATE"), Optional.empty());
        UUID stranger = UUID.randomUUID();
        when(users.findById(stranger)).thenReturn(Optional.of(member(stranger)));
        stubRows(row(REPO, USER, RepoRole.REPORTER, Source.DIRECT, Effect.ALLOW));
        assertFalse(acl.checkRepoPerm(stranger, REPO, RepoActions.VIEW));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
    }

    // ============ 缓存：命中短路 + 键版本失效（变更后判定刷新） + 无 Valkey 降级 ============

    @Test
    void cache_hit_skips_db_until_version_bump() {
        // 带缓存实例：首查落库并回填缓存，二查命中缓存（不查库）
        acl = new RepoAclService(users, members, visibilityPortOf("PRIVATE"), Optional.of(cache));
        List<RepoMember> rows = List.of(row(REPO, USER, RepoRole.REPORTER, Source.DIRECT, Effect.ALLOW));
        when(members.findByRepoId(REPO)).thenReturn(rows);
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
        verify(members, times(1)).findByRepoId(REPO);
        assertEquals(1L, cache.version(REPO)); // 读时懒建 = 1

        // 成员变更 → bump（INCR ver + DEL 判定键）→ 判定按新事实刷新
        when(members.findByRepoIdAndSubjectUserId(REPO, USER)).thenReturn(Optional.of(rows.get(0)));
        memberService.removeMember(REPO, USER, ADMIN_ID); // reporter 非 Owner → 直接删除
        when(members.findByRepoId(REPO)).thenReturn(List.of()); // 回收后无行

        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW)); // PRIVATE 且无行 → 不可见
        assertEquals(2L, cache.version(REPO)); // 版本已 INCR
        assertEquals(1, cache.bumps);
        verify(members, times(2)).findByRepoId(REPO); // bump 后强制重查
        verify(audit).record(any(), eq("acl.revoke"), any(), any(), any());
    }

    @Test
    void cache_unavailable_degrades_to_db_chain() {
        stubRows(row(REPO, USER, RepoRole.REPORTER, Source.DIRECT, Effect.ALLOW));
        // 无 Valkey（empty SPI）→ 异常降级直查 DB，判定正确性不受影响
        assertTrue(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW));
        assertFalse(acl.checkRepoPerm(USER, REPO, RepoActions.MERGE));
    }

    // ==================== INHERITED Owner 保护（最后 Owner 保护口径 409） ====================

    @Test
    void inherited_sole_owner_cannot_be_downgraded() {
        // ⑥i-Q4 裁决：Owner 可经成员端点授予/降级，但唯一 Owner 行降级 → 409 最后 Owner 保护
        when(members.findByRepoIdAndSubjectUserId(REPO, USER))
                .thenReturn(Optional.of(row(REPO, USER, RepoRole.OWNER, Source.INHERITED, Effect.ALLOW)));
        when(members.countByRepoIdAndRoleAndEffect(REPO, RepoRole.OWNER, Effect.ALLOW)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> memberService.upsertMember(REPO, USER, "maintainer", ADMIN_ID));
        assertEquals(ErrorCode.PLT_4091, ex.errorCode());
        verify(members, never()).save(any(RepoMember.class));
    }

    @Test
    void inherited_owner_can_be_downgraded_when_not_sole() {
        // 多 Owner 场景：INHERITED 建仓人行降级放行（建仓人徽标=历史身份保留，角色=当前授权）
        when(members.findByRepoIdAndSubjectUserId(REPO, USER))
                .thenReturn(Optional.of(row(REPO, USER, RepoRole.OWNER, Source.INHERITED, Effect.ALLOW)));
        when(members.countByRepoIdAndRoleAndEffect(REPO, RepoRole.OWNER, Effect.ALLOW)).thenReturn(2L);
        when(members.save(any(RepoMember.class))).thenAnswer(inv -> inv.getArgument(0));
        memberService.upsertMember(REPO, USER, "developer", ADMIN_ID);
        ArgumentCaptor<RepoMember> saved = ArgumentCaptor.forClass(RepoMember.class);
        verify(members).save(saved.capture());
        assertEquals(RepoRole.DEVELOPER, saved.getValue().getRole());
        assertEquals(Source.INHERITED, saved.getValue().getSource()); // 建仓人来源保留
        verify(audit).record(any(), eq("acl.role"), any(), any(), any());
    }

    @Test
    void owner_role_can_be_granted_via_panel() {
        // ⑥i-Q4 裁决：Owner 为可授予角色——「先授新 Owner、再降旧 Owner」两步即完成转移
        when(members.findByRepoIdAndSubjectUserId(REPO, TARGET)).thenReturn(Optional.empty());
        when(members.save(any(RepoMember.class))).thenAnswer(inv -> inv.getArgument(0));
        memberService.upsertMember(REPO, TARGET, "owner", ADMIN_ID);
        ArgumentCaptor<RepoMember> saved = ArgumentCaptor.forClass(RepoMember.class);
        verify(members).save(saved.capture());
        assertEquals(RepoRole.OWNER, saved.getValue().getRole());
        assertEquals(Source.DIRECT, saved.getValue().getSource());
        verify(audit).record(any(), eq("acl.grant"), any(), any(), any());
    }

    @Test
    void sole_owner_cannot_be_removed_inherited_or_direct() {
        // 唯一 Owner 行（建仓人 INHERITED）删除 → 409
        when(members.findByRepoIdAndSubjectUserId(REPO, USER)).thenReturn(Optional.of(
                row(REPO, USER, RepoRole.OWNER, Source.INHERITED, Effect.ALLOW)));
        when(members.countByRepoIdAndRoleAndEffect(REPO, RepoRole.OWNER, Effect.ALLOW)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> memberService.removeMember(REPO, USER, ADMIN_ID));
        assertEquals(ErrorCode.PLT_4091, ex.errorCode());
        verify(members, never()).delete(any(RepoMember.class));

        // 多 Owner 场景：移除非最后一个（DIRECT）Owner 放行
        when(members.findByRepoIdAndSubjectUserId(REPO, TARGET)).thenReturn(Optional.of(
                row(REPO, TARGET, RepoRole.OWNER, Source.DIRECT, Effect.ALLOW)));
        when(members.countByRepoIdAndRoleAndEffect(REPO, RepoRole.OWNER, Effect.ALLOW)).thenReturn(2L);
        memberService.removeMember(REPO, TARGET, ADMIN_ID);
        verify(members).delete(any(RepoMember.class));
    }

    @Test
    void remove_unknown_member_404() {
        when(members.findByRepoIdAndSubjectUserId(REPO, TARGET)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> memberService.removeMember(REPO, TARGET, ADMIN_ID));
        assertEquals(ErrorCode.PLT_4040, ex.errorCode());
    }

    @Test
    void upsert_rejects_invalid_role_and_inactive_target() {
        // owner 已放开为可授予角色（⑥i-Q4 裁决，见 owner_role_can_be_granted_via_panel）
        // 非法角色串（400）
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> memberService.upsertMember(REPO, TARGET, "guest", ADMIN_ID));
        assertEquals(ErrorCode.PLT_4000, ex2.errorCode());
        // 目标非 ACTIVE（400，§5.1 前置校验①）
        AppUser disabled = member(TARGET);
        disabled.setStatus(AppUser.Status.DISABLED);
        when(users.findById(TARGET)).thenReturn(Optional.of(disabled));
        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> memberService.upsertMember(REPO, TARGET, "developer", ADMIN_ID));
        assertEquals(ErrorCode.PLT_4000, ex3.errorCode());
    }

    @Test
    void upsert_grant_and_role_change_audited_and_version_bumped() {
        // 新授予：DIRECT 行 + acl.grant 审计 + 版本 bump
        when(members.findByRepoIdAndSubjectUserId(REPO, TARGET)).thenReturn(Optional.empty());
        when(members.save(any(RepoMember.class))).thenAnswer(inv -> inv.getArgument(0));
        memberService.upsertMember(REPO, TARGET, "maintainer", ADMIN_ID);
        ArgumentCaptor<RepoMember> saved = ArgumentCaptor.forClass(RepoMember.class);
        verify(members).save(saved.capture());
        assertEquals(RepoRole.MAINTAINER, saved.getValue().getRole());
        assertEquals(Source.DIRECT, saved.getValue().getSource());
        assertEquals(Effect.ALLOW, saved.getValue().getEffect());
        verify(audit).record(any(), eq("acl.grant"), any(), any(), any());
        assertEquals(1, cache.bumps);

        // 改角色：acl.role 审计 + 再次 bump（版本键从未被读时不存在，INCR-on-missing 首次=1，
        // 与真 Valkey 语义一致：无读者时无缓存可失效，两次 bump → 2）
        when(members.findByRepoIdAndSubjectUserId(REPO, TARGET)).thenReturn(Optional.of(saved.getValue()));
        memberService.upsertMember(REPO, TARGET, "reporter", ADMIN_ID);
        verify(audit).record(any(), eq("acl.role"), any(), any(), any());
        assertEquals(2, cache.bumps);
        assertEquals(2L, cache.version(REPO));
    }

    @Test
    void grant_inherited_owner_is_idempotent() {
        // 建仓系统授予：无行落 INHERITED owner 行；已有行不覆盖（幂等）
        memberService.grantInheritedOwner(REPO, USER);
        ArgumentCaptor<RepoMember> saved = ArgumentCaptor.forClass(RepoMember.class);
        verify(members).save(saved.capture());
        assertEquals(RepoRole.OWNER, saved.getValue().getRole());
        assertEquals(Source.INHERITED, saved.getValue().getSource());
        when(members.findByRepoIdAndSubjectUserId(REPO, USER)).thenReturn(Optional.of(saved.getValue()));
        memberService.grantInheritedOwner(REPO, USER);
        verify(members, times(1)).save(any(RepoMember.class)); // 幂等：第二次不写
    }

    // ==================== require 断言出口 ====================

    @Test
    void require_throws_403_with_plt_4030() {
        stubRows(); // 陌生用户 + PRIVATE → 拒绝
        acl = new RepoAclService(users, members, visibilityPortOf("PRIVATE"), Optional.empty());
        cn.teamone.shared.api.PermissionDeniedException ex =
                assertThrows(cn.teamone.shared.api.PermissionDeniedException.class,
                        () -> acl.require(USER, REPO, RepoActions.VIEW));
        assertEquals(ErrorCode.PLT_4030, ex.errorCode());
    }
}
