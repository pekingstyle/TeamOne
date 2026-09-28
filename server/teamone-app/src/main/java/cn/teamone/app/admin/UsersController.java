package cn.teamone.app.admin;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.Department;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.DepartmentRepository;
import cn.teamone.shared.auth.RequirePerm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 用户目录（受 user:list 权限保护——M0 验收的 403 矩阵端点） */
@RestController
@RequestMapping("/api/v1")
public class UsersController {

    private final AppUserRepository users;
    private final DepartmentRepository departments;

    public UsersController(AppUserRepository users, DepartmentRepository departments) {
        this.users = users;
        this.departments = departments;
    }

    public record UserView(UUID id, String username, String displayName, String title,
                           String email, String platformRole, UUID departmentId,
                           int dailyCapacityHours, String status) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getTitle(),
                    u.getEmail(), u.getPlatformRole().name(), u.getDepartmentId(),
                    u.getDailyCapacityHours(), u.getStatus().name());
        }
    }

    /** 用户简要投影（选人/指派组件用，只透出 4 个字段） */
    public record UserBrief(UUID id, String username, String displayName, String title) {
        static UserBrief of(AppUser u) {
            return new UserBrief(u.getId(), u.getUsername(), u.getDisplayName(), u.getTitle());
        }
    }

    /** 部门拓扑投影（⑥n 真实化：memberIds 由 app_user.department_id 反查——部门表本身不存成员清单） */
    public record DepartmentView(UUID id, String name, UUID parentId, UUID leadUserId,
                                 List<UUID> memberIds) {
        static DepartmentView of(Department d, List<UUID> memberIds) {
            return new DepartmentView(d.getId(), d.getName(), d.getParentId(), d.getLeadUserId(), memberIds);
        }
    }

    @GetMapping("/users")
    @RequirePerm(resourceType = "platform", action = "user:list")
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::of).toList();
    }

    /** 用户简要清单（仅需登录态，不加 @RequirePerm——前端选人下拉/负责人筛选数据源） */
    @GetMapping("/users/briefs")
    public List<UserBrief> briefs() {
        return users.findAll().stream()
                .map(UserBrief::of)
                .sorted(Comparator.comparing(UserBrief::username,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 部门拓扑（⑥n 真实化：仅需登录态，同 /users/briefs 口径——团队页「部门拓扑」页签数据源，
     * 替代 store 原型虚构部门）。产品归组在前端按「产品负责人所属部门」推导（真实 schema
     * 的 product 无 department_id 列，不虚构关联）。
     */
    @GetMapping("/departments")
    public List<DepartmentView> departmentTree() {
        var byDept = users.findAll().stream()
                .filter(u -> u.getDepartmentId() != null)
                .collect(java.util.stream.Collectors.groupingBy(AppUser::getDepartmentId));
        return departments.findAll().stream()
                .map(d -> DepartmentView.of(d,
                        byDept.getOrDefault(d.getId(), List.of()).stream().map(AppUser::getId).toList()))
                .sorted(Comparator.comparing(DepartmentView::name,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @GetMapping("/me/profile")
    public UserView profile(@AuthenticationPrincipal AppUser me) {
        return UserView.of(me);
    }
}
