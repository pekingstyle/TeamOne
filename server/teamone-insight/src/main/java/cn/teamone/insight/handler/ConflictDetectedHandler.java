package cn.teamone.insight.handler;

import cn.teamone.insight.domain.InsightNotification;
import cn.teamone.insight.repo.InsightNotificationRepository;
import cn.teamone.shared.event.W3EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * conflict.detected 消费端（05 §5.1 W2 增订行）：
 * 红色冲突新增 → INSERT collab.notification（kind=conflict.red，总监裁决的 insight
 * 写映射例外，见包声明）。WS user:{userId} 帧由 app.FanoutHandler 扩展负责（本端不碰 WS）。
 * 幂等：processed_event 去重先行，同事件只驱动一次；重复通知为红线 1 允许项。
 */
@Component
public class ConflictDetectedHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(ConflictDetectedHandler.class);

    private final InsightNotificationRepository notifications;

    public ConflictDetectedHandler(InsightNotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    public Set<String> types() {
        return Set.of("conflict.detected");
    }

    @Override
    @Transactional
    public void handle(JsonNode payload) {
        String userId = payload.path("userId").asText(null);
        if (userId == null || userId.isBlank()) {
            log.warn("[insight-conflict] conflict.detected without userId, dropped");
            return;
        }
        int redCount = payload.path("redCount").asInt(0);

        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("redCount", redCount);
        projection.put("kind", "红色冲突");
        projection.put("text", "您有 " + redCount + " 个新增红色冲突，请到冲突中心处理");

        InsightNotification n = new InsightNotification();
        n.setUserId(UUID.fromString(userId));
        n.setKind(InsightNotification.KIND_CONFLICT_RED);
        n.setPayload(projection);
        notifications.save(n);
        log.info("[insight-conflict] red conflict notification: user={} redCount={}", userId, redCount);
    }
}
