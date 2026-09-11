package cn.teamone.app.config;

import cn.teamone.platform.authz.DecisionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 授权决策缓存 · Valkey 实现（协议兼容 Redis 8，架构设计 §1.5）。
 *
 * <p>结构：{@code acl:{uid}} Hash，field = "{resType}:{resId}:{action}"，value = "1"/"0"，
 * 整 key TTL 5min（可配）。W3 acl.changed 事件消费时调用 {@link #evictUser} 精准失效。</p>
 *
 * <p><b>容错纪律</b>：缓存是加速器而非真相——任何 Redis 异常一律降级为"未命中"，
 * 授权主流程回退查库，绝不让缓存故障放大为业务故障；告警日志仅打一次防刷屏。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
@Component
public class RedisDecisionCache implements DecisionCache {

    private static final Logger log = LoggerFactory.getLogger(RedisDecisionCache.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public RedisDecisionCache(StringRedisTemplate redis,
                              @Value("${teamone.cache.acl-ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    private static String key(UUID userId) {
        return "acl:" + userId;
    }

    private static String field(String resourceType, UUID resourceId, String action) {
        return resourceType + ":" + (resourceId == null ? "nil" : resourceId) + ":" + action;
    }

    @Override
    public Optional<Boolean> get(UUID userId, String resourceType, UUID resourceId, String action) {
        try {
            Object v = redis.opsForHash().get(key(userId), field(resourceType, resourceId, action));
            if (v == null) {
                return Optional.empty();
            }
            return Optional.of("1".equals(v));
        } catch (RuntimeException e) {
            warnOnce(e);
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID userId, String resourceType, UUID resourceId, String action, boolean allowed) {
        try {
            String k = key(userId);
            redis.opsForHash().put(k, field(resourceType, resourceId, action), allowed ? "1" : "0");
            redis.expire(k, ttl);
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    @Override
    public void evictUser(UUID userId) {
        try {
            redis.delete(key(userId));
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    private void warnOnce(RuntimeException e) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("[acl-cache] Valkey 不可用，授权决策缓存降级为直查（不影响主流程）: {}", e.getMessage());
        }
    }
}
