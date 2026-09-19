package cn.teamone.it;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 门禁 L1 全流程集成回归（Testcontainers PG17 + Flyway V4+V17 + DevSeeder/ProductSeeder 全真实链路）。
 *
 * <p>动线：空库迁移 → 种子（v2.4.0 blocked / D-88 致命修复中 / D-87 一般新建 /
 * T-103 todo、T-104/T-106 in_progress、T-105 done 均挂 v2.4.0）→ 建 D-89
 * （Idempotency-Key 重放同 id）→ blocked_defect_keys=[D-88,D-89] → publish 4230+清单 →
 * D-88/D-89 流转至回归通过 → blocked=false/code_freeze → publish 4231（R-9 未完结工作项门禁：
 * T-103/T-104/T-106/D-87 明细）→ 种子任务/一般缺陷流转至完结集 → D-88 已关闭 → publish released →
 * 重复 publish 拒绝 → R-9 联动：登记部署 + 流水线/部署清单闭环 → requirement 会签流
 * submit(2 评审人)→双 approve→accepted→in_dev→deliver→accept。</p>
 *
 * <p>门禁数学注记：L1 口径为「致命/严重 且 状态 ∉ (已关闭,回归通过)」，故 D-89（致命）
 * 也须流转出 open 集合，blocked 才能清零（与 V-2 矩阵的检查点一致）；R-9 硬门禁（4231）
 * 口径为「release_id 挂版本 且 状态 ∉ 类型完结集（WorkItem.doneStatusesOf，与 insight isDone
 * 同源）」——种子任务 T-103/T-104/T-106 与一般缺陷 D-87 在 L1 通过后仍须先流转完结才可发布。</p>
 *
 * <p>执行条件：无 Docker 自动跳过（{@code @Testcontainers(disabledWithoutDocker = true)}）；
 * failsafe 默认 skipITs=true，CI 传 -DskipITs=false 启用。</p>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
// B-N1 修复：登录令牌用 static 字段跨 @Test 保持（PER_METHOD 下实例字段每个方法都会置 null）。
// 不采用 @TestInstance(PER_CLASS)：PER_CLASS 会把 Spring 实例化提前到 beforeAll 阶段，
// 先于 Testcontainers 扩展启动容器 → "Mapped port can only be obtained after the container is started"。
@SpringBootTest(classes = cn.teamone.app.TeamOneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 评审必改 1：显式钉种子开关（enabled=账号/ACL 引导 + demo=演示数据，GateFlowIT 依赖演示
                // 数据：v2.4.0 blocked 态与 D-88/D-87 门禁投影），免疫默认值翻转
                "teamone.seed.enabled=true",
                "teamone.seed.demo=true",
                "teamone.event.enabled=false", // IT 只验业务与门禁：事件管道关闭（V-1 挂死修复，总监裁决）
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GateFlowIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper om;

    private static String adminToken;
    private static String dev1Token;
    private static String dev2Token;

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<String> res) throws Exception {
        return om.readValue(res.getBody(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listBody(ResponseEntity<String> res) throws Exception {
        return (List<Map<String, Object>>) om.readValue(res.getBody(), List.class);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> releaseV240() throws Exception {
        return listBody(exchange(HttpMethod.GET, "/api/v1/releases", adminToken, null))
                .stream().filter(r -> "v2.4.0".equals(r.get("key"))).findFirst().orElseThrow();
    }

    // ==================== 用例 ====================

    @Test
    @Order(1)
    void step1_login_and_seeded_release_blocked() throws Exception {
        adminToken = login("admin", "Admin@123");
        dev1Token = login("dev1", "Dev@12345");
        dev2Token = login("dev2", "Dev@12345");

        Map<String, Object> release = releaseV240();
        assertThat(release.get("status")).isEqualTo("blocked");
        assertThat(release.get("blocked")).isEqualTo(true);
        assertThat((List<Object>) release.get("blockedDefectKeys")).containsExactly("D-88");
    }

    @Test
    @Order(2)
    void step2_seeded_defects_visible() throws Exception {
        List<Map<String, Object>> items = listDefects();
        List<Object> keys = items.stream().map(w -> w.get("key")).collect(Collectors.toList());
        assertThat(keys).contains("D-88", "D-87");
        Map<String, Object> d87 = items.stream()
                .filter(w -> "D-87".equals(w.get("key"))).findFirst().orElseThrow();
        assertThat(d87.get("severity")).isEqualTo("一般");
        assertThat(d87.get("status")).isEqualTo("新建");
        assertThat(d87.get("blockedReleaseId")).isNull();
    }

    @Test
    @Order(3)
    void step3_create_D89_with_idempotent_replay() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminToken);
        headers.set("Idempotency-Key", "v2-a");
        Map<String, Object> req = Map.of("type", "defect", "title", "门禁演示新缺陷 D-89",
                "severity", "致命", "priority", "P0", "blockedReleaseId", "v2.4.0");

        ResponseEntity<String> first = rest.postForEntity("/api/v1/work-items",
                new HttpEntity<>(req, headers), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> created = body(first);
        assertThat(created.get("key")).isEqualTo("D-89");

        // 同 Key 重放 → 同一 id（幂等快照回放）
        ResponseEntity<String> replay = rest.postForEntity("/api/v1/work-items",
                new HttpEntity<>(req, headers), String.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(body(replay).get("id")).isEqualTo(created.get("id"));

        // release 投影更新：[D-88, D-89]
        assertThat((List<Object>) releaseV240().get("blockedDefectKeys")).containsExactly("D-88", "D-89");
    }

    @Test
    @Order(4)
    void step4_publish_blocked_4230_and_dev1_forbidden() throws Exception {
        ResponseEntity<String> blocked = exchange(HttpMethod.POST,
                "/api/v1/releases/v2.4.0/publish", adminToken, null);
        assertThat(blocked.getStatusCode().value()).isEqualTo(422);
        Map<String, Object> err = body(blocked);
        assertThat(err.get("code")).isEqualTo("T1-PRD-4230");
        String details = String.valueOf(err.get("details"));
        assertThat(details).contains("D-88").contains("D-89");

        // dev1（无 product:edit 授权）→ 403 四步链默认拒绝
        ResponseEntity<String> forbidden = exchange(HttpMethod.POST,
                "/api/v1/releases/v2.4.0/publish", dev1Token, null);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(body(forbidden).get("code")).isEqualTo("T1-PLT-4030");
    }

    @Test
    @Order(5)
    void step5_defect_flow_clears_gate() throws Exception {
        // D-88: 修复中 → 已修复（非法直跳已关闭 4201）→ 回归通过
        transition("D-88", "已修复", 200, null);
        transition("D-88", "已关闭", 422, "T1-PRD-4201");
        transition("D-88", "回归通过", 200, null);
        // 门禁数学：D-89（致命·新建）仍在 open 集合，blocked 保持 true
        Map<String, Object> stillBlocked = releaseV240();
        assertThat(stillBlocked.get("blocked")).isEqualTo(true);

        // D-89: 新建 → 修复中 → 已修复 → 回归通过
        transition("D-89", "修复中", 200, null);
        transition("D-89", "已修复", 200, null);
        transition("D-89", "回归通过", 200, null);

        Map<String, Object> release = releaseV240();
        assertThat(release.get("blocked")).isEqualTo(false);
        assertThat(release.get("status")).isEqualTo("code_freeze");
        assertThat((List<Object>) release.get("blockedDefectIds")).isEmpty();
    }

    @Test
    @Order(6)
    void step6_unfinished_gate_4231_then_close_and_publish_released() throws Exception {
        // ---- R-9 正面：L1 门禁已清（4230 不再触发）但种子在制工作项仍挂 v2.4.0 → publish 422/T1-PRD-4231 ----
        ResponseEntity<String> unfinished = exchange(HttpMethod.POST,
                "/api/v1/releases/v2.4.0/publish", adminToken, null);
        assertThat(unfinished.getStatusCode().value()).isEqualTo(422);
        Map<String, Object> gateErr = body(unfinished);
        assertThat(gateErr.get("code")).isEqualTo("T1-PRD-4231");
        String details = String.valueOf(gateErr.get("details"));
        // 明细按类型分组：任务×3（T-103 todo、T-104/T-106 in_progress）、缺陷×1（D-87 一般·新建）
        assertThat(details).contains("T-103").contains("T-104").contains("T-106").contains("D-87");
        assertThat(details).contains("任务×3").contains("缺陷×1");

        // ---- 反面收敛：种子任务沿状态机流转至 done（S-1 在制任务清零） ----
        // T-103: todo → in_progress → done
        transition("T-103", "in_progress", 200, null);
        transition("T-103", "done", 200, null);
        // T-104 / T-106: in_progress → done
        transition("T-104", "done", 200, null);
        transition("T-106", "done", 200, null);
        // D-87（一般缺陷挂 release_id）：新建 → 修复中 → 已修复 → 回归通过（完结集）
        transition("D-87", "修复中", 200, null);
        transition("D-87", "已修复", 200, null);
        transition("D-87", "回归通过", 200, null);

        // ---- D-88 收尾关闭 + 发布（原 W2 动线） ----
        transition("D-88", "已关闭", 200, null);

        ResponseEntity<String> ok = exchange(HttpMethod.POST,
                "/api/v1/releases/v2.4.0/publish", adminToken, null);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> released = body(ok);
        assertThat(released.get("status")).isEqualTo("released");
        assertThat(released.get("releasedAt")).isNotNull();

        // 重复 publish → 状态机拒绝（非 2xx，不许重复事件）
        ResponseEntity<String> again = exchange(HttpMethod.POST,
                "/api/v1/releases/v2.4.0/publish", adminToken, null);
        assertThat(again.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(body(again).get("code")).isEqualTo("T1-PRD-4201");
    }

    @Test
    @Order(7)
    void step7_release_pipeline_deployment_link_closed_loop() throws Exception {
        String releaseId = String.valueOf(releaseV240().get("id"));

        // 流水线区：发布联动触发属 M4/M5，本批该版本无关联运行 → 空清单（前端显式空态契约）
        Map<String, Object> pipelines = body(exchange(HttpMethod.GET,
                "/api/v1/releases/" + releaseId + "/pipelines", adminToken, null));
        assertThat((List<Object>) pipelines.get("items")).isEmpty();

        // 登记部署（platform:manage，admin=OWNER 短路通过）→ 审计留痕 + 清单回显
        Map<String, Object> registered = body(exchange(HttpMethod.POST,
                "/api/v1/releases/" + releaseId + "/deployments", adminToken,
                Map.of("env", "dev", "artifactVersion", "2.4.0-rc.1", "note", "B2 登记闭环演示")));
        assertThat(registered.get("env")).isEqualTo("dev");
        assertThat(registered.get("status")).isEqualTo("success");
        assertThat(registered.get("artifactVersion")).isEqualTo("2.4.0-rc.1");

        // env 白名单校验：非法值 400
        ResponseEntity<String> badEnv = exchange(HttpMethod.POST,
                "/api/v1/releases/" + releaseId + "/deployments", adminToken,
                Map.of("env", "prod-x"));
        assertThat(badEnv.getStatusCode().value()).isEqualTo(400);

        // 部署区：登记后可见（deployed_at 倒序）
        Map<String, Object> deployments = body(exchange(HttpMethod.GET,
                "/api/v1/releases/" + releaseId + "/deployments", adminToken, null));
        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) deployments.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("env")).isEqualTo("dev");
        assertThat(items.get(0).get("deployedAt")).isNotNull();
    }

    @Test
    @Order(8)
    void step8_requirement_signoff_flow_to_closed() throws Exception {
        // 建 REQ（draft）
        Map<String, Object> created = body(exchange(HttpMethod.POST, "/api/v1/work-items",
                adminToken, Map.of("type", "requirement", "title", "W2 门禁演示需求",
                        "priority", "P1", "productId", "p1")));
        assertThat(created.get("status")).isEqualTo("draft");

        // submit：2 评审人（dev1/dev2），round=1
        Map<String, Object> submitted = body(exchange(HttpMethod.POST,
                "/api/v1/work-items/REQ-1/submit", adminToken,
                Map.of("reviewerIds", List.of("dev1", "dev2"))));
        assertThat(submitted.get("status")).isEqualTo("pending_review");

        // 会签：两 approve → accepted
        body(exchange(HttpMethod.POST, "/api/v1/work-items/REQ-1/review", dev1Token,
                Map.of("result", "approved", "comment", "lgtm")));
        Map<String, Object> accepted = body(exchange(HttpMethod.POST,
                "/api/v1/work-items/REQ-1/review", dev2Token,
                Map.of("result", "approved", "comment", "ok")));
        assertThat(accepted.get("status")).isEqualTo("accepted");

        // in_dev 手动（转移表 accepted→in_dev）→ deliver → accept
        Map<String, Object> inDev = body(exchange(HttpMethod.POST,
                "/api/v1/work-items/REQ-1/transition", adminToken, Map.of("to", "in_dev")));
        assertThat(inDev.get("status")).isEqualTo("in_dev");
        Map<String, Object> delivered = body(exchange(HttpMethod.POST,
                "/api/v1/work-items/REQ-1/deliver", adminToken, null));
        assertThat(delivered.get("status")).isEqualTo("delivered");
        Map<String, Object> closed = body(exchange(HttpMethod.POST,
                "/api/v1/work-items/REQ-1/accept", adminToken, null));
        assertThat(closed.get("status")).isEqualTo("closed");

        // 评审行留痕：1 轮 2 行，均 approved
        List<Map<String, Object>> reviews = listBody(exchange(HttpMethod.GET,
                "/api/v1/work-items/REQ-1/reviews", adminToken, null));
        assertThat(reviews).hasSize(2);
        assertThat(reviews.stream().map(r -> r.get("result")))
                .containsOnly("approved");
    }

    // ==================== 内部 ====================

    private void transition(String key, String to, int expectedStatus, String expectedCode) throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.POST,
                "/api/v1/work-items/" + key + "/transition", adminToken, Map.of("to", to));
        assertThat(res.getStatusCode().value()).isEqualTo(expectedStatus);
        if (expectedCode != null) {
            assertThat(body(res).get("code")).isEqualTo(expectedCode);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listDefects() throws Exception {
        Map<String, Object> page = body(exchange(HttpMethod.GET,
                "/api/v1/work-items?type=defect", adminToken, null));
        return (List<Map<String, Object>>) (List<?>) page.get("items");
    }
}
