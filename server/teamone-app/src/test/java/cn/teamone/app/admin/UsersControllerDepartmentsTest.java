package cn.teamone.app.admin;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.Department;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 部门拓扑端点单测（⑥n · UT-88）：memberIds 由 app_user.department_id 反查聚合，
 * 无成员部门输出空清单而不是缺席（前端拓扑渲染需要稳定形状）。
 *
 * @author Ivan Yang, 2026-09-21
 */
class UsersControllerDepartmentsTest {

    @Test
    void departmentTree_aggregatesMembersByDepartmentId() {
        Department platform = dept("平台研发部", null);
        Department frontend = dept("前端组", platform.getId());

        AppUser admin = user("admin", platform.getId());
        AppUser dev1 = user("dev1", platform.getId());
        AppUser dev2 = user("dev2", frontend.getId());
        AppUser loner = user("ghost", null); // 未挂部门：不得计入任何部门

        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findAll()).thenReturn(List.of(admin, dev1, dev2, loner));
        DepartmentRepository departments = mock(DepartmentRepository.class);
        when(departments.findAll()).thenReturn(List.of(frontend, platform));

        UsersController controller = new UsersController(users, departments);
        List<UsersController.DepartmentView> tree = controller.departmentTree();

        assertEquals(2, tree.size());
        // 排序按名称：前端组 < 平台研发部
        UsersController.DepartmentView fe = tree.get(0);
        assertEquals("前端组", fe.name());
        assertEquals(platform.getId(), fe.parentId());
        assertEquals(List.of(dev2.getId()), fe.memberIds());

        UsersController.DepartmentView pf = tree.get(1);
        assertEquals("平台研发部", pf.name());
        // 同部门两名成员聚合；无成员引用不出现
        assertEquals(2, pf.memberIds().size());
        assertEquals(List.of(admin.getId(), dev1.getId()), pf.memberIds());
    }

    private Department dept(String name, UUID parentId) {
        Department d = new Department();
        d.setName(name);
        d.setParentId(parentId);
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        return d;
    }

    private AppUser user(String username, UUID departmentId) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDepartmentId(departmentId);
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }
}
