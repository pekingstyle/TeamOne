package cn.teamone.app.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 事件骨干装配：@Scheduled 开关 + 消费者专用 Redis 连接。
 *
 * <p>EventConsumer 的 XREADGROUP 带 BLOCK 0（长阻塞）：绝不能跑在 lettuce
 * 共享连接上（会挂起全局 Redis 调用——刷新令牌/ACL 缓存/relay XADD 全堵），
 * 故单独建一个 shareNativeConnection=false 的连接工厂专供消费者。
 * 注意只暴露工厂、不暴露 StringRedisTemplate bean——否则会触发自动装配的
 * {@code @ConditionalOnMissingBean} 使全局 stringRedisTemplate 退位（启动即炸）；
 * 消费者拿到工厂后自建模板。</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "teamone.event.enabled", havingValue = "true", matchIfMissing = true)
public class EventBackboneConfig {

    @Bean(name = "eventConsumerRedisConnectionFactory")
    public LettuceConnectionFactory eventConsumerRedisConnectionFactory(RedisProperties redis) {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getPort());
        factory.setShareNativeConnection(false); // 每操作独立连接：阻塞读只占自己的
        return factory;
    }
}
