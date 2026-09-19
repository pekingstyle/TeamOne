package cn.teamone.app.admin;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
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

    public UsersController(AppUserRepository users) {
        this.users = users;
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

    @GetMapping("/me/profile")
    public UserView profile(@AuthenticationPrincipal AppUser me) {
        return UserView.of(me);
    }
}
