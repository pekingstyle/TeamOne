package cn.teamone.eng.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cn.teamone.eng.domain.Repository;
import cn.teamone.platform.authz.RepoAclService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.authz.RepoRole;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;

/**
 * {@link RepoPermChecker}（服务层回退检查器）单元测试（⑥i-A1 M-a · ⑥j-A M-b 扩展）。
 *
 * <p>两路定位器之二：无 /repos 前缀端点（M-b 的 /commits、/mrs/{id}/blame 等）服务层
 * 显式调用本检查器；本测试验证其向 platform {@link RepoAclService} 的忠实委托与 403 透传，
 * 以及 M-b 新增三族入口（角色下限 / 跨仓 PRIVATE 过滤 / me-permissions 数据源）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
class RepoPermCheckerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID REPO = UUID.randomUUID();

    private RepoAclService acl;
    private RepoPermChecker checker;

    @BeforeEach
    void setUp() {
        acl = mock(RepoAclService.class);
        checker = new RepoPermChecker(acl);
    }

    @Test
    void check_delegates_to_platform_chain() {
        org.mockito.Mockito.when(acl.checkRepoPerm(USER, REPO, RepoActions.VIEW)).thenReturn(true);
        assertTrue(checker.check(USER, REPO, RepoActions.VIEW));
        verify(acl).checkRepoPerm(USER, REPO, RepoActions.VIEW);
    }

    @Test
    void require_delegates_and_propagates_403() {
        // 放行路径：委托 require 且不抛
        checker.require(USER, REPO, RepoActions.VIEW);
        verify(acl).require(USER, REPO, RepoActions.VIEW);

        // 拒绝路径：403 T1-PLT-4030 原样透传给调用方（服务层回退口径与切面一致）
        doThrow(new PermissionDeniedException(ErrorCode.PLT_4030, "无权限", null))
                .when(acl).require(eq(USER), eq(REPO), anyString());
        PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                () -> checker.require(USER, REPO, RepoActions.MERGE));
        org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.PLT_4030, ex.errorCode());
    }

    // ==================== M-b 扩展：角色下限（§2.4「Maintainer+」派生口径） ====================

    @Test
    void roleFloor_delegates_and_propagates_403() {
        // 断言版：委托 requireRoleAtLeast，场景文案透传
        checker.requireRoleAtLeast(USER, REPO, RepoRole.MAINTAINER, "关闭评审");
        verify(acl).requireRoleAtLeast(USER, REPO, RepoRole.MAINTAINER, "关闭评审");

        doThrow(new PermissionDeniedException(ErrorCode.PLT_4030, "无权限", null))
                .when(acl).requireRoleAtLeast(eq(USER), eq(REPO), eq(RepoRole.MAINTAINER), anyString());
        assertThrows(PermissionDeniedException.class,
                () -> checker.requireRoleAtLeast(USER, REPO, RepoRole.MAINTAINER, "豁免单测门禁"));

        // 判定版：委托 checkRoleAtLeast 布尔结果
        when(acl.checkRoleAtLeast(USER, REPO, RepoRole.OWNER)).thenReturn(false);
        assertFalse(checker.checkRoleAtLeast(USER, REPO, RepoRole.OWNER));
    }

    // ==================== M-b 扩展：跨仓列表 PRIVATE 过滤（B4） ====================

    @Test
    void filterVisible_privateRepo_checked_per_repo_internal_passthrough() {
        // AppUser 无 id setter（@GeneratedValue），主体以 any() 匹配（此处锁过滤语义而非主体透传）
        Repository internal = repo("r-internal", "INTERNAL");
        Repository priv = repo("r-private", "PRIVATE");
        when(acl.checkRepoPerm(any(), eq(priv.getId()), eq(RepoActions.VIEW))).thenReturn(true);

        // INTERNAL 直接过（不逐仓查链）；PRIVATE 命中 view 才可见
        List<Repository> visible = checker.filterVisible(member(USER), List.of(internal, priv));
        assertEquals(List.of(internal, priv), visible);
        verify(acl).checkRepoPerm(any(), eq(priv.getId()), eq(RepoActions.VIEW));
        verify(acl, never()).checkRepoPerm(any(), eq(internal.getId()), anyString());

        // PRIVATE 未命中 view → 剔除
        when(acl.checkRepoPerm(any(), eq(priv.getId()), eq(RepoActions.VIEW))).thenReturn(false);
        assertEquals(List.of(internal), checker.filterVisible(member(USER), List.of(internal, priv)));
    }

    @Test
    void filterVisible_platform_admin_and_null_principal_short_circuit() {
        Repository priv = repo("r-private", "PRIVATE");
        // 平台 OWNER/ADMIN 与 null 主体（防御分支）整体放行，不逐仓判定
        assertEquals(List.of(priv), checker.filterVisible(platformAdmin(AppUser.PlatformRole.OWNER), List.of(priv)));
        assertEquals(List.of(priv), checker.filterVisible(platformAdmin(AppUser.PlatformRole.ADMIN), List.of(priv)));
        assertEquals(List.of(priv), checker.filterVisible(null, List.of(priv)));
        verify(acl, never()).checkRepoPerm(any(), any(), anyString());
    }

    // ==================== M-b 扩展：me/permissions 数据源（§4.5 契约） ====================

    @Test
    void mePermissions_sources_delegate_to_platform() {
        when(acl.effectiveRoleOf(USER, REPO)).thenReturn(RepoRole.MAINTAINER);
        when(acl.capabilitiesOf(USER, REPO)).thenReturn(List.of(RepoActions.VIEW, RepoActions.PULL));
        assertEquals(RepoRole.MAINTAINER, checker.effectiveRoleOf(USER, REPO));
        assertEquals(List.of(RepoActions.VIEW, RepoActions.PULL), checker.capabilitiesOf(USER, REPO));

        // 无角色（null 透传——前端渲染仅 visibility 兜底只读态）
        when(acl.effectiveRoleOf(USER, REPO)).thenReturn(null);
        assertNull(checker.effectiveRoleOf(USER, REPO));
    }

    @Test
    void isPlatformAdmin_recognizes_owner_admin_only() {
        assertTrue(RepoPermChecker.isPlatformAdmin(platformAdmin(AppUser.PlatformRole.OWNER)));
        assertTrue(RepoPermChecker.isPlatformAdmin(platformAdmin(AppUser.PlatformRole.ADMIN)));
        assertFalse(RepoPermChecker.isPlatformAdmin(member(USER)));
        assertFalse(RepoPermChecker.isPlatformAdmin(null));
    }

    // ==================== 工具 ====================

    private static AppUser member(UUID id) {
        AppUser u = new AppUser();
        u.setUsername("u-" + id.toString().substring(0, 8));
        u.setPlatformRole(AppUser.PlatformRole.MEMBER);
        return u;
    }

    private static AppUser platformAdmin(AppUser.PlatformRole role) {
        AppUser u = member(UUID.randomUUID());
        u.setPlatformRole(role);
        return u;
    }

    private static Repository repo(String name, String visibility) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setRepoPath(name + "/" + name + ".git");
        r.setDefaultBranch("main");
        r.setVisibility(visibility);
        return r;
    }
}
