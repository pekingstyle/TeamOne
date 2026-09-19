package cn.teamone.collab.handler;

import cn.teamone.collab.app.ConversationService;
import cn.teamone.shared.event.W3EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

import cn.teamone.collab.HandlerJson;

/**
 * 自动建题消费者（权威目录 §5.1，collab）：
 *
 * <ul>
 *   <li>workitem.created 且 type=defect 且 severity=致命 → autoTopic("defect",…)
 *       + 干系人（payload.stakeholderUserIds）+ 系统消息「缺陷 D-xx 已登记，话题自动创建」；</li>
 *   <li>requirement.submitted → autoTopic("requirement",…)
 *       + 系统消息「需求提交评审，话题自动创建」。</li>
 * </ul>
 *
 * <p>接口只给 payload 不给事件 type——两事件以载荷形状区分（requirement.submitted
 * 必带 requirementId；workitem.created 必带 id+type）。幂等由 ConversationService
 * 的唯一索引 + 补拉语义保证（红线 1）。</p>
 *
 * <p>系统消息 sender 取「系统感更强者」：reporterId 优先（workitem.created 有），
 * 缺位时退化取 stakeholderUserIds 首位（prd 侧 stakeholdersOf 约定 reporter 居首）。</p>
 */
@Component
public class AutoTopicHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(AutoTopicHandler.class);
    private static final String SEVERITY_FATAL = "致命";

    private final ConversationService conversations;

    public AutoTopicHandler(ConversationService conversations) {
        this.conversations = conversations;
    }

    @Override
    public Set<String> types() {
        return Set.of("workitem.created", "requirement.submitted");
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload.hasNonNull("requirementId")) {
            // ---- requirement.submitted ----
            UUID requirementId = HandlerJson.uuidOrThrow(payload, "requirementId", "requirement.submitted");
            conversations.autoTopic("requirement", requirementId,
                    HandlerJson.text(payload, "title"),
                    HandlerJson.uuidList(payload, "stakeholderUserIds"),
                    "需求提交评审，话题自动创建",
                    systemSender(payload));
            log.info("[collab-auto-topic] requirement topic ensured: {}", requirementId);
            return;
        }
        // ---- workitem.created：致命与严重缺陷建题（目录口径），拉入干系人，其余类型静默跳过 ----
        String severity = HandlerJson.text(payload, "severity");
        if (!"defect".equals(HandlerJson.text(payload, "type"))
                || (!SEVERITY_FATAL.equals(severity) && !"严重".equals(severity))) {
            return;
        }
        UUID targetId = HandlerJson.uuidOrThrow(payload, "id", "workitem.created");
        conversations.autoTopic("defect", targetId,
                HandlerJson.text(payload, "title"),
                HandlerJson.uuidList(payload, "stakeholderUserIds"),
                "缺陷 " + HandlerJson.text(payload, "key") + " 已登记，话题自动创建",
                systemSender(payload));
        log.info("[collab-auto-topic] defect topic ensured: {}", targetId);
    }

    private UUID systemSender(JsonNode payload) {
        UUID reporter = HandlerJson.uuidOrNull(payload, "reporterId");
        if (reporter != null) {
            return reporter;
        }
        var stakeholders = HandlerJson.uuidList(payload, "stakeholderUserIds");
        return stakeholders.isEmpty() ? null : stakeholders.get(0);
    }
}
