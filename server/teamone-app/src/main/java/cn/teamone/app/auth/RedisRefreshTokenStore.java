package cn.teamone.app.auth;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 刷新令牌 · Valkey 存储实现（M1-W1 预置，架构设计 §1.5：Valkey {@code rt:{hash}}，7d 滑动）。
 *
 * <p>过期即失效由 Redis TTL 天然保证；吊销 = DEL（立即生效，用于登出/旋转/踢人）。
 * 与表存储的差异：无审计痕迹（令牌生命周期短，审计诉求由登录日志承担）。</p>
 *
 * <p><b>故障语义</b>：认证相关存储失败一律<b>快速失败</b>（异常上抛 → 5xxx 信封），
 * 不做静默降级——认证链路上"宁可拒绝，不可放行"。由 {@link AuthStoreConfig} 装配。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private final StringRedisTemplate redis;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String tokenHash) {
        return "rt:" + tokenHash;
    }

    @Override
    public void store(String tokenHash, UUID userId, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("刷新令牌过期时间必须晚于当前时刻");
        }
        redis.opsForValue().set(key(tokenHash), userId.toString(), ttl);
    }

    @Override
    public Optional<UUID> findValidUserId(String tokenHash) {
        String v = redis.opsForValue().get(key(tokenHash));
        return v == null ? Optional.empty() : Optional.of(UUID.fromString(v));
    }

    @Override
    public void revoke(String tokenHash) {
        redis.delete(key(tokenHash));
    }

    @Override
    public void revokeAllForUser(UUID userId, String exceptHash) {
        // 改密强制下线（保留当前设备）：键空间为 hash→userId 单向索引，只能 SCAN 全量比对值删除。
        // 令牌键量级 = 活跃会话数（≤ 用户数 × 设备数，量小），SCAN 一次性可接受；
        // 与 JPA 实现的语义差异：值存储无吊销痕迹，过期键由 TTL 天然消失，DEL 即永久失效
        String exempt = (exceptHash == null || exceptHash.isBlank()) ? null : key(exceptHash);
        java.util.Set<String> keys = redis.keys("rt:*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> hit = keys.stream()
                .filter(k -> !k.equals(exempt))
                .filter(k -> userId.toString().equals(redis.opsForValue().get(k)))
                .toList();
        if (!hit.isEmpty()) {
            redis.delete(hit);
        }
    }
}
