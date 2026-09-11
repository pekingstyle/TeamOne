package cn.teamone.app.admin;

import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.ResourceAclRepository;
import cn.teamone.shared.auth.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 平台管理端点（platform:manage——仅 OWNER/ADMIN 可达，用于验收第 4 步 ACL 之外的角色短路） */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AppUserRepository users;
    private final ResourceAclRepository acls;

    public AdminController(AppUserRepository users, ResourceAclRepository acls) {
        this.users = users;
        this.acls = acls;
    }

    @GetMapping("/stats")
    @RequirePerm(resourceType = "platform", action = "platform:manage")
    public Map<String, Object> stats() {
        return Map.of("users", users.count(), "aclEntries", acls.count());
    }
}
