package cn.teamone.app.admin;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.auth.RequirePerm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/users")
    @RequirePerm(resourceType = "platform", action = "user:list")
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::of).toList();
    }

    @GetMapping("/me/profile")
    public UserView profile(@AuthenticationPrincipal AppUser me) {
        return UserView.of(me);
    }
}
