package cn.teamone.eng.app;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cn.teamone.platform.authz.RepoAclService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;

/**
 * {@link RepoPermChecker}（服务层回退检查器）单元测试（⑥i-A1 M-a）。
 *
 * <p>两路定位器之二：无 /repos 前缀端点（M-b 的 /commits、/mrs/{id}/blame 等）服务层
 * 显式调用本检查器；本测试验证其向 platform {@link RepoAclService} 的忠实委托与 403 透传。</p>
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
                .when(acl).require(eq(USER), eq(REPO), eq(RepoActions.MERGE));
        PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                () -> checker.require(USER, REPO, RepoActions.MERGE));
        org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.PLT_4030, ex.errorCode());
    }
}
