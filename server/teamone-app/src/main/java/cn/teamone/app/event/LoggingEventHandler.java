package cn.teamone.app.event;

import cn.teamone.shared.event.W3EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * W3 骨干烟测用日志 Handler（types() 空集合 = 通配）：
 * 打印每个事件载荷的 id/key，供端到端链路与幂等去重验证。
 * ③b 在 collab 域挂真实 Handler（自动建题/通知/归档）后本类保留为链路观测。
 */
@Component
public class LoggingEventHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventHandler.class);

    @Override
    public Set<String> types() {
        return Set.of(); // 空集合=通配（接口约定）
    }

    @Override
    public void handle(JsonNode payload) {
        log.info("[w3-event-handler] id={} key={} title={}",
                text(payload, "id"), text(payload, "key"), text(payload, "title"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "-" : v.asText();
    }
}
