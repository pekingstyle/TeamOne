package cn.teamone.collab.handler;

import cn.teamone.collab.app.ConversationService;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.shared.event.W3EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

import cn.teamone.collab.HandlerJson;

/**
 * 延迟归档消费者（权威目录 §5.1，collab；05 §6.2 缓冲期保护）：
 *
 * <ul>
 *   <li>workitem.status_changed（payload.type=defect）：to=已关闭 → scheduleArchive；
 *       to=重新打开等非关闭态 → cancelArchive（红线 9：反悔期免费撤销）；</li>
 *   <li>requirement.status_changed（payload.type=requirement）：to=closed → scheduleArchive；
 *       to≠closed → cancelArchive；</li>
 *   <li>sprint.completed（M2-INC-1 W2 增订）：payload{sprintId,...} → 迭代话题按 sprint_ended
 *       口径延迟归档（archived_reason='sprint_ended'，与 05 §5.1 sprint_ended 一致）。</li>
 * </ul>
 *
 * <p>status_changed 两事件载荷同构（均带 id/type/from/to——prd 侧单表工作项模型），以 payload.type
 * 区分 target：requirement→requirement 话题，defect→defect 话题；task/test_task
 * 无自动话题，静默跳过。按 target 查不到 conversation（未自动建题）同样静默跳过。</p>
 */
@Component
public class ArchiveHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(ArchiveHandler.class);

    public static final String STATUS_DEFECT_CLOSED = "已关闭";
    public static final String STATUS_REQ_CLOSED = "closed";
    /** 迭代话题归档原因（05 §5.1 sprint_ended 口径） */
    public static final String REASON_SPRINT_ENDED = "sprint_ended";

    private final ConversationRepository conversations;
    private final ConversationService topicArchive;
    private final int archiveDelayMinutes;

    public ArchiveHandler(ConversationRepository conversations,
                          ConversationService topicArchive,
                          @Value("${teamone.topic.archive-delay-minutes:1440}") int archiveDelayMinutes) {
        this.conversations = conversations;
        this.topicArchive = topicArchive;
        this.archiveDelayMinutes = archiveDelayMinutes;
    }

    @Override
    public Set<String> types() {
        return Set.of("workitem.status_changed", "requirement.status_changed", "sprint.completed");
    }

    @Override
    public void handle(JsonNode payload) {
        // sprint.completed（W2）：迭代完成 → 迭代话题 sprint_ended 归档（动作=target_closed 同口径）
        String sprintId = payload.path("sprintId").asText(null);
        if (sprintId != null && !sprintId.isBlank()) {
            onSprintCompleted(payload, sprintId);
            return;
        }

        String wiType = HandlerJson.text(payload, "type");
        boolean isRequirement = "requirement".equals(wiType);
        if (!isRequirement && !"defect".equals(wiType)) {
            return; // task/test_task 无自动话题：目录口径，静默跳过
        }
        UUID targetId = HandlerJson.uuidOrNull(payload, "id");
        if (targetId == null) {
            return;
        }
        String targetType = isRequirement ? "requirement" : "defect";

        var conv = conversations.findByTargetTypeAndTargetId(targetType, targetId).orElse(null);
        if (conv == null) {
            return; // 未自动建题的对象：静默跳过
        }

        String to = HandlerJson.text(payload, "to");
        boolean closing = isRequirement
                ? STATUS_REQ_CLOSED.equals(to)
                : STATUS_DEFECT_CLOSED.equals(to);
        if (closing) {
            topicArchive.scheduleArchive(conv.getId(), archiveDelayMinutes);
            log.info("[collab-archive] scheduled conv={} target={}/{} to={}",
                    conv.getId(), targetType, targetId, to);
        } else {
            // 缓冲期保护：恢复非关闭态 → 撤销待归档（ZREM no-op 安全）
            topicArchive.cancelArchive(conv.getId());
            log.info("[collab-archive] cancelled conv={} target={}/{} to={}",
                    conv.getId(), targetType, targetId, to);
        }
    }

    /** 迭代完成：target_type=sprint 定位话题（未建题静默跳过），复用延迟归档动作 */
    private void onSprintCompleted(JsonNode payload, String sprintId) {
        UUID targetId;
        try {
            targetId = UUID.fromString(sprintId);
        } catch (IllegalArgumentException ex) {
            log.warn("[collab-archive] sprint.completed bad sprintId dropped: {}", sprintId);
            return;
        }
        var conv = conversations.findByTargetTypeAndTargetId("sprint", targetId).orElse(null);
        if (conv == null) {
            return; // 迭代未建话题：静默跳过
        }
        topicArchive.scheduleArchive(conv.getId(), archiveDelayMinutes, REASON_SPRINT_ENDED);
        log.info("[collab-archive] scheduled conv={} target=sprint/{} reason={} moved={}",
                conv.getId(), targetId, REASON_SPRINT_ENDED, payload.path("movedCount").asInt(0));
    }
}
