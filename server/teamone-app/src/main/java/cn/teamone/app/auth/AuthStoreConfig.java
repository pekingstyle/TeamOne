package cn.teamone.app.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 刷新令牌存储装配：{@code teamone.auth.refresh-store=jpa|redis} 一键切换。
 * 默认 jpa（与 M0 行为完全一致）；Valkey 容器就绪并通过验证后，.env 设
 * TEAMONE_REFRESH_STORE=redis 即完成迁移，不改任何业务代码（W1 纪律）。
 *
 * @author Ivan Yang, 2026-09-11
 */
@Configuration
public class AuthStoreConfig {

    @Bean
    public RefreshTokenStore refreshTokenStore(
            @Value("${teamone.auth.refresh-store:jpa}") String mode,
            RefreshTokenRepository jpaRepo,
            StringRedisTemplate redis) {
        return switch (mode) {
            case "redis" -> new RedisRefreshTokenStore(redis);
            case "jpa" -> new JpaRefreshTokenStore(jpaRepo);
            default -> throw new IllegalStateException(
                    "teamone.auth.refresh-store 取值非法: " + mode + "（仅支持 jpa|redis）");
        };
    }
}
