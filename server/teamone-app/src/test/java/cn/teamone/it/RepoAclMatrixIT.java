package cn.teamone.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cn.teamone.eng.domain.CommitWorkItem;
import cn.teamone.eng.domain.MergeRequest;
import cn.teamone.eng.domain.PipelineRun;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.CommitWorkItemRepository;
import cn.teamone.eng.repo.MergeRequestRepository;
import cn.teamone.eng.repo.PipelineRunRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓库 ACL 权限矩阵集成回归（⑥j-A M-b B5 · docs/v2/13 §6.2 B5 / §2.3 默认能力全表）。
 *
 * <p>矩阵设计：4 仓库角色（owner=own1 / maintainer=dev1 / developer=dev2 / reporter=qa1，
 * 均为平台 MEMBER——平台管理员短路单列 admin 格）× 关键代表端点逐格断言：
 * view 恒通（INTERNAL 兜底）、create-branch 分级、delete-branch 仅 Maintainer+、
 * merge Developer+、manage-protection/settings 分级、trigger-pipeline / baseline 分级、
 * members 管理仅 owner、越权 403 与治理 422 区分、无 /repos 前缀回退端点
 * （/mrs/{id}/merge、/mrs/{id}/blame、/baselines/{id}/*）、跨仓 PRIVATE 过滤三处
 * （/repos、/mrs、/pipelines、/commits）、Q5 register-deployment 双路口径、
 * close/reopen B3 新口径、缓存失效（授权后判定刷新）。</p>
 *
 * <p>夹具：用户/仓库走 API 建仓+授权（POST /repos 用 admin；JPA 补建 3 个夹具用户）；
 * 种子 teamone 仓（GitFlow 规则 + 演示 MR ×3）为矩阵主仓。Git 夹具注记：生产代码 git
 * 子进程唯一出口仍为 infra.git（ArchUnit R8，DoNotIncludeTests 不扫描测试类）——本 IT
 * 在 <b>测试代码</b>内以 ProcessBuilder 引导真实 bare 库（两提交 + clone --bare），
 * 使分支增删/cherry-pick 等真 git 端点可断言 200 侧。</p>
 *
 * <p>执行条件：无 Docker 自动跳过；failsafe 默认 skipITs=true，CI 传 -DskipITs=false 启用。
 * 缓存失效断言注记：IT 环境无 Valkey（DecisionCache 空实现，判定恒直查 DB），「授权后
 * 判定刷新」以真实授权→复测放行断言；键版本失效语义由 RepoAclServiceTest 内嵌假缓存单测覆盖。</p>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = cn.teamone.app.TeamOneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teamone.seed.enabled=true",
                "teamone.seed.demo=true",
                "teamone.event.enabled=false",
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepoAclMatrixIT {

    /** 仓库判定缓存为可选 SPI：IT 无 Valkey，自动降级直查 DB（判定正确性不受影响） */

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 真实 bare 库引导根（teamone/teamone.git 由下方静态块克隆生成，两提交） */
    static final Path GIT_ROOT;

    static {
        try {
            GIT_ROOT = Files.createTempDirectory("teamone-acl-it-git");
            Path src = Files.createDirectories(GIT_ROOT.resolve("seed-src"));
            git(src, "init");
            git(src, "symbolic-ref", "HEAD", "refs/heads/main");
            Files.writeString(src.resolve("README.md"), "# acl-matrix-it\n");
            git(src, "add", "-A");
            git(src, "-c", "user.name=IT", "-c", "user.email=it@teamone.cn", "commit", "-m", "init");
            Files.writeString(src.resolve("second.txt"), "second commit\n");
            git(src, "add", "-A");
            git(src, "-c", "user.name=IT", "-c", "user.email=it@teamone.cn", "commit", "-m", "second");
            Path bare = GIT_ROOT.resolve("teamone");
            Files.createDirectories(bare);
            git(GIT_ROOT, "clone", "--bare", src.toAbsolutePath().toString(),
                    bare.resolve("teamone.git").toString());
        } catch (Exception e) {
            throw new IllegalStateException("git 夹具引导失败（CI 镜像需自带 git）: " + e.getMessage(), e);
        }
    }

    /** 测试夹具专用 git 直调（R8 扫描排除测试类；生产代码纪律不受影响，见类注释） */
    private static void git(Path cwd, String... args) throws Exception {
        Process p = new ProcessBuilder(prepend("git", args))
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
        }
    }

    private static String[] prepend(String head, String... tail) {
        String[] cmd = new String[tail.length + 1];
        cmd[0] = head;
        System.arraycopy(tail, 0, cmd, 1, tail.length);
        return cmd;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teamone.git.root", () -> GIT_ROOT.toAbsolutePath().toString());
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper om;

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    RepositoryRepository repositoryRepo;

    @Autowired
    MergeRequestRepository mrRepo;

    @Autowired
    PipelineRunRepository pipelineRepo;

    @Autowired
    CommitWorkItemRepository commitWorkItems;

    // PER_METHOD 下跨 @Test 保持（GateFlowIT 同款纪律）
    private static String adminToken;
    private static String maintainerToken; // dev1
    private static String developerToken;  // dev2
    private static String reporterToken;   // qa1
    private static String ownerToken;      // own1（仓库 Owner，平台 MEMBER）
    private static String outsiderToken;   // outsider1（无任何仓库角色）

    private static String teamoneRepoId;
    private static String mr1Id;           // 演示 !1（open，merge 矩阵用）
    private static String mr2Id;           // 演示 !2（open，exempt/close 矩阵用）
    private static String privateRepoId;
    private static String privateMrId;
    private static String teamoneRunId;    // dev2 触发的 teamone 流水线（Q5 部署登记用）
    private static String baselineId;      // dev2 建的草稿基线（approve 矩阵用）

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<String> res) throws Exception {
        return om.readValue(res.getBody(), Map.class);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object req) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(req, headers), String.class);
    }

    private String login(String username, String password) throws Exception {
        return (String) body(exchange(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("username", username, "password", password))).get("accessToken");
    }

    /** 403 断言（能力缺失口径：code=T1-PLT-4030，§3.3——与治理 422 区分） */
    private void assertDenied403(ResponseEntity<String> res) throws Exception {
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(body(res).get("code")).isEqualTo("T1-PLT-4030");
    }

    /** 422 治理断言（业务码非 403：能力已过、治理拦截，§3.3 区分用例） */
    private void assertGovernanceNot403(ResponseEntity<String> res, int expected) throws Exception {
        assertThat(res.getStatusCode().value()).isEqualTo(expected);
        assertThat(res.getStatusCode().value()).isNotEqualTo(403);
    }

    private void grant(String token, String repoNameOrId, String userId, String role) throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.PUT, "/api/v1/repos/" + repoNameOrId + "/members",
                token, Map.of("userId", userId, "role", role));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }

    private String userIdOf(String username) {
        return users.findByUsername(username).orElseThrow().getId().toString();
    }

    /** JPA 补建夹具用户（密码可登录；平台 MEMBER，挂 admin 部门） */
    private void ensureUser(String username, String displayName) {
        users.findByUsername(username).orElseGet(() -> {
            AppUser admin = users.findByUsername("admin").orElseThrow();
            AppUser u = new AppUser();
            u.setUsername(username);
            u.setPasswordHash(encoder.encode("Dev@12345"));
            u.setDisplayName(displayName);
            u.setTitle("ACL 矩阵夹具");
            u.setPlatformRole(AppUser.PlatformRole.MEMBER);
            u.setDepartmentId(admin.getDepartmentId());
            return users.save(u);
        });
    }

    // ==================== 夹具：用户 + 四角色授权 ====================

    @Test
    @Order(1)
    void step1_fixture_users_roles_and_me_permissions() throws Exception {
        ensureUser("qa1", "测试一号（矩阵 Reporter）");
        ensureUser("own1", "仓库主（矩阵 Owner）");
        ensureUser("outsider1", "路人（无仓库角色）");

        adminToken = login("admin", "Admin@123");
        maintainerToken = login("dev1", "Dev@12345");
        developerToken = login("dev2", "Dev@12345");
        reporterToken = login("qa1", "Dev@12345");
        ownerToken = login("own1", "Dev@12345");
        outsiderToken = login("outsider1", "Dev@12345");

        // 种子主仓解析（INTERNAL + GitFlow 规则 + 演示 MR）
        ResponseEntity<String> repoRes = exchange(HttpMethod.GET, "/api/v1/repos/teamone", adminToken, null);
        assertThat(repoRes.getStatusCode().value()).isEqualTo(200);
        teamoneRepoId = (String) body(repoRes).get("id");

        // admin（平台 OWNER 短路 manage-settings）授予四角色（Q4：owner 可授予）
        grant(adminToken, teamoneRepoId, userIdOf("dev1"), "maintainer");
        grant(adminToken, teamoneRepoId, userIdOf("dev2"), "developer");
        grant(adminToken, teamoneRepoId, userIdOf("qa1"), "reporter");
        grant(adminToken, teamoneRepoId, userIdOf("own1"), "owner");

        // me/permissions 契约（§4.5）：role/capabilities/平台短路 platformAdmin
        Map<String, Object> adminPerms = body(exchange(HttpMethod.GET,
                "/api/v1/repos/" + teamoneRepoId + "/me/permissions", adminToken, null));
        assertThat(adminPerms.get("role")).isEqualTo("owner");
        assertThat(adminPerms.get("platformAdmin")).isEqualTo(true);
        assertThat(caps(adminPerms)).hasSize(14);

        Map<String, Object> ownerPerms = body(exchange(HttpMethod.GET,
                "/api/v1/repos/" + teamoneRepoId + "/me/permissions", ownerToken, null));
        assertThat(ownerPerms.get("role")).isEqualTo("owner");
        assertThat(ownerPerms.get("platformAdmin")).isNull(); // 仓库 Owner ≠ 平台管理员
        assertThat(caps(ownerPerms)).hasSize(14);

        Map<String, Object> maintainerPerms = body(exchange(HttpMethod.GET,
                "/api/v1/repos/" + teamoneRepoId + "/me/permissions", maintainerToken, null));
        assertThat(maintainerPerms.get("role")).isEqualTo("maintainer");
        assertThat(caps(maintainerPerms))
                .containsExactly("view", "pull", "push", "create-branch", "delete-branch", "create-mr",
                        "merge", "manage-protection", "trigger-pipeline", "register-deployment",
                        "review", "baseline:create", "baseline:approve");

        Map<String, Object> devPerms = body(exchange(HttpMethod.GET,
                "/api/v1/repos/" + teamoneRepoId + "/me/permissions", developerToken, null));
        assertThat(devPerms.get("role")).isEqualTo("developer");
        assertThat(caps(devPerms)).hasSize(9);

        Map<String, Object> repPerms = body(exchange(HttpMethod.GET,
                "/api/v1/repos/" + teamoneRepoId + "/me/permissions", reporterToken, null));
        assertThat(repPerms.get("role")).isEqualTo("reporter");
        assertThat(caps(repPerms))
                .containsExactly("view", "pull", "create-mr", "review");

        // 无角色 + INTERNAL：role=null，visibility 兜底仅 view（M-a 链内口径）
        Map<String, Object> outPerms = body(exchange(HttpMethod.GET,
                "/api/v1/repos/" + teamoneRepoId + "/me/permissions", outsiderToken, null));
        assertThat(outPerms.get("role")).isNull();
        assertThat(caps(outPerms)).containsExactly("view");

        // 演示 MR 定位（!1 merge 矩阵 / !2 exempt+close 矩阵）
        List<Map<String, Object>> mrs = itemsOf(exchange(HttpMethod.GET,
                "/api/v1/mrs?repoId=" + teamoneRepoId + "&size=50", adminToken, null));
        // MR 列表 DTO 的编号字段实名是 number（详情另有 mrNumber 场景无关；IT 复跑修正）
        mr1Id = mrs.stream().filter(m -> Integer.valueOf(1).equals(m.get("number")))
                .findFirst().orElseThrow().get("id").toString();
        mr2Id = mrs.stream().filter(m -> Integer.valueOf(2).equals(m.get("number")))
                .findFirst().orElseThrow().get("id").toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(ResponseEntity<String> res) throws Exception {
        return (List<Map<String, Object>>) body(res).get("items");
    }

    /** 裸数组响应直读（如 GET /repos/{id}/branch-rules 返回 [ ... ]） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(ResponseEntity<String> res) throws Exception {
        return om.readValue(res.getBody(), List.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> caps(Map<String, Object> perms) {
        return (List<String>) perms.get("capabilities");
    }


    // ==================== view 恒通（INTERNAL 兜底） ====================

    @Test
    @Order(2)
    void step2_view_always_allowed_on_internal_repo() throws Exception {
        // 四角色 + 无角色陌生人（visibility 兜底）全部 200；单仓读含 detail/branches/search
        for (String t : List.of(reporterToken, developerToken, maintainerToken, ownerToken, outsiderToken)) {
            assertThat(exchange(HttpMethod.GET, "/api/v1/repos/teamone", t, null).getStatusCode().value()).isEqualTo(200);
            assertThat(exchange(HttpMethod.GET, "/api/v1/repos/teamone/branches", t, null).getStatusCode().value()).isEqualTo(200);
            assertThat(exchange(HttpMethod.GET, "/api/v1/repos/teamone/search?q=teamone", t, null).getStatusCode().value()).isEqualTo(200);
        }
    }

    // ==================== create-branch 分级 + 403 vs 422 ====================

    @Test
    @Order(3)
    void step3_create_branch_tiers_and_403_vs_422() throws Exception {
        // Reporter 无能力 → 403（能力优先于治理：非法名也不到 422）
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                reporterToken, Map.of("name", "nope-it-acl")));
        // Developer+ 放行（GitFlow 规则命中 feature/*）
        assertThat(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                developerToken, Map.of("name", "feature/it-acl-dev")).getStatusCode().value()).isEqualTo(200);
        assertThat(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                maintainerToken, Map.of("name", "feature/it-acl-mnt")).getStatusCode().value()).isEqualTo(200);
        assertThat(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                ownerToken, Map.of("name", "feature/it-acl-own")).getStatusCode().value()).isEqualTo(200);

        // 区分用例（§3.3）：同分支名——Reporter 403（能力缺失）vs Developer 422（治理 ENG_4255）
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                reporterToken, Map.of("name", "release-direct-it")));
        ResponseEntity<String> gov = exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                developerToken, Map.of("name", "release-direct-it"));
        assertThat(gov.getStatusCode().value()).isEqualTo(422);
        assertThat(body(gov).get("code")).isEqualTo("T1-ENG-4255");
    }

    // ==================== delete-branch 仅 Maintainer+ ====================

    @Test
    @Order(4)
    void step4_delete_branch_maintainer_only() throws Exception {
        // 待删分支由 admin 预建（未受保护、命中 feature/* 规则）
        assertThat(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                adminToken, Map.of("name", "feature/it-acl-del")).getStatusCode().value()).isEqualTo(200);
        assertDenied403(exchange(HttpMethod.DELETE, "/api/v1/repos/teamone/branches/feature/it-acl-del",
                reporterToken, null));
        // Developer 不可删（Q7 保守值）
        assertDenied403(exchange(HttpMethod.DELETE, "/api/v1/repos/teamone/branches/feature/it-acl-del",
                developerToken, null));
        // Maintainer 可删（204）
        assertThat(exchange(HttpMethod.DELETE, "/api/v1/repos/teamone/branches/feature/it-acl-del",
                maintainerToken, null).getStatusCode().value()).isEqualTo(204);
    }

    // ==================== merge Developer+（无 /repos 前缀回退端点） ====================

    @Test
    @Order(5)
    void step5_merge_capability_via_no_prefix_fallback() throws Exception {
        // Reporter（服务层回退 MR→repo）→ 403
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr1Id + "/merge", reporterToken, null));
        // Developer 有 merge 能力：ACL 放行后落入六项门禁（评审未全部批准 → 400 而非 403，
        // 即「能力与门禁是两回事」§2.3 注记的正证）
        ResponseEntity<String> gateBlocked = exchange(HttpMethod.POST, "/api/v1/mrs/" + mr1Id + "/merge",
                developerToken, null);
        assertGovernanceNot403(gateBlocked, 400);
        assertThat(body(gateBlocked).get("code")).isNotEqualTo("T1-PLT-4030");
    }

    // ==================== manage-protection 分级（Maintainer+） ====================

    @Test
    @Order(6)
    void step6_manage_protection_maintainer_only() throws Exception {
        // 读端点 view：Reporter 200（§5.2 读收口，不收紧）
        assertThat(exchange(HttpMethod.GET, "/api/v1/repos/teamone/protections",
                reporterToken, null).getStatusCode().value()).isEqualTo(200);
        // 写端点：Reporter/Developer 403；Maintainer 200
        Map<String, Object> prot = Map.of("branchPattern", "release/it-acl-*", "requireMr", true,
                "minApprovals", 1, "requireUnitTest", false, "blockForcePush", true);
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/repos/teamone/protections", reporterToken, prot));
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/repos/teamone/protections", developerToken, prot));
        ResponseEntity<String> saved = exchange(HttpMethod.POST, "/api/v1/repos/teamone/protections",
                maintainerToken, prot);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        String protectionId = body(saved).get("id").toString();
        // Developer 删除保护同样 403，Maintainer 可删（还原夹具）
        assertDenied403(exchange(HttpMethod.DELETE, "/api/v1/repos/teamone/protections/" + protectionId,
                developerToken, null));
        assertThat(exchange(HttpMethod.DELETE, "/api/v1/repos/teamone/protections/" + protectionId,
                maintainerToken, null).getStatusCode().value()).isEqualTo(200);
    }

    // ==================== manage-settings 分级（Owner）+ members 仅 owner ====================

    @Test
    @Order(7)
    void step7_manage_settings_and_members_owner_only() throws Exception {
        // branch-rules 写端点（批口径挂 manage-settings）：Maintainer 403、Owner 200（原样回写）
        // branch-rules 列表端点返回裸 JSON 数组（无 items 包装；IT 复跑修正）
        List<Map<String, Object>> rules = listOf(exchange(HttpMethod.GET,
                "/api/v1/repos/teamone/branch-rules", ownerToken, null));
        Map<String, Object> put = Map.of("model", "custom", "rules", rules);
        assertDenied403(exchange(HttpMethod.PUT, "/api/v1/repos/teamone/branch-rules", maintainerToken, put));
        assertDenied403(exchange(HttpMethod.PUT, "/api/v1/repos/teamone/branch-rules", developerToken, put));
        assertThat(exchange(HttpMethod.PUT, "/api/v1/repos/teamone/branch-rules",
                ownerToken, put).getStatusCode().value()).isEqualTo(200);

        // members 管理仅 owner（manage-settings）：Maintainer 403；Owner 授予/回收 200/204
        Map<String, Object> grantOutsider = Map.of("userId", userIdOf("outsider1"), "role", "reporter");
        assertDenied403(exchange(HttpMethod.PUT, "/api/v1/repos/teamone/members", maintainerToken, grantOutsider));
        grant(ownerToken, teamoneRepoId, userIdOf("outsider1"), "reporter");
        assertThat(exchange(HttpMethod.DELETE,
                "/api/v1/repos/teamone/members/" + userIdOf("outsider1"), ownerToken, null)
                .getStatusCode().value()).isEqualTo(204); // 还原「无角色路人」夹具
    }

    // ==================== trigger-pipeline / baseline 分级 ====================

    @Test
    @Order(8)
    void step8_trigger_pipeline_and_baseline_tiers() throws Exception {
        // trigger-pipeline：Reporter 403、Developer 200（切面路径 /repos/{id}/pipelines/trigger）
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/repos/teamone/pipelines/trigger",
                reporterToken, Map.of("branch", "main")));
        ResponseEntity<String> run = exchange(HttpMethod.POST, "/api/v1/repos/teamone/pipelines/trigger",
                developerToken, Map.of("branch", "main"));
        assertThat(run.getStatusCode().value()).isEqualTo(200);
        teamoneRunId = body(run).get("id").toString();

        // baseline:create：Reporter 403、Developer 200（切面路径）
        Map<String, Object> baseline = Map.of("name", "it-acl-b1", "type", "functional",
                "tagRef", "it-acl-b1-tag", "artifactVersion", "1.0.0-it");
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/repos/teamone/baselines", reporterToken, baseline));
        ResponseEntity<String> created = exchange(HttpMethod.POST, "/api/v1/repos/teamone/baselines",
                developerToken, baseline);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        baselineId = body(created).get("id").toString();

        // submit（无 /repos 前缀回退，baseline:create 档）：Developer 200、Reporter 403
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/baselines/" + baselineId + "/submit",
                reporterToken, null));
        assertThat(exchange(HttpMethod.POST, "/api/v1/baselines/" + baselineId + "/submit",
                developerToken, null).getStatusCode().value()).isEqualTo(200);

        // baseline:approve（回退端点，Maintainer+）：Developer 403、Maintainer 200（单签未定版）
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/baselines/" + baselineId + "/approve",
                developerToken, null));
        assertThat(exchange(HttpMethod.POST, "/api/v1/baselines/" + baselineId + "/approve",
                maintainerToken, null).getStatusCode().value()).isEqualTo(200);
    }

    // ==================== MR exempt（Maintainer+）与 close/reopen（B3 新口径） ====================

    @Test
    @Order(9)
    void step9_mr_exempt_and_close_reopen_b3() throws Exception {
        // 单测豁免 Maintainer+：Reporter/Developer 403，Maintainer 200（回退端点；!2 单测门禁未过可豁免）
        Map<String, Object> exempt = Map.of("reason", "it-acl 矩阵豁免验证");
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/checks/unit-test/exempt",
                reporterToken, exempt));
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/checks/unit-test/exempt",
                developerToken, exempt));
        assertThat(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/checks/unit-test/exempt",
                maintainerToken, exempt).getStatusCode().value()).isEqualTo(200);

        // close/reopen B3：作者∨Maintainer+∨平台管理员（!2 作者 = dev2）
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/close", outsiderToken, null));
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/close", reporterToken, null));
        // 非作者 Maintainer 可关/重开
        assertThat(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/close",
                maintainerToken, null).getStatusCode().value()).isEqualTo(200);
        assertThat(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/reopen",
                maintainerToken, null).getStatusCode().value()).isEqualTo(200);
        // 作者本人短路（无需角色档位）
        assertThat(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/close",
                developerToken, null).getStatusCode().value()).isEqualTo(200);
        assertThat(exchange(HttpMethod.POST, "/api/v1/mrs/" + mr2Id + "/reopen",
                developerToken, null).getStatusCode().value()).isEqualTo(200);
    }

    // ==================== Q5：register-deployment 仓库角色 + 回退双路口径 ====================

    @Test
    @Order(10)
    void step10_register_deployment_q5_dual_track() throws Exception {
        String releaseId = UUID.randomUUID().toString(); // eng 视为逻辑引用（prd ⊥ eng），任意 UUID 即可
        // 主口径（带 pipelineRunId→repo）：Reporter/Developer 403，Maintainer 200
        Map<String, Object> withRun = Map.of("env", "dev", "pipelineRunId", teamoneRunId);
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/releases/" + releaseId + "/deployments",
                reporterToken, withRun));
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/releases/" + releaseId + "/deployments",
                developerToken, withRun));
        assertThat(exchange(HttpMethod.POST, "/api/v1/releases/" + releaseId + "/deployments",
                maintainerToken, withRun).getStatusCode().value()).isEqualTo(200);
        // 首挂完成后锚点恒为版本自身运行：携带外来 runId（不归属本版本）→ 400 锚点校验（Q5 修正）
        Map<String, Object> foreignRun = Map.of("env", "dev", "pipelineRunId", UUID.randomUUID());
        assertThat(exchange(HttpMethod.POST, "/api/v1/releases/" + releaseId + "/deployments",
                maintainerToken, foreignRun).getStatusCode().value()).isEqualTo(400);

        // Q5 回退口径（未挂流水线）：非平台管理员 403（platform:manage），平台 OWNER 短路 200
        // 用全新未关联版本，避免命中上面的首挂引导路径
        String releaseId2 = UUID.randomUUID().toString();
        Map<String, Object> noRun = Map.of("env", "staging");
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/releases/" + releaseId2 + "/deployments",
                maintainerToken, noRun));
        assertThat(exchange(HttpMethod.POST, "/api/v1/releases/" + releaseId2 + "/deployments",
                adminToken, noRun).getStatusCode().value()).isEqualTo(200);
    }

    // ==================== 跨仓 PRIVATE 过滤（B4：/repos、/mrs、/pipelines、/commits） ====================

    @Test
    @Order(11)
    void step11_private_repo_cross_list_filtering() throws Exception {
        // 建 PRIVATE 仓（API 建仓默认 INTERNAL，JPA 翻转为 PRIVATE——夹具从简）
        ResponseEntity<String> created = exchange(HttpMethod.POST, "/api/v1/repos", adminToken,
                Map.of("name", "it-acl-private", "description", "PRIVATE 过滤矩阵夹具"));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        privateRepoId = body(created).get("id").toString();
        Repository priv = repositoryRepo.findById(UUID.fromString(privateRepoId)).orElseThrow();
        priv.setVisibility("PRIVATE");
        repositoryRepo.save(priv);

        // PRIVATE 仓数据夹具（JPA：MR + 流水线运行 + 关联提交）
        MergeRequest mr = new MergeRequest();
        mr.setRepoId(priv.getId());
        mr.setMrNumber(1);
        mr.setTitle("PRIVATE 仓 MR（过滤矩阵夹具）");
        mr.setSourceBranch("feature/private");
        mr.setTargetBranch("main");
        mr.setAuthorId(users.findByUsername("admin").orElseThrow().getId());
        mr.setStatus("open");
        privateMrId = mrRepo.save(mr).getId().toString();

        PipelineRun run = new PipelineRun();
        run.setRepoId(priv.getId());
        run.setTitle("#IT PRIVATE 过滤夹具");
        run.setBranch("main");
        run.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        run.setCommitShort("0123456");
        run.setTrigger("manual");
        run.setStatus("passed");
        run.setStartedAt(Instant.now());
        pipelineRepo.save(run);

        CommitWorkItem cwi = new CommitWorkItem();
        cwi.setRepoKey(priv.getRepoPath());
        cwi.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        cwi.setAuthorName("IT");
        cwi.setAuthorEmail("it@teamone.cn");
        cwi.setCommittedAt(Instant.now());
        cwi.setSubject("PRIVATE 过滤夹具提交");
        cwi.setWorkItemKey("IT-ACL-1");
        commitWorkItems.save(cwi);

        // ① GET /repos：路人列表不可见，admin 可见（M-a A6 路径复验）
        assertThat(itemsOf(exchange(HttpMethod.GET, "/api/v1/repos?size=100", outsiderToken, null))
                .stream().noneMatch(r -> "it-acl-private".equals(r.get("name")))).isTrue();
        assertThat(itemsOf(exchange(HttpMethod.GET, "/api/v1/repos?size=100", adminToken, null))
                .stream().anyMatch(r -> "it-acl-private".equals(r.get("name")))).isTrue();

        // ② 详情/单仓读 403（非成员，含另一仓的 Reporter——授权不外溢）
        assertDenied403(exchange(HttpMethod.GET, "/api/v1/repos/it-acl-private", outsiderToken, null));
        assertDenied403(exchange(HttpMethod.GET, "/api/v1/repos/it-acl-private", reporterToken, null));

        // ③ GET /mrs 跨仓过滤：PRIVATE 仓 MR 元数据不返回；无前缀回退详情 403
        assertThat(itemsOf(exchange(HttpMethod.GET, "/api/v1/mrs?size=100", outsiderToken, null))
                .stream().noneMatch(m -> privateRepoId.equals(m.get("repoId").toString()))).isTrue();
        assertThat(itemsOf(exchange(HttpMethod.GET, "/api/v1/mrs?size=100", adminToken, null))
                .stream().anyMatch(m -> privateRepoId.equals(m.get("repoId").toString()))).isTrue();
        assertDenied403(exchange(HttpMethod.GET, "/api/v1/mrs/" + privateMrId, outsiderToken, null));
        // 回退端点 blame（经 MR→repo）：ACL 先于路径校验，非法 path 仍 403
        assertDenied403(exchange(HttpMethod.GET, "/api/v1/mrs/" + privateMrId + "/blame?path=x",
                outsiderToken, null));

        // ④ GET /pipelines 跨仓过滤：PRIVATE 仓 run 不返回
        assertThat(itemsOf(exchange(HttpMethod.GET, "/api/v1/pipelines?size=100", outsiderToken, null))
                .stream().noneMatch(p -> privateRepoId.equals(p.get("repoId").toString()))).isTrue();
        assertThat(itemsOf(exchange(HttpMethod.GET, "/api/v1/pipelines?size=100", adminToken, null))
                .stream().anyMatch(p -> privateRepoId.equals(p.get("repoId").toString()))).isTrue();

        // ⑤ GET /commits 逐仓剔除：PRIVATE 仓提交行剔除（不整单 403，工作项维度仍 200）
        ResponseEntity<String> commits = exchange(HttpMethod.GET, "/api/v1/commits?workItemKey=IT-ACL-1",
                outsiderToken, null);
        assertThat(commits.getStatusCode().value()).isEqualTo(200);
        assertThat(body(commits).get("count")).isEqualTo(0);
        ResponseEntity<String> adminCommits = exchange(HttpMethod.GET, "/api/v1/commits?workItemKey=IT-ACL-1",
                adminToken, null);
        assertThat(body(adminCommits).get("count")).isEqualTo(1);
    }

    // ==================== 缓存失效：授权后判定刷新 ====================

    @Test
    @Order(12)
    void step12_grant_refreshes_decision() throws Exception {
        // 授权前：Reporter 建分支 403
        assertDenied403(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                reporterToken, Map.of("name", "feature/it-acl-refresh")));
        // Owner 授 developer → 同一用户同端点复测放行（成员变更即时生效；
        // IT 无 Valkey 时判定恒直查 DB，键版本失效语义由 RepoAclServiceTest 单测覆盖）
        grant(ownerToken, teamoneRepoId, userIdOf("qa1"), "developer");
        assertThat(exchange(HttpMethod.POST, "/api/v1/repos/teamone/branches",
                reporterToken, Map.of("name", "feature/it-acl-refresh")).getStatusCode().value()).isEqualTo(200);
        // me/permissions 同步刷新（role=developer，能力位含 create-branch）
        Map<String, Object> perms = body(exchange(HttpMethod.GET,
                "/api/v1/repos/" + teamoneRepoId + "/me/permissions", reporterToken, null));
        assertThat(perms.get("role")).isEqualTo("developer");
        assertThat(caps(perms)).contains("create-branch");
        // 还原 Reporter 档（后续批次复跑防护）
        grant(ownerToken, teamoneRepoId, userIdOf("qa1"), "reporter");
    }
}
