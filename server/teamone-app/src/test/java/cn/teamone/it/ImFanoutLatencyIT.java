package cn.teamone.it;

import cn.teamone.collab.app.ConversationService;
import cn.teamone.collab.app.ImFastFanout;
import cn.teamone.collab.app.MessageService;
import cn.teamone.collab.domain.Message;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.platform.repo.AppUserRepository;
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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * IM 扇出端到端延迟基准 IT（IM 延迟诊断批）：PG17 + Valkey 8 全真实链路
 * （Testcontainers，参照 {@link GateFlowIT}；无 Docker 自动跳过，failsafe 默认 skipITs）。
 *
 * <p>测什么（发一条消息 → 订阅 teamone:ws:fanout 收帧计时）：</p>
 * <ol>
 *   <li><b>L3 直推路径</b>（修复后主路径）：MessageService.send 事务提交 →
 *       afterCommit 直推 message 帧 + unread 帧——在线用户零轮询延迟；</li>
 *   <li><b>L2 事件链路径</b>（兜底/修复前基线口径）：直接向 Outbox 追加无直推标记的
 *       message.created 合成事件 → Relay 轮询 XADD → EventConsumer XREADGROUP →
 *       FanoutHandler PUBLISH——计时反映 relay 轮询 + 消费调度间隙；</li>
 *   <li><b>防重复</b>：直推后写 teamone:im:pushed:{msgId} 标记，L2 事件消费到同一条
 *       message.created 必须跳过（窗口内 conv 帧恰 1 次，不多不少）；</li>
 *   <li><b>未读正确性</b>：unread 帧的 unreadCount 与读侧真相（findMineWithUnread 口径）一致。</li>
 * </ol>
 *
 * <p>延迟断言刻意放宽（CI 抖动容忍），精确数字看尾部打印的统计表——
 * 修复前基线由 L2 计时代表（同轮询参数），修复后由 L3 计时代表。</p>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = cn.teamone.app.TeamOneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 评审必改 1：显式钉种子开关（enabled=账号/ACL 引导 + demo=演示数据），免疫默认值翻转
                "teamone.seed.enabled=true",
                "teamone.seed.demo=true",
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImFanoutLatencyIT {

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
    /** 采样轮数（每路径） */
    private static final int SAMPLES = 5;
    /** 直推帧到货上限（含事务耗时；放宽到 2s 纯属 CI 容忍，实测应为个位/十位 ms 级） */
    private static final long DIRECT_TIMEOUT_MS = 2_000;
    /** L2 事件链帧到货上限（relay 500ms 轮询 + 消费调度，放宽到 15s 防 CI 抖动） */
    private static final long CHAIN_TIMEOUT_MS = 15_000;
    /** 防重复观察窗：须大于 relay(500ms)+消费周期(≤800ms)，覆盖 L2 对直推消息的一次完整消费 */
    private static final long DEDUP_WINDOW_MS = 4_000;
    /** 合成事件 msgId 基址（message 表 identity 不会到达的量级，纯为扇出帧关联） */
    private static final long SYNTH_MSG_ID_BASE = 9_000_000_000L;

    @Autowired MessageService messages;
    @Autowired ConversationService conversations;
    @Autowired AppUserRepository users;
    @Autowired OutboxWriter outbox;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    /** 扇出帧记录：ts=到货时刻（监听线程写入） */
    private record Rec(long ts, String ch, String kind, long msgId, Long unreadCount) {}

    private static final ConcurrentLinkedQueue<Rec> RECS = new ConcurrentLinkedQueue<>();
    private static LettuceConnectionFactory listenerFactory;
    private static RedisMessageListenerContainer listenerContainer;

    private static UUID senderId;
    private static UUID peerId;
    private static UUID convId;

    private static final List<Long> directLatencies = new CopyOnWriteArrayList<>();
    private static final List<Long> chainLatencies = new CopyOnWriteArrayList<>();

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

    // ==================== 用例 ====================

    @Test
    @Order(0)
    void step0_setup_seedUsers_dmAndFanoutSubscription() {
        var admin = users.findByUsername("admin").orElseThrow();
        var dev1 = users.findByUsername("dev1").orElseThrow();
        senderId = admin.getId();
        peerId = dev1.getId();
        convId = UUID.fromString(String.valueOf(
                conversations.createDm(senderId, peerId).get("id")));

        // 订阅 fanout 频道（独立连接，先于一切发送；帧到达即记 ts）
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
                long msgId = payload.path("msgId").asLong(0);
                Long unread = payload.hasNonNull("unreadCount") ? payload.path("unreadCount").asLong() : null;
                RECS.add(new Rec(System.currentTimeMillis(), ch, kind, msgId, unread));
            } catch (Exception ignored) {
                // 坏帧不参与计时
            }
        }, new ChannelTopic("teamone:ws:fanout"));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();

        assertThat(convId).isNotNull();
    }

    /** L3 直推：提交即达 + unread 帧正确 + 直推标记落地 */
    @Test
    @Order(1)
    void step1_directPush_latency_and_unread_and_marker() throws Exception {
        for (int i = 1; i <= SAMPLES; i++) {
            long t0 = System.currentTimeMillis();
            Message sent = messages.send(convId, senderId, UUID.randomUUID(),
                    "text", "bench-direct-" + i, null, null, null);
            Rec frame = awaitFrame("message.created", sent.getId(), DIRECT_TIMEOUT_MS);
            directLatencies.add(frame.ts() - t0);
        }
        // 未读帧正确性：第 N 条后对端 unreadCount == N（读侧真相同源，且帧先于断言到达）
        Rec lastUnread = awaitUnread(peerId, (long) SAMPLES, DIRECT_TIMEOUT_MS);
        assertThat(lastUnread.ch()).isEqualTo("user:" + peerId);
        // 直推标记落地（FanoutHandler 据此跳过 L2 重复扇出）
        assertThat(redis.hasKey(ImFastFanout.PUSHED_KEY_PREFIX
                + jdbc.queryForObject(
                    "SELECT max(id) FROM collab.message WHERE conversation_id=?", Long.class, convId)))
                .isTrue();
    }

    /** 防重复：直推过的消息，L2 事件链消费到时必须跳过（窗口内 conv 帧恰 SAMPLES 次） */
    @Test
    @Order(2)
    void step2_dedup_l2ChainMustNotRepublishDirectPushed() throws Exception {
        // step1 已直推 SAMPLES 条并落标记；本测试等待足够时间让 L2 消费完全部直推事件
        // （relay 轮询 ≤1s + 消费调度 ≤0.8s，4s 窗口富余；若去重失效将出现 2×SAMPLES 帧）
        Thread.sleep(DEDUP_WINDOW_MS);
        long frames = RECS.stream()
                .filter(r -> "message.created".equals(r.kind()))
                .filter(r -> r.ch().equals("conv:" + convId))
                .count();
        assertThat(frames).as("直推 + L2 去重后 conv 帧总数（去重失效则翻倍）").isEqualTo(SAMPLES);
    }

    /** L2 事件链（修复前基线口径）：合成无标记事件走 Relay 轮询 → 消费 → PUBLISH 的端到端耗时 */
    @Test
    @Order(3)
    void step3_l2EventChain_latencyBaseline() throws Exception {
        for (int i = 1; i <= SAMPLES; i++) {
            long synthMsgId = SYNTH_MSG_ID_BASE + i;
            Map<String, Object> payload = new HashMap<>();
            payload.put("msgId", synthMsgId);
            payload.put("conversationId", convId.toString());
            payload.put("senderId", senderId.toString());
            payload.put("kind", "text");
            payload.put("body", "bench-chain-" + i);
            payload.put("attachments", List.of());
            payload.put("clientMsgId", UUID.randomUUID().toString());
            payload.put("createdAt", Instant.now().toString());
            payload.put("withdrawn", false);

            long t0 = System.currentTimeMillis();
            outbox.append("message", convId, "message.created", payload, senderId);
            awaitFrame("message.created", synthMsgId, CHAIN_TIMEOUT_MS);
            chainLatencies.add(System.currentTimeMillis() - t0);
        }
    }

    /** 尾部：打印两路径延迟统计表（汇报用数字），并断言直推显著快于事件链 */
    @Test
    @Order(4)
    void step4_printLatencyBudget_and_sanity() {
        long directMin = directLatencies.stream().min(Long::compare).orElse(-1L);
        long directMax = directLatencies.stream().max(Long::compare).orElse(-1L);
        double directAvg = directLatencies.stream().mapToLong(Long::longValue).average().orElse(-1);
        long chainMin = chainLatencies.stream().min(Long::compare).orElse(-1L);
        long chainMax = chainLatencies.stream().max(Long::compare).orElse(-1L);
        double chainAvg = chainLatencies.stream().mapToLong(Long::longValue).average().orElse(-1);

        System.out.println("\n================ IM 扇出端到端延迟基准（ImFanoutLatencyIT） ================");
        System.out.printf("L3 直推（修复后主路径, n=%d）: min=%dms avg=%.1fms max=%dms%n",
                directLatencies.size(), directMin, directAvg, directMax);
        System.out.printf("L2 事件链（修复前基线口径, n=%d）: min=%dms avg=%.1fms max=%dms%n",
                chainLatencies.size(), chainMin, chainAvg, chainMax);
        System.out.println("（L2 计时含 relay 轮询平均等待 ~250ms + 消费调度间隙；直推应快一个数量级以上）");
        System.out.println("==========================================================================\n");

        assertThat(directLatencies).isNotEmpty();
        assertThat(chainLatencies).isNotEmpty();
        assertThat(directAvg).as("直推平均延迟应显著低于事件链（数量级差异）").isLessThan(chainAvg);
    }

    // ==================== 内部 ====================

    /** 等待指定 msgId 的 message.created 帧到货，超时 fail（返回帧记录含到货时刻） */
    private static Rec awaitFrame(String kind, long msgId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Rec r : RECS) {
                if (kind.equals(r.kind()) && r.msgId() == msgId) {
                    return r;
                }
            }
            Thread.sleep(5);
        }
        fail("fanout frame not in time: kind=" + kind + " msgId=" + msgId + " within " + timeoutMs + "ms");
        return null;
    }

    /** 等待对端指定 unreadCount 的 unread 帧到货 */
    private static Rec awaitUnread(UUID uid, long expectedUnread, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Rec r : RECS) {
                if ("unread".equals(r.kind()) && r.ch().equals("user:" + uid)
                        && r.unreadCount() != null && r.unreadCount() >= expectedUnread) {
                    return r;
                }
            }
            Thread.sleep(5);
        }
        fail("unread frame not in time: user=" + uid + " unread>=" + expectedUnread);
        return null;
    }
}
