package cn.teamone.it;

import cn.teamone.collab.app.ConversationService;
import cn.teamone.collab.app.MessageService;
import cn.teamone.collab.domain.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * IM 未读游标与 @提醒 协议级 IT（B1 批 · R-10，QA 验收矩阵）：
 * PG17 + Valkey 8 全真实链路（Testcontainers，参照 {@link GateFlowIT}/{@link ImFanoutLatencyIT} 基建）。
 *
 * <p>properties 显式钉 {@code teamone.seed.enabled=true}（免疫种子开关默认值翻转，评审结论 1/5）+
 * {@code teamone.event.enabled=false}（关事件管道，只测协议层：HTTP 已读端点与 WS handleRead
 * 同走 ConversationService.advanceRead；扇出帧经 teamone:ws:fanout PUBLISH 观察——与
 * ImFanoutLatencyIT 同口径，WS 节点收到的即该频道帧）。无 Docker 自动跳过。</p>
 *
 * <p>QA 矩阵逐步：</p>
 * <ol>
 *   <li>双用户 DM：dev1 发 2 条 → GET /conversations 该会话 unreadCount=2 → HTTP 已读 →
 *       ack(unread=0)/GET 双证归零 + SQL 游标落库证据（非仅缓存）；</li>
 *   <li>游标只前进：旧 msgId 读 → unread 不变大；越界 id → 400 拒绝且 unread 不回退；</li>
 *   <li>多端一致：HTTP 已读提交后 user:{uid} 收 read 帧（同用户其余端就地清零依据）；</li>
 *   <li>@提醒：mentions 消息 → 被成员 collab.notification(kind=im.mention) 行 + WS notify 帧
 *       （含 conversationId/摘要/mentions 标记）+ 收件箱 HTTP 双证；</li>
 *   <li>unread-summary 与 GET /conversations 求和一致（同口径 SQL）；</li>
 *   <li>时间字段 sanity：新发消息 createdAt 与 now 差 &lt; 5s；mentions 随消息投影透传。</li>
 * </ol>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = cn.teamone.app.TeamOneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teamone.seed.enabled=true",   // 评审必改 1：显式钉种子开关，免疫默认值翻转
                "teamone.seed.demo=true",      // 评审必改 1：演示数据开关同钉（B6 一拆二后补齐）
                "teamone.event.enabled=false", // 评审必改 5：关事件管道，只测协议层（沿 GateFlowIT 先例）
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImUnreadCursorIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", VALKEY::getHost);
        r.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 扇出帧到货上限（L3 直推为 afterCommit 即推；放宽 5s 纯属 CI 容忍） */
    private static final long FRAME_TIMEOUT_MS = 5_000;

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper om;
    @Autowired MessageService messages;
    @Autowired ConversationService conversations;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    /** 扇出帧记录：ts=到货时刻（监听线程写入；多端 read 帧 / notify 帧据此断言） */
    private record Rec(long ts, String ch, String kind, JsonNode payload) {}

    private static final ConcurrentLinkedQueue<Rec> RECS = new ConcurrentLinkedQueue<>();
    private static LettuceConnectionFactory listenerFactory;
    private static RedisMessageListenerContainer listenerContainer;

    private static String adminToken; // A：接收方视角主断言用户
    private static String dev1Token;  // B：发送方视角
    private static UUID adminId;
    private static UUID dev1Id;
    private static UUID convId;
    private static long firstMsgId;
    private static long lastMsgId;

    // ==================== 基础设施 ====================

    @AfterAll
    static void tearDownListener() {
        try {
            if (listenerContainer != null) listenerContainer.stop();
        } catch (Exception ignored) {
        }
        try {
            if (listenerFactory != null) listenerFactory.destroy();
        } catch (Exception ignored) {
        }
    }

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

    /** 本人会话清单中取指定会话行（GET /conversations 全量行，与 unread-summary 同集合） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convRow(String token, UUID id) throws Exception {
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) body(exchange(HttpMethod.GET, "/api/v1/conversations", token, null)).get("items");
        return items.stream().filter(c -> id.toString().equals(String.valueOf(c.get("id")))).findFirst().orElse(null);
    }

    private static void subscribeFanout() {
        listenerFactory = new LettuceConnectionFactory(
                VALKEY.getHost(), VALKEY.getMappedPort(6379));
        listenerFactory.afterPropertiesSet();
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(listenerFactory);
        listenerContainer.addMessageListener((message, pattern) -> {
            try {
                JsonNode env = MAPPER.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
                String ch = env.path("ch").asText("");
                JsonNode frame = env.path("frame");
                String kind = frame.path("payload").path("kind").asText("");
                JsonNode payload = frame.path("payload").path("payload");
                RECS.add(new Rec(System.currentTimeMillis(), ch, kind, payload));
            } catch (Exception ignored) {
                // 坏帧不参与断言
            }
        }, new ChannelTopic("teamone:ws:fanout"));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
    }

    /** 等待满足条件的扇出帧（ts ≥ sinceMs），超时 fail */
    private static Rec awaitFrame(String ch, String kind, long sinceMs, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Rec r : RECS) {
                if (r.ts() >= sinceMs && kind.equals(r.kind()) && ch.equals(r.ch())) {
                    return r;
                }
            }
            Thread.sleep(5);
        }
        fail("fanout frame not in time: ch=" + ch + " kind=" + kind + " within " + timeoutMs + "ms");
        return null;
    }

    private long maxMsgId() {
        Long id = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id),0) FROM collab.message WHERE conversation_id=?", Long.class, convId);
        return id == null ? 0L : id;
    }

    // ==================== 用例 ====================

    /** step0：双用户登录 + DM 会话 + fanout 订阅（先于一切发送） */
    @Test
    @Order(0)
    void step0_setup_login_dm_fanoutSubscription() throws Exception {
        adminToken = login("admin", "Admin@123");
        dev1Token = login("dev1", "Dev@12345");
        adminId = jdbc.queryForObject(
                "SELECT id FROM platform.app_user WHERE username='admin'", UUID.class);
        dev1Id = jdbc.queryForObject(
                "SELECT id FROM platform.app_user WHERE username='dev1'", UUID.class);
        Map<String, Object> dm = body(exchange(HttpMethod.POST, "/api/v1/conversations", adminToken,
                Map.of("type", "dm", "peerId", dev1Id.toString())));
        convId = UUID.fromString(String.valueOf(dm.get("id")));
        subscribeFanout();
        assertThat(convId).isNotNull();
    }

    /**
     * step1（QA 矩阵①）：dev1 发 2 条 → GET /conversations 该会话 unreadCount=2 →
     * HTTP 已读（无游标=读到最新）→ ack(unread=0)/GET 双证归零 + SQL 游标落库证据。
     */
    @Test
    @Order(1)
    void step1_twoMessages_unreadTwo_thenHttpRead_zeroesTriple() throws Exception {
        // dev1 连发 2 条（clientMsgId 幂等键各不相同）
        Message m1 = messages.send(convId, dev1Id, UUID.randomUUID(), "text", "hello-A-1",
                null, null, null);
        Message m2 = messages.send(convId, dev1Id, UUID.randomUUID(), "text", "hello-A-2",
                null, null, null);
        firstMsgId = m1.getId();
        lastMsgId = m2.getId();

        // 未读=2（GET /conversations 读侧真相；发送者本人 dev1 的行恒为 0）
        Map<String, Object> row = convRow(adminToken, convId);
        assertThat(row).as("会话行存在于 admin 清单").isNotNull();
        assertThat(((Number) row.get("unreadCount")).longValue()).isEqualTo(2);

        // HTTP 已读（POST /{id}/read，body 无 lastReadMessageId=读到最新，R-10b 哨兵废除）
        ResponseEntity<String> readRes = exchange(HttpMethod.POST,
                "/api/v1/conversations/" + convId + "/read", adminToken, Map.of());
        assertThat(readRes.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> readAck = body(readRes);
        assertThat(((Number) readAck.get("unread")).longValue()).as("ack 双证：unread 归零").isEqualTo(0);
        assertThat(((Number) readAck.get("lastReadMessageId")).longValue())
                .as("缺省游标=会话 last_message_id").isEqualTo(lastMsgId);

        // GET 双证：清单行 unreadCount=0
        assertThat(((Number) convRow(adminToken, convId).get("unreadCount")).longValue()).isEqualTo(0);
        // 第三证：游标落库（非仅 Valkey 缓存）——SQL 行存在且等于最新消息 id
        Long cursor = jdbc.queryForObject("""
                SELECT last_read_message_id FROM collab.user_conversation_cursor
                WHERE user_id=? AND conversation_id=?
                """, Long.class, adminId, convId);
        assertThat(cursor).as("游标落库证据").isEqualTo(lastMsgId);
    }

    /** step2（QA 矩阵②）：游标只前进——旧 msgId 读不回退、越界 id 拒绝、unread 永不变大 */
    @Test
    @Order(2)
    void step2_cursorOnlyForward_oldAndOutOfBounds() throws Exception {
        // 旧游标：允许 200（GREATEST 幂等），但生效游标不回退 → unread 仍为 0 不变大
        ResponseEntity<String> oldRes = exchange(HttpMethod.POST,
                "/api/v1/conversations/" + convId + "/read", adminToken,
                Map.of("lastReadMessageId", firstMsgId));
        assertThat(oldRes.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> oldAck = body(oldRes);
        assertThat(((Number) oldAck.get("lastReadMessageId")).longValue())
                .as("生效游标保持最新（只前进）").isEqualTo(lastMsgId);
        assertThat(((Number) oldAck.get("unread")).longValue()).isEqualTo(0);

        // 越界：> last_message_id → 400/T1-PLT-4000 拒绝（红线 2：防伪造大游标打穿未读）
        ResponseEntity<String> beyond = exchange(HttpMethod.POST,
                "/api/v1/conversations/" + convId + "/read", adminToken,
                Map.of("lastReadMessageId", lastMsgId + 100_000));
        assertThat(beyond.getStatusCode().value()).isEqualTo(400);
        assertThat(body(beyond).get("code")).isEqualTo("T1-PLT-4000");

        // 负值：400
        ResponseEntity<String> negRes = exchange(HttpMethod.POST,
                "/api/v1/conversations/" + convId + "/read", adminToken,
                Map.of("lastReadMessageId", -1));
        assertThat(negRes.getStatusCode().value()).isEqualTo(400);

        // 拒绝后终态：GET /conversations unread 不变（仍 0，未回退未变大）
        assertThat(((Number) convRow(adminToken, convId).get("unreadCount")).longValue()).isEqualTo(0);
    }

    /**
     * step3（QA 矩阵③）：多端一致——HTTP 已读提交后 user:{uid} 扇出 read 帧
     * （同用户双端：一端已读，另一端凭帧就地清零；含 conversationId/unread）。
     */
    @Test
    @Order(3)
    void step3_multiDevice_readFrameFanout() throws Exception {
        // dev1 再发 1 条 → admin 未读=1；admin HTTP 已读 → user:{adminId} 必到 read 帧
        messages.send(convId, dev1Id, UUID.randomUUID(), "text", "hello-A-3", null, null, null);
        assertThat(((Number) convRow(adminToken, convId).get("unreadCount")).longValue()).isEqualTo(1);

        long since = System.currentTimeMillis();
        ResponseEntity<String> readRes = exchange(HttpMethod.POST,
                "/api/v1/conversations/" + convId + "/read", adminToken, Map.of());
        assertThat(readRes.getStatusCode().value()).isEqualTo(200);

        Rec frame = awaitFrame("user:" + adminId, "read", since, FRAME_TIMEOUT_MS);
        assertThat(frame.payload().path("conversationId").asText()).isEqualTo(convId.toString());
        assertThat(frame.payload().path("unread").asLong()).as("read 帧携权威 unread=0").isEqualTo(0);
    }

    /**
     * step4（QA 矩阵④）：@提醒——含 mentions 的消息 → 被成员 notification 行（im.mention）
     * + user:{uid} notify 帧（conversationId/摘要/mentions 标记）+ 收件箱 HTTP 双证。
     */
    @Test
    @Order(4)
    void step4_mention_notificationRow_and_wsNotifyFrame() throws Exception {
        long since = System.currentTimeMillis();
        messages.send(convId, adminId, UUID.randomUUID(), "text", "@dev1 请跟进这条线索",
                null, List.of(dev1Id), null, null);

        // ① WS notify 帧（L3 直推；事务提交后才发——先落库后推送）
        Rec notify = awaitFrame("user:" + dev1Id, "notify", since, FRAME_TIMEOUT_MS);
        assertThat(notify.payload().path("conversationId").asText()).isEqualTo(convId.toString());
        assertThat(notify.payload().path("kind").asText()).isEqualTo("im.mention");
        assertThat(notify.payload().path("senderId").asText()).isEqualTo(adminId.toString());
        assertThat(notify.payload().path("preview").asText()).contains("@dev1");
        assertThat(notify.payload().path("mentions").toString()).contains(dev1Id.toString());

        // ② notification 落库行（事务内写；非仅缓存/帧）
        Integer rows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM collab.notification
                WHERE user_id=? AND kind='im.mention'
                  AND payload->>'conversationId'=?
                """, Integer.class, dev1Id, convId.toString());
        assertThat(rows).as("被@成员 notification 行").isGreaterThanOrEqualTo(1);

        // ③ 收件箱 HTTP 双证（NotificationController 同形状：id/kind/payload/readAt/createdAt）
        Map<String, Object> inbox = body(exchange(HttpMethod.GET,
                "/api/v1/notifications?unread=true&limit=50", dev1Token, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) inbox.get("items");
        assertThat(items.stream().anyMatch(n -> "im.mention".equals(n.get("kind"))
                && convId.toString().equals(
                        String.valueOf(((Map<String, Object>) n.get("payload")).get("conversationId")))))
                .as("通知收件箱出现 im.mention 条目").isTrue();
    }

    /** step5（QA 矩阵⑤）：unread-summary 与 GET /conversations 求和一致（同口径 SUM） */
    @Test
    @Order(5)
    void step5_unreadSummary_matchesConversationsSum() throws Exception {
        // 造一个确定未读态：dev1 发 1 条 → admin 未读 +1（sum ≥ 1，断言非平凡）
        messages.send(convId, dev1Id, UUID.randomUUID(), "text", "hello-A-4", null, null, null);

        long summary = ((Number) body(exchange(HttpMethod.GET,
                "/api/v1/conversations/unread-summary", adminToken, null)).get("total")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(
                exchange(HttpMethod.GET, "/api/v1/conversations", adminToken, null)).get("items");
        long sum = items.stream()
                .mapToLong(c -> ((Number) c.getOrDefault("unreadCount", 0)).longValue())
                .sum();

        assertThat(sum).as("前置：确有未读（非平凡一致）").isGreaterThanOrEqualTo(1);
        assertThat(summary).as("unread-summary = GET /conversations 求和").isEqualTo(sum);
    }

    /** step6（QA 矩阵⑥）：时间字段 sanity——新发消息 createdAt 与 now 差 < 5s；mentions 随投影透传 */
    @Test
    @Order(6)
    void step6_createdAtSanity_and_mentionsProjected() throws Exception {
        Message sent = messages.send(convId, adminId, UUID.randomUUID(), "text",
                "time-sanity-" + Instant.now(), null, null, null);

        Map<String, Object> page = body(exchange(HttpMethod.GET,
                "/api/v1/conversations/" + convId + "/messages?before=latest&limit=1", adminToken, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).hasSize(1);
        Map<String, Object> newest = items.get(0);
        assertThat(((Number) newest.get("msgId")).longValue()).isEqualTo(sent.getId());

        Instant createdAt = Instant.parse(String.valueOf(newest.get("createdAt")));
        long deltaSeconds = Math.abs(createdAt.until(Instant.now(), ChronoUnit.SECONDS));
        assertThat(deltaSeconds).as("createdAt 与 now 差 <5s（防 epoch 秒/毫秒错位）").isLessThan(5);

        // mentions 字段随消息投影透传（无提醒=空数组；@提醒消息=被成员 id 串）
        assertThat(newest).containsKey("mentions");
        Message mentioned = messages.send(convId, adminId, UUID.randomUUID(), "text",
                "@dev1 再提一次", null, List.of(dev1Id), null, null);
        assertThat(new ArrayList<>(mentioned.getMentions())).containsExactly(dev1Id);
    }
}
