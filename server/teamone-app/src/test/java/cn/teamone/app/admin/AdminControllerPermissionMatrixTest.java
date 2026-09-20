package cn.teamone.app.admin;

import cn.teamone.eng.app.RepoPermChecker;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.authz.RepoRole;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.ResourceAclRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限判定矩阵端点单测（⑥m · UT-84）：行 = 真实用户 × 列 = 真实仓库，
 * 单元格 = 服务端五步链（RepoPermChecker mock 代理真实契约），前端不判定。
 *
 * @author Ivan Yang, 2026-09-21
 */
class AdminControllerPermissionMatrixTest {

    @Test
    void permissionMatrix_mapsRolesCapabilitiesAndPlatformAdminFlag() {
        UUID repoId = UUID.randomUUID();
        Repository repo = new Repository();
        repo.setId(repoId);
        repo.setName("teamone");
        repo.setVisibility("INTERNAL");

        AppUser owner = user("admin", "平台管理员", AppUser.PlatformRole.OWNER);
        AppUser member = user("dev1", "开发一号", AppUser.PlatformRole.MEMBER);
        AppUser nobody = user("dev2", "开发二号", AppUser.PlatformRole.MEMBER);

        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findAll()).thenReturn(List.of(owner, member, nobody));
        RepositoryRepository repos = mock(RepositoryRepository.class);
        when(repos.findAll()).thenReturn(List.of(repo));
        RepoPermChecker checker = mock(RepoPermChecker.class);
        // 平台 OWNER：短路全量（14 动作）；成员：五步链判得 maintainer/13；无角色：null（线格式空串）
        when(checker.effectiveRoleOf(owner.getId(), repoId)).thenReturn(RepoRole.OWNER);
        when(checker.capabilitiesOf(owner.getId(), repoId)).thenReturn(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n"));
        when(checker.effectiveRoleOf(member.getId(), repoId)).thenReturn(RepoRole.MAINTAINER);
        when(checker.capabilitiesOf(member.getId(), repoId)).thenReturn(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m"));
        when(checker.effectiveRoleOf(eq(nobody.getId()), any())).thenReturn(null);
        when(checker.capabilitiesOf(eq(nobody.getId()), any())).thenReturn(List.of());

        AdminController controller = new AdminController(users, mock(ResourceAclRepository.class), repos, checker);
        Map<String, Object> resp = controller.permissionMatrix();

        List<?> repoCols = (List<?>) resp.get("repos");
        assertEquals(1, repoCols.size());
        assertEquals("teamone", ((Map<?, ?>) repoCols.get(0)).get("name"));
        assertEquals("INTERNAL", ((Map<?, ?>) repoCols.get(0)).get("visibility"));

        List<?> rows = (List<?>) resp.get("users");
        assertEquals(3, rows.size());

        Map<?, ?> ownerRow = rowOf(rows, "admin");
        assertEquals("OWNER", ownerRow.get("platformRole"));
        Map<?, ?> ownerCell = cellOf(ownerRow, repoId);
        assertEquals("owner", ownerCell.get("role"));
        assertEquals(14, ownerCell.get("capCount"));
        assertEquals(true, ownerCell.get("platformAdmin"), "平台 OWNER 短路须带 platformAdmin 标记");

        Map<?, ?> memberRow = rowOf(rows, "dev1");
        Map<?, ?> memberCell = cellOf(memberRow, repoId);
        assertEquals("maintainer", memberCell.get("role"));
        assertEquals(13, memberCell.get("capCount"));
        assertEquals(false, memberCell.get("platformAdmin"), "非平台管理员不得误标 platformAdmin");

        Map<?, ?> nobodyCell = cellOf(rowOf(rows, "dev2"), repoId);
        assertEquals("", nobodyCell.get("role"), "无角色输出空串（前端渲染「—」）");
        assertEquals(0, nobodyCell.get("capCount"));
        assertEquals(false, nobodyCell.get("platformAdmin"));
    }

    private AppUser user(String username, String displayName, AppUser.PlatformRole role) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPlatformRole(role);
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> rowOf(List<?> rows, String username) {
        return (Map<?, ?>) rows.stream().filter(r -> username.equals(((Map<?, ?>) r).get("username")))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> cellOf(Map<?, ?> row, UUID repoId) {
        return ((Map<String, Map<?, ?>>) row.get("memberships")).get(repoId.toString());
    }
}
