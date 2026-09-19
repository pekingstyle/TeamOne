package cn.teamone.app.ws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.teamone.app.event.TeamoneProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * WS 扇出订阅装配（W3-③b）：Valkey Pub/Sub 专用连接订阅 teamone:ws:fanout。
 *
 * <p><b>③a 交接红线</b>：Pub/Sub 长阻塞订阅绝不复用 eventConsumerRedisConnectionFactory
 * （消费组专用）——本配置自建 shareNativeConnection=false 的专用工厂供 listener container
 * 独占。同样不注册 StringRedisTemplate bean（避免顶掉自动装配的 stringRedisTemplate）。</p>
 *
 * <p><b>③b 实测补记（启动即炸复盘）</b>：工厂若注册成 bean，上下文内出现
 * 两个 RedisConnectionFactory → 自动装配 redisTemplate/stringRedisTemplate 的
 * &#64;ConditionalOnSingleCandidate 不再匹配 → AuthStoreConfig 等注入点启动即炸。
 * 故专用工厂不进 bean 定义，随 {@link WsFanoutStack} 程序化持有：
 * start→container 订阅；destroy→container+factory 全量释放，生命周期零泄漏。</p>
 *
 * <p>收到 fanout 帧 {ch, frame} → 本地按 ch 匹配订阅者推送
 * （{@link TeamOneWsHandler#pushToChannel}，含发送者自身）。
 * Valkey 不可用：container 后台自动重连，推送侧仅 WARN（红线 5），health 不耦合。</p>
 */
@Configuration
@ConditionalOnProperty(name = "teamone.event.enabled", havingValue = "true", matchIfMissing = true)
public class WsFanoutConfig {

    private static final Logger log = LoggerFactory.getLogger(WsFanoutConfig.class);

    @Bean
    public WsFanoutStack wsFanoutStack(RedisProperties redis, TeamOneWsHandler wsHandler,
                                       TeamoneProps props, ObjectMapper om) {
        // 专用连接工厂（非 bean：原因见类注释——保住 stringRedisTemplate 的单候选条件）
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getPort());
        factory.setShareNativeConnection(false); // 订阅长连接独占，不占共享连接池
        factory.afterPropertiesSet();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                JsonNode node = om.readTree(body);
                String ch = node.path("ch").asText("");
                JsonNode frame = node.path("frame");
                if (!ch.isEmpty() && !frame.isMissingNode()) {
                    wsHandler.pushToChannel(ch, frame.toString());
                } else {
                    log.warn("[ws-fanout] frame missing ch/frame, dropped: {}", abbreviate(body));
                }
            } catch (Exception ex) {
                log.warn("[ws-fanout] bad fanout frame dropped: {} ({})", abbreviate(body), ex.getMessage());
            }
        }, new ChannelTopic(props.getWs().getFanoutChannel()));
        container.afterPropertiesSet();
        return new WsFanoutStack(factory, container);
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * 扇出订阅栈：container 生命周期（SmartLifecycle 容器自动 start/stop）
     * + 专用工厂释放（DisposableBean）捆成一个 bean，避免泄漏 Lettuce client。
     */
    static class WsFanoutStack implements SmartLifecycle, DisposableBean {

        private final LettuceConnectionFactory factory;
        private final RedisMessageListenerContainer container;
        private volatile boolean running = false;

        WsFanoutStack(LettuceConnectionFactory factory, RedisMessageListenerContainer container) {
            this.factory = factory;
            this.container = container;
        }

        @Override
        public void start() {
            container.start();
            running = true;
            log.info("[ws-fanout] subscribed fanout stack started");
        }

        @Override
        public void stop() {
            running = false;
            container.stop();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void destroy() {
            try {
                container.destroy();
            } catch (Exception ex) {
                log.warn("[ws-fanout] container destroy suppressed: {}", ex.getMessage());
            }
            factory.destroy();
        }
    }
}
