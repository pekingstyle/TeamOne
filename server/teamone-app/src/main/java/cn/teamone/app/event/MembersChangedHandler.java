package cn.teamone.app.event;

import cn.teamone.app.ws.TeamOneWsHandler;
import cn.teamone.shared.event.W3EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * 会话成员变更消费者（M2-INC-2 T-2，权威目录 §5.1 增订行 conversation.members_changed）：
 * collab ConversationService 在加人/退群事务内写 outbox，本 Handler 消费后调用
 * {@link TeamOneWsHandler#onMembersChanged}——清该会话 sub 鉴权缓存（含负缓存）+
 * 重校验本地订阅表（被移出者即刻失效，§4.4「成员变更触发节点本地订阅表清理」）。
 *
 * <p>放在 app 包的原因：TeamOneWsHandler（含其 aclCache/channelSubs）在 app.ws，
 * collab 模块不可依赖 app（ArchUnit R4/R7），事件骨干是唯一合法通路。</p>
 */
@Component
public class MembersChangedHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(MembersChangedHandler.class);

    private final TeamOneWsHandler wsHandler;

    public MembersChangedHandler(TeamOneWsHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @Override
    public Set<String> types() {
        // 权威常量在 collab.ConversationService（app 可依赖 collab，§1.4 单向）
        return Set.of(cn.teamone.collab.app.ConversationService.EVENT_MEMBERS_CHANGED);
    }

    @Override
    public void handle(JsonNode payload) {
        UUID conversationId = parseUuid(payload.path("conversationId").asText(null));
        if (conversationId == null) {
            log.warn("[members-changed] payload missing conversationId, dropped");
            return;
        }
        wsHandler.onMembersChanged(conversationId);
        log.info("[members-changed] conv={} ws authz cache invalidated",
                conversationId);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
