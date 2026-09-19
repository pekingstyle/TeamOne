package cn.teamone.collab.handler;

import cn.teamone.collab.HandlerJson;
import cn.teamone.collab.domain.Notification;
import cn.teamone.collab.repo.NotificationRepository;
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
 * 通知消费者（权威目录 §5.1，collab；06 §3 W3 通知最小版）：
 *
 * <ul>
 *   <li>requirement.submitted → reviewerIds 逐条 INSERT notification(kind=requirement.review，
 *       payload{requirementId,key,title})；</li>
 *   <li>requirement.review_result → <b>简化裁决（总监批）：不发通知</b>——owner 不在
 *       payload，06 最小版口径只保留 submitted 与 blocked=false 两条路径；</li>
 *   <li>defect.blocked_changed 且 blocked=false → 通知 productOwnerId
 *       （kind=release.gate，payload{releaseId,blocked:false}）。</li>
 * </ul>
 *
 * <p>M1 口径：通知只落库不经 WS（HTTP 收件箱轮询）。通知允许重复（红线 1 允许项），
 * 但同事件幂等去重已由消费端 processed_event 保证，Handler 内不做额外判重。</p>
 */
@Component
public class NotifyHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotifyHandler.class);

    private final NotificationRepository notifications;

    public NotifyHandler(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    public Set<String> types() {
        return Set.of("requirement.submitted", "requirement.review_result", "defect.blocked_changed");
    }

    @Override
    @Transactional
    public void handle(JsonNode payload) {
        if (payload.hasNonNull("requirementId") && payload.hasNonNull("reviewerIds")) {
            onSubmitted(payload);          // requirement.submitted
            return;
        }
        if (payload.hasNonNull("reviewerId")) {
            // requirement.review_result：简化裁决（总监批）不发通知，仅记录
            log.info("[collab-notify] review_result suppressed by M1 minimal scope: {}",
                    HandlerJson.text(payload, "key"));
            return;
        }
        if (payload.hasNonNull("releaseId")) {
            onBlockedChanged(payload);     // defect.blocked_changed
        }
    }

    /** 评审邀请：reviewerIds 逐条落库（渲染事实全随 payload，collab 禁 import prd） */
    private void onSubmitted(JsonNode payload) {
        UUID requirementId = HandlerJson.uuidOrThrow(payload, "requirementId", "requirement.submitted");
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("requirementId", requirementId.toString());
        projection.put("key", HandlerJson.text(payload, "key"));
        projection.put("title", HandlerJson.text(payload, "title"));

        int count = 0;
        for (UUID reviewerId : HandlerJson.uuidList(payload, "reviewerIds")) {
            Notification n = new Notification();
            n.setUserId(reviewerId);
            n.setKind(Notification.KIND_REQUIREMENT_REVIEW);
            n.setPayload(new LinkedHashMap<>(projection));
            notifications.save(n);
            count++;
        }
        log.info("[collab-notify] review invitations: requirement={} reviewers={}",
                requirementId, count);
    }

    /** 门禁解除：blocked=false 才通知发布经理（productOwnerId 由 prd 侧补全进 payload） */
    private void onBlockedChanged(JsonNode payload) {
        if (!payload.hasNonNull("blocked") || payload.get("blocked").asBoolean()) {
            return; // blocked=true（新增阻塞）不通知——06 通知最小版只做「解除」一条
        }
        UUID productOwnerId = HandlerJson.uuidOrNull(payload, "productOwnerId");
        if (productOwnerId == null) {
            log.warn("[collab-notify] blocked=false without productOwnerId, skip: {}",
                    HandlerJson.text(payload, "releaseId"));
            return;
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("releaseId", HandlerJson.uuidOrThrow(payload, "releaseId", "defect.blocked_changed").toString());
        projection.put("blocked", false);
        Notification n = new Notification();
        n.setUserId(productOwnerId);
        n.setKind(Notification.KIND_RELEASE_GATE);
        n.setPayload(projection);
        notifications.save(n);
        log.info("[collab-notify] gate unblocked notification: release={} owner={}",
                projection.get("releaseId"), productOwnerId);
    }
}
