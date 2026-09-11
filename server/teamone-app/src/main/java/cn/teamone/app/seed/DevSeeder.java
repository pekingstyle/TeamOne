package cn.teamone.app.seed;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.Department;
import cn.teamone.platform.domain.ResourceAcl;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.DepartmentRepository;
import cn.teamone.platform.repo.ResourceAclRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开发种子（幂等）：部门 + 三个账号 + 一条 ACL 授予。
 * 账号口令见 deploy/README-dev.md（仅开发环境，生产禁用本 Seeder）。
 */
@Component
public class DevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final ResourceAclRepository acls;
    private final PasswordEncoder encoder;

    public DevSeeder(AppUserRepository users, DepartmentRepository departments,
                     ResourceAclRepository acls, PasswordEncoder encoder) {
        this.users = users;
        this.departments = departments;
        this.acls = acls;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Department dev = departments.findByName("平台研发部").orElseGet(() -> {
            Department d = new Department();
            d.setName("平台研发部");
            return departments.save(d);
        });

        AppUser admin = ensureUser("admin", "Admin@123", "平台管理员", "平台负责人",
                AppUser.PlatformRole.OWNER, dev);
        dev.setLeadUserId(admin.getId());
        departments.save(dev);
        ensureUser("dev1", "Dev@12345", "开发一号", "后端工程师", AppUser.PlatformRole.MEMBER, dev);
        AppUser dev2 = ensureUser("dev2", "Dev@12345", "开发二号", "前端工程师", AppUser.PlatformRole.MEMBER, dev);

        // 授予路径验证：dev2 显式获得 user:list（平台级）
        if (acls.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceIdAndAction(
                ResourceAcl.SubjectType.USER, dev2.getId(), "platform",
                PermissionService.PLATFORM_RESOURCE_ID, "user:list").isEmpty()) {
            ResourceAcl acl = new ResourceAcl();
            acl.setSubjectType(ResourceAcl.SubjectType.USER);
            acl.setSubjectId(dev2.getId());
            acl.setResourceType("platform");
            acl.setResourceId(PermissionService.PLATFORM_RESOURCE_ID);
            acl.setAction("user:list");
            acl.setEffect(ResourceAcl.Effect.ALLOW);
            acl.setGrantedBy(admin.getId());
            acls.save(acl);
        }
        log.info("[seed] dev accounts ready (admin/dev1/dev2)");
    }

    private AppUser ensureUser(String username, String password, String displayName,
                               String title, AppUser.PlatformRole role, Department dept) {
        return users.findByUsername(username).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setUsername(username);
            u.setPasswordHash(encoder.encode(password));
            u.setDisplayName(displayName);
            u.setTitle(title);
            u.setPlatformRole(role);
            u.setDepartmentId(dept.getId());
            return users.save(u);
        });
    }
}
