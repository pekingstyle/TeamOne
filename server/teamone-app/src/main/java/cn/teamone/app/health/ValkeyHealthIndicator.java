package cn.teamone.app.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 自定义 Valkey / Redis 连通性与往返延迟健康指标（V-17）。
 * <p>
 * 遵守 W3 红线 4 纪律：Valkey 为可降级依赖组件，当其离线或网络抖动时，系统自动降级直查，
 * 不会将全局系统健康拉为 DOWN，同时向 Prometheus 导出真实连通状态与往返延迟毫秒数。
 * </p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Component
public class ValkeyHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;

    public ValkeyHealthIndicator(
            @Autowired(required = false) @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        if (redisTemplate == null || redisTemplate.getConnectionFactory() == null) {
            return Health.up()
                    .withDetail("valkey", "DISABLED")
                    .withDetail("message", "未注入 StringRedisTemplate，运行于内存降级模式")
                    .build();
        }

        long start = System.currentTimeMillis();
        try (RedisConnection conn = redisTemplate.getConnectionFactory().getConnection()) {
            String pong = conn.ping();
            long latencyMs = System.currentTimeMillis() - start;
            return Health.up()
                    .withDetail("valkey", "CONNECTED")
                    .withDetail("response", pong)
                    .withDetail("latencyMs", latencyMs)
                    .build();
        } catch (Exception e) {
            return Health.up()
                    .withDetail("valkey", "DEGRADED")
                    .withDetail("warning", "Valkey 连接异常，系统已自动平滑降级: " + e.getMessage())
                    .build();
        }
    }
}
