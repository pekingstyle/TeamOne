package cn.teamone.app.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.teamone.eng.app.RepoPermChecker;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.authz.RepoRole;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.ResourceAclRepository;
import cn.teamone.shared.auth.RequirePerm;

/** 平台管理端点（platform:manage——仅 OWNER/ADMIN 可达，用于验收第 4 步 ACL 之外的角色短路） */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AppUserRepository users;
    private final ResourceAclRepository acls;
    private final RepositoryRepository repositories;
    private final RepoPermChecker permChecker;

    public AdminController(AppUserRepository users, ResourceAclRepository acls,
                           RepositoryRepository repositories, RepoPermChecker permChecker) {
        this.users = users;
        this.acls = acls;
        this.repositories = repositories;
        this.permChecker = permChecker;
    }

    @GetMapping("/stats")
    @RequirePerm(resourceType = "platform", action = "platform:manage")
    public Map<String, Object> stats() {
        return Map.of("users", users.count(), "aclEntries", acls.count());
    }

    /**
     * 权限判定矩阵数据源（⑥m 真实化：行 = 真实用户 × 列 = 真实仓库，单元格 = 服务端五步链判定）。
     *
     * <p>替代前端 store 原型矩阵（虚构用户/产品 + 客户端简化四步判定）：判定真相与放行同源——
     * 逐用户×逐仓调用 {@link RepoPermChecker#effectiveRoleOf} 与 {@link RepoPermChecker#capabilitiesOf}
     * （含平台 OWNER/ADMIN 短路、DENY、visibility 兜底），前端只做展示不做判定。</p>
     *
     * <p>契约：{@code 200 {repos:[{id,name,visibility}], users:[{id,username,displayName,
     * platformRole, memberships:{<repoId>:{role,capCount,platformAdmin}}}]}}；role 为
     * {@link RepoRole#wire()} 小写线格式，无角色为空串（visibility 兜底时 capabilitiesOf 仍可能
     * 返回 [view]，角色如实显示空）。</p>
     */
    @GetMapping("/permission-matrix")
    @RequirePerm(resourceType = "platform", action = "platform:manage")
    public Map<String, Object> permissionMatrix() {
        List<Repository> repos = repositories.findAll();
        List<Map<String, Object>> repoCols = new ArrayList<>(repos.size());
        for (Repository r : repos) {
            repoCols.add(Map.of(
                    "id", r.getId().toString(),
                    "name", r.getName(),
                    "visibility", r.getVisibility()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AppUser u : users.findAll()) {
            Map<String, Object> memberships = new LinkedHashMap<>();
            for (Repository r : repos) {
                RepoRole role = permChecker.effectiveRoleOf(u.getId(), r.getId());
                int capCount = permChecker.capabilitiesOf(u.getId(), r.getId()).size();
                boolean platformAdmin = role == RepoRole.OWNER && RepoPermChecker.isPlatformAdmin(u);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("role", role == null ? "" : role.wire());
                cell.put("capCount", capCount);
                cell.put("platformAdmin", platformAdmin);
                memberships.put(r.getId().toString(), cell);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId().toString());
            row.put("username", u.getUsername());
            row.put("displayName", u.getDisplayName());
            row.put("platformRole", u.getPlatformRole().name());
            row.put("memberships", memberships);
            rows.add(row);
        }
        return Map.of("repos", repoCols, "users", rows);
    }
}
