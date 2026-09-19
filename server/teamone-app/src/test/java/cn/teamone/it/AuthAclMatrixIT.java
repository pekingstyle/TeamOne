package cn.teamone.it;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0 验收矩阵的自动化回归（部署到 CI：Testcontainers PG17 + Flyway + DevSeeder 全真实链路）。
 *
 * <p>覆盖架构设计 §9 M0 验收项：登录矩阵（无 token/错密码/admin）、ACL 矩阵
 * （dev1 默认拒绝 / dev2 ACL 授予 / dev2 授权精确性）、错误信封形状、
 * 刷新令牌旋转与吊销（W1 存储抽象后语义不变）。</p>
 *
 * <p>执行条件：本地无 Docker 默认跳过（failsafe skipITs=true）；
 * CI 传 {@code -DskipITs=false} 启用。Valkey 相关用例随容器侧就绪后补（W1-10）。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
@Testcontainers
@SpringBootTest(classes = cn.teamone.app.TeamOneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"teamone.event.enabled=false", // IT 只验业务：事件管道（relay/consumer/fanout/sweeper）关闭，专用 IT/环境覆盖（M2）
                "teamone.seed.enabled=true", "teamone.seed.demo=true"}) // 免疫默认值翻转（与其他 IT 同口径，QA 复审）
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthAclMatrixIT {

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<String> res) throws Exception {
        return om.readValue(res.getBody(), Map.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, Object req) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(req, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private String loginAndGet(String username, String password, String field) throws Exception {
        Map<String, Object> body = body(post("/api/v1/auth/login",
                Map.of("username", username, "password", password)));
        return (String) body.get(field);
    }

    @Test
    @Order(1)
    void login_matrix_admin_ok_wrong_password_401_no_token_401() throws Exception {
        // admin → 200
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("username", "admin", "password", "Admin@123"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(body(login)).containsKeys("accessToken", "refreshToken", "user");

        // 错密码 → 401 T1-PLT-4011
        ResponseEntity<String> bad = post("/api/v1/auth/login",
                Map.of("username", "admin", "password", "wrong"));
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
        assertThat(body(bad).get("code")).isEqualTo("T1-PLT-4011");

        // 无 token → 401 T1-PLT-4010 规范信封
        ResponseEntity<String> anon = get("/api/v1/users", null);
        assertThat(anon.getStatusCode().value()).isEqualTo(401);
        assertThat(body(anon).get("code")).isEqualTo("T1-PLT-4010");
    }

    @Test
    @Order(2)
    void acl_matrix_default_deny_grant_precision() throws Exception {
        String dev1 = loginAndGet("dev1", "Dev@12345", "accessToken");
        String dev2 = loginAndGet("dev2", "Dev@12345", "accessToken");

        // dev1（无授权）→ 403 默认拒绝
        ResponseEntity<String> r1 = get("/api/v1/users", dev1);
        assertThat(r1.getStatusCode().value()).isEqualTo(403);
        assertThat(body(r1).get("code")).isEqualTo("T1-PLT-4030");

        // dev2（ACL 授予 user:list）→ 200
        ResponseEntity<String> r2 = get("/api/v1/users", dev2);
        assertThat(r2.getStatusCode().value()).isEqualTo(200);

        // dev2 访问 stats → 403（授权精确性，ACL 不外溢）
        ResponseEntity<String> r3 = get("/api/v1/admin/stats", dev2);
        assertThat(r3.getStatusCode().value()).isEqualTo(403);
        assertThat(body(r3).get("code")).isEqualTo("T1-PLT-4030");

        // admin → 200（平台角色短路）
        String admin = loginAndGet("admin", "Admin@123", "accessToken");
        assertThat(get("/api/v1/admin/stats", admin).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void refresh_rotation_and_logout_revocation() throws Exception {
        // 登录拿到 rt1 与访问令牌（logout 为 §3.3「持有者」接口，需 Bearer）
        Map<String, Object> login = body(post("/api/v1/auth/login",
                Map.of("username", "dev2", "password", "Dev@12345")));
        String rt1 = (String) login.get("refreshToken");
        String access = (String) login.get("accessToken");

        // refresh → rt2（旋转成功）
        ResponseEntity<String> rotated = post("/api/v1/auth/refresh", Map.of("refreshToken", rt1));
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String rt2 = (String) body(rotated).get("refreshToken");
        assertThat(rt2).isNotEqualTo(rt1);

        // 旧令牌复用 → 401 T1-PLT-4012（旋转即吊销）
        ResponseEntity<String> replay = post("/api/v1/auth/refresh", Map.of("refreshToken", rt1));
        assertThat(replay.getStatusCode().value()).isEqualTo(401);
        assertThat(body(replay).get("code")).isEqualTo("T1-PLT-4012");

        // logout rt2（携带访问令牌）→ 再 refresh → 401（吊销生效）
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access);
        ResponseEntity<String> out = rest.exchange("/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", rt2), headers), String.class);
        assertThat(out.getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", rt2))
                .getStatusCode().value()).isEqualTo(401);
    }
}
