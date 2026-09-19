package cn.teamone.app.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.teamone.shared.event.W3EventHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件消费者（05 §5.1：teamone-app 内嵌消费组；多实例时消费组自动分摊）。
 *
 * <p>每轮调度两段（消费组 main，实例名 = UUID 短码/环境变量覆写）：</p>
 * <ol>
 *   <li>PEL 排空：XREADGROUP … STREAMS … 0 0 0 0（本实例未 ACK 的重投，立即返回）；</li>
 *   <li>新消息：XREADGROUP GROUP main {instanceId} COUNT 32 BLOCK {block-ms,默认 500ms}，
 *       一次挂全部分片流 teamone:stream:0..N——任一分片到货即醒（数据到达即时返回，
 *       block-ms 只决定无消息时空转周期与停机汇合上限），分片内保序。</li>
 * </ol>
 *
 * <p>逐条（质量审查③ MF-2/MF-3 修订）：先查 processed_event 去重；<b>dispatch 成功后</b>
 * 才 INSERT processed_event（ON CONFLICT DO NOTHING）——claim-before-handle 会让
 * 「Handler 失败→重启→重投命中去重行」的窗口被 ACK 静默丢弃，与 06「异常 PEL 重投」矛盾；
 * Handler 全体幂等（红线 1），at-least-once 无害。{@link DataAccessException}（DB 抖动等
 * 基础设施故障）不计入错误次数、不 ACK、不死信——事件留 PEL 等重投，杜绝「从未成功处理
 * 即被死信」的永久丢失；其余 Handler 异常进程内计数 &gt;5 → 死信 + XACK。
 * Valkey 连接级故障 → 整批退避（下轮调度重试），不崩溃循环。</p>
 *
 * <p>M1 简化（注释备查）：error 计数为进程内内存态——重启清零（PEL 仍在，重投继续）；
 * MF-3 后重投不再被去重行拦截，重试语义闭环。M2 需要时改为 DB 计数。</p>
 */
@Component
@ConditionalOnProperty(name = "teamone.event.enabled", havingValue = "true", matchIfMissing = true)
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    /** 消费组名（06 §3 W3：XREADGROUP GROUP main …） */
    private static final String GROUP = "main";
    /** 每次最多取 32 条 */
    private static final int COUNT = 32;
    /** BLOCK 0：阻塞等待（任务线程专用于消费，专用连接，不占用 lettuce 共享连接） */
    /**
     * XREADGROUP BLOCK 时长（teamone.event.block-ms，默认 500；0=永久阻塞旧语义）。
     * 06 §10 清账项：M1 硬编码 Duration.ZERO 使消费线程僵在阻塞 read 上，优雅停机卡满
     * socket 超时；改有界阻塞后每 block 周期醒一次回调度循环，stop 可即时汇合。
     * IM 延迟诊断批由 5000 降至 500（在线直推已接管实时性，本值只剩兜底语义）。
     */
    private final Duration block;
    /** Handler 异常计数超过 5 → 死信 + XACK */
    private static final int MAX_HANDLER_ERRORS = 5;
    private static final long WARN_INTERVAL_MS = 30_000;

    private static final String STREAM_PREFIX = "teamone:stream:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 幂等去重行（MF-3：dispatch 成功后落行；ON CONFLICT DO NOTHING 兜多实例竞态，重放无害） */
    private static final String CLAIM_SQL =
            "INSERT INTO infra.processed_event(event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING";
    private static final String DEAD_LETTER_SQL =
            "INSERT INTO infra.dead_letter(type,payload,error,retry_count,dead_at) "
            + "VALUES (?,cast(? as jsonb),?,?,now())";
    /** 去重预查（MF-3 前置判定：已成功处理过的事件直接 ACK 丢弃） */
    private static final String ALREADY_SQL =
            "SELECT count(*) FROM infra.processed_event WHERE event_id=?";

    private final StreamOperations<String, String, String> streamOps;
    private final JdbcTemplate jdbc;
    private final TeamoneProps props;
    private final String instanceId;

    /** type → 订阅它的 Handler（types() 为空 = 通配，另存一列） */
    private final Map<String, List<W3EventHandler>> registry;
    private final List<W3EventHandler> wildcardHandlers;
    /** 进程内 Handler 异常计数（eventId → 次数） */
    private final Map<String, Integer> handlerErrors = new ConcurrentHashMap<>();

    private volatile boolean groupsReady = false;
    private volatile long lastWarnAt = 0L;

    public EventConsumer(@Qualifier("eventConsumerRedisConnectionFactory")
                         LettuceConnectionFactory consumerRedisFactory,
                         JdbcTemplate jdbc, TeamoneProps props, List<W3EventHandler> handlers) {
        // 专用连接工厂自建模板（不注册成 bean，避免顶掉自动装配的 stringRedisTemplate）
        this.streamOps = new StringRedisTemplate(consumerRedisFactory).opsForStream();
        this.jdbc = jdbc;
        this.props = props;
        String configured = props.getEvent().getConsumerId();
        this.instanceId = configured == null || configured.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : configured;
        this.block = Duration.ofMillis(Math.max(0, props.getEvent().getBlockMs()));

        Map<String, List<W3EventHandler>> reg = new HashMap<>();
        List<W3EventHandler> wildcard = new ArrayList<>();
        for (W3EventHandler handler : handlers) {
            if (handler.types().isEmpty()) {
                wildcard.add(handler);
            } else {
                for (String type : handler.types()) {
                    reg.computeIfAbsent(type, k -> new ArrayList<>()).add(handler);
                }
            }
        }
        this.registry = reg;
        this.wildcardHandlers = List.copyOf(wildcard);
    }

    @Scheduled(fixedDelay = 300)
    public void poll() {
        if (!groupsReady) {
            try {
                ensureGroups();
            } catch (DataAccessException ex) {
                groupsReady = false;
                warnThrottled("[event-consumer] valkey unavailable, backoff: " + ex.getMessage(), ex);
                return;
            }
        }
        try {
            readPel();   // 先排空本实例 PEL（异常未 ACK 的重投）
            readNew();   // 再阻塞等新消息（BLOCK block-ms，任一分片到货即醒）
        } catch (DataAccessException ex) {
            groupsReady = false; // 连接断开/组丢失 → 下轮重建
            warnThrottled("[event-consumer] read failed, will retry: " + ex.getMessage(), ex);
        }
    }

    /** XGROUP CREATE … MKSTREAM，BUSYGROUP 容忍；ReadOffset=0 兼收建组前的存量 */
    private void ensureGroups() {
        for (String stream : shardStreams()) {
            try {
                streamOps.createGroup(stream, ReadOffset.from("0"), GROUP);
            } catch (DataAccessException ex) {
                if (!isBusyGroup(ex)) {
                    throw ex;
                }
            }
        }
        groupsReady = true;
    }

    /** PEL 排空：STREAMS … 0 0 0 0（无阻塞，空则立即返回） */
    private void readPel() {
        StreamOffset<String>[] offsets = shardStreams().stream()
                .map(StreamOffset::<String>fromStart)
                .toArray(StreamOffset[]::new);
        List<MapRecord<String, String, String>> records =
                streamOps.read(Consumer.from(GROUP, instanceId),
                        StreamReadOptions.empty().count(COUNT), offsets);
        records.forEach(this::handle);
    }

    /** 新消息：STREAMS … > > > >，COUNT 32 BLOCK {block-ms}（lastConsumed = " >"，XREADGROUP 新消息专用） */
    private void readNew() {
        StreamOffset<String>[] offsets = shardStreams().stream()
                .map(s -> StreamOffset.create(s, ReadOffset.lastConsumed()))
                .toArray(StreamOffset[]::new);
        List<MapRecord<String, String, String>> records =
                streamOps.read(Consumer.from(GROUP, instanceId),
                        StreamReadOptions.empty().count(COUNT).block(block), offsets);
        records.forEach(this::handle);
    }

    /**
     * 逐条（MF-2/MF-3 修订）：去重预查 → dispatch → 成功才落 processed_event → XACK。
     * DataAccessException 不计数不 ACK（留 PEL）；其余异常计数 &gt;5 死信+XACK。
     */
    private void handle(MapRecord<String, String, String> record) {
        Map<String, String> body = record.getValue();
        String eventId = body.get("event_id");
        String type = body.get("type");
        try {
            if (alreadyProcessed(eventId)) {
                // 已成功处理过（MF-3：该行只会在 dispatch 成功后存在）→ ACK 丢弃
                log.info("[event-consumer] duplicate skipped by processed_event, type={} eventId={}",
                        type, eventId);
                acknowledge(record);
                return;
            }
            List<W3EventHandler> handlers = handlersFor(type);
            dispatch(type, body.get("payload"), handlers);
            jdbc.update(CLAIM_SQL, UUID.fromString(eventId)); // dispatch 成功后才落去重行
            handlerErrors.remove(eventId);
            acknowledge(record);
            log.info("[event-consumer] handled type={} eventId={} handlers={} by={}",
                    type, eventId, handlers.size(), instanceId);
        } catch (DataAccessException ex) {
            // MF-2：DB 抖动等基础设施故障不计入 handlerErrors——不 ACK 不死信，留 PEL 等重投
            warnThrottled("[event-consumer] infra failure, kept in PEL for redelivery, type="
                    + type + " eventId=" + eventId + ": " + ex.getMessage());
        } catch (Exception ex) {
            int errors = handlerErrors.merge(eventId, 1, Integer::sum);
            if (errors > MAX_HANDLER_ERRORS) {
                jdbc.update(DEAD_LETTER_SQL, type, body.get("payload"),
                        abbreviate(ex.toString()), errors);
                handlerErrors.remove(eventId);
                acknowledge(record);
                log.error("[event-consumer] dead-lettered type={} eventId={} after {} errors",
                        type, eventId, errors);
            } else {
                // 不 XACK：留在 PEL 待重投（readPel 下轮重试）
                log.warn("[event-consumer] handler failed type={} eventId={} ({}/{}): {}",
                        type, eventId, errors, MAX_HANDLER_ERRORS, ex.getMessage());
            }
        }
    }

    /** 去重预查（MF-3：行只在 dispatch 成功后存在，预查命中即可安全 ACK） */
    private boolean alreadyProcessed(String eventId) {
        Integer hits = jdbc.queryForObject(ALREADY_SQL, Integer.class, UUID.fromString(eventId));
        return hits != null && hits > 0;
    }

    private void dispatch(String type, String payloadJson, List<W3EventHandler> handlers) throws Exception {
        if (handlers.isEmpty()) {
            return; // 无订阅者：认领后 ACK 丢弃（目录外事件）
        }
        JsonNode payload = payloadJson == null || payloadJson.isBlank()
                ? MAPPER.createObjectNode() : MAPPER.readTree(payloadJson);
        for (W3EventHandler handler : handlers) {
            handler.handle(payload);
        }
    }

    private List<W3EventHandler> handlersFor(String type) {
        List<W3EventHandler> matched = registry.get(type);
        if (matched == null || matched.isEmpty()) {
            return wildcardHandlers;
        }
        if (wildcardHandlers.isEmpty()) {
            return matched;
        }
        List<W3EventHandler> all = new ArrayList<>(matched);
        all.addAll(wildcardHandlers);
        return all;
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        streamOps.acknowledge(record.getStream(), GROUP, record.getId());
    }

    private List<String> shardStreams() {
        int shards = props.getEvent().getShards();
        List<String> streams = new ArrayList<>(shards);
        for (int i = 0; i < shards; i++) {
            streams.add(STREAM_PREFIX + i);
        }
        return streams;
    }

    private static boolean isBusyGroup(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }

    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_INTERVAL_MS) {
            lastWarnAt = now;
            log.warn(message);
        }
    }

    /** 节流窗口内首条带全栈（便于定位），窗口内其余静默（防降级刷屏） */
    private void warnThrottled(String message, Exception ex) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_INTERVAL_MS) {
            lastWarnAt = now;
            log.warn(message, ex);
        }
    }
}
