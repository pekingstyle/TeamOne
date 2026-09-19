package cn.teamone.app.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.lettuce.core.RedisCommandTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox 投递器（05 §5.1 原样）：500ms 扫描 infra.outbox_event 未发布行，
 * native SELECT ... FOR UPDATE SKIP LOCKED 抢占 → 按聚合分片 XADD teamone:stream:{shard}
 * （同聚合同分片 ⇒ Stream 内保序）→ 成功 UPDATE published_at=now()。
 *
 * <p>轮询间隔经 teamone.outbox.relay-interval-ms 可配（默认 500 不变；IM 延迟诊断批
 * 参数化——在线直推 teamone.im.fast-fanout 不经过本轮询，间隔只影响兜底链路投递等待）。</p>
 *
 * <p>失败语义（06 §3 W3）：单条失败 retry_count+1 继续下一条；retry_count&gt;16 →
 * 移入 infra.dead_letter 并删 outbox 行。Valkey 不可用（连接级故障）→ 整批退避：
 * 只记 WARN 不动 retry_count，行留在 outbox 待下次调度——不得崩溃循环、不得把
 * 停机窗口刷成死信（至少一次投递，消费端 processed_event 去重兜住重复 XADD）。</p>
 */
@Component
@ConditionalOnProperty(name = "teamone.event.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 单批 200（06 §3 W3） */
    private static final int BATCH = 200;
    /** retry_count 超过此值入死信（06 §3 W3：retry>16） */
    private static final int MAX_RETRY = 16;
    private static final long WARN_INTERVAL_MS = 30_000;

    /** native + SKIP LOCKED（红线：必须 native），FOR UPDATE 锁行至事务提交 */
    private static final String SELECT_UNPUBLISHED = """
            SELECT id, aggregate_type, aggregate_id, type, payload::text AS payload,
                   occurred_at, retry_count
              FROM infra.outbox_event
             WHERE published_at IS NULL
             ORDER BY occurred_at
             LIMIT %d
             FOR UPDATE SKIP LOCKED
            """.formatted(BATCH);

    private static final String MARK_PUBLISHED =
            "UPDATE infra.outbox_event SET published_at=now() WHERE id=?";
    private static final String BUMP_RETRY =
            "UPDATE infra.outbox_event SET retry_count=retry_count+1 WHERE id=?";
    private static final String DEAD_LETTER =
            "INSERT INTO infra.dead_letter(type,payload,error,retry_count,dead_at) "
            + "VALUES (?,cast(? as jsonb),?,?,now())";
    private static final String DELETE_OUTBOX =
            "DELETE FROM infra.outbox_event WHERE id=?";

    private static final String STREAM_PREFIX = "teamone:stream:";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis; // 自动装配的 stringRedisTemplate（非阻塞 XADD）
    private final TeamoneProps props;
    private final TransactionTemplate tx;

    private volatile long lastWarnAt = 0L;

    public OutboxRelay(JdbcTemplate jdbc,
                       @Qualifier("stringRedisTemplate") StringRedisTemplate redis,
                       TeamoneProps props, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${teamone.outbox.relay-interval-ms:500}")
    public void relay() {
        try {
            tx.executeWithoutResult(status -> relayBatch());
        } catch (DataAccessException ex) {
            warnThrottled("[outbox-relay] batch aborted, will retry: " + ex.getMessage());
        }
    }

    /** 单批：SELECT FOR UPDATE SKIP LOCKED → 逐条 XADD → published_at / retry+1 / 死信 */
    private void relayBatch() {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT_UNPUBLISHED);
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            try {
                xadd(row);
            } catch (DataAccessException ex) {
                if (isConnectionFailure(ex)) {
                    // Valkey 降级：整批退避（不 bump retry，行留下次重试），锁随事务提交释放
                    warnThrottled("[outbox-relay] valkey unavailable, backoff ("
                            + rows.size() + " pending rows kept): " + ex.getMessage());
                    return;
                }
                handleRelayFailure(row, id, ex);
                continue;
            }
            jdbc.update(MARK_PUBLISHED, id); // DB 侧失败 → 抛出 → 外层整批回滚（重复 XADD 由消费端去重）
        }
    }

    /** 分片 = floorMod(aggregate_id.hashCode(), shards)——同聚合同分片保序（MIN_VALUE 边界安全） */
    private void xadd(Map<String, Object> row) {
        UUID aggregateId = (UUID) row.get("aggregate_id");
        int shard = Math.floorMod(aggregateId.hashCode(), props.getEvent().getShards());
        Map<String, String> body = Map.of(
                "event_id", row.get("id").toString(),
                "type", (String) row.get("type"),
                "aggregate_type", (String) row.get("aggregate_type"),
                "aggregate_id", aggregateId.toString(),
                "occurred_at", String.valueOf(row.get("occurred_at")),
                "payload", (String) row.get("payload"));
        redis.opsForStream().add(STREAM_PREFIX + shard, body);
    }

    /** 单条失败：retry_count+1 继续下一条；retry>16 移入 dead_letter 并删 outbox 行 */
    private void handleRelayFailure(Map<String, Object> row, UUID id, Exception ex) {
        int retries = ((Number) row.get("retry_count")).intValue() + 1;
        String type = (String) row.get("type");
        if (retries > MAX_RETRY) {
            jdbc.update(DEAD_LETTER, type, (String) row.get("payload"),
                    abbreviate(ex.toString()), retries);
            jdbc.update(DELETE_OUTBOX, id);
            log.error("[outbox-relay] dead-lettered type={} eventId={} retries={}", type, id, retries);
        } else {
            jdbc.update(BUMP_RETRY, id);
            log.warn("[outbox-relay] publish failed type={} eventId={} retry={}/{}: {}",
                    type, id, retries, MAX_RETRY, ex.getMessage());
        }
    }

    /** 连接级故障（拒连/超时）→ 退避；其余（数据类）→ 按单条失败计 */
    private static boolean isConnectionFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RedisConnectionFailureException || t instanceof RedisCommandTimeoutException) {
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
}
