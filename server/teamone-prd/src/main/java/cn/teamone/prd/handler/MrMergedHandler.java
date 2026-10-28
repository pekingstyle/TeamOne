package cn.teamone.prd.handler;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import cn.teamone.prd.app.TransitionService;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.shared.event.W3EventHandler;

import java.util.Set;

/**
 * mr.merged 消费端（⑦ Closes 语义 · docs/v2/17 §2）：eng MR 合并成功后发事件，
 * 本端回写关联工作项——GitLab「Closes #N」口径：
 * 仅合并进<b>仓库默认分支</b>且关联项 type=task 且未完结时自动流转 done；
 * requirement 等他类/非法流转（已完结等）捕获跳过并 WARN——业务常态而非故障，
 * 不抛异常（避免 PEL 无限重投；事件消费幂等）。
 *
 * @author Ivan Yang, 2026-09-30
 */
@Component
public class MrMergedHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(MrMergedHandler.class);

    private final WorkItemRepository workItems;
    private final TransitionService transitions;

    public MrMergedHandler(WorkItemRepository workItems, TransitionService transitions) {
        this.workItems = workItems;
        this.transitions = transitions;
    }

    @Override
    public Set<String> types() {
        return Set.of("mr.merged");
    }

    @Override
    public void handle(JsonNode payload) {
        String key = payload.path("linkedWorkItemKey").asText(null);
        if (key == null || key.isBlank()) {
            return;
        }
        // 集成线语义（docs/v2/17 §2 裁决）：TeamOne GitFlow 下 develop 承担默认分支的集成职责
        // （feature/* 经分支策略固定合入 develop）——触发线=默认分支 ∨ develop；交付分支不触发
        boolean completingBranch = payload.path("isDefaultBranch").asBoolean(false)
                || "develop".equalsIgnoreCase(payload.path("targetBranch").asText(""));
        if (!completingBranch) {
            log.info("[mr-merged] 非集成线合并（MR !{} → {}），Closes 语义不触发",
                    payload.path("mrNumber").asText(""), payload.path("targetBranch").asText(""));
            return;
        }
        WorkItem item = workItems.findByKey(key).orElse(null);
        if (item == null) {
            log.warn("[mr-merged] 关联工作项不存在: {}", key);
            return;
        }
        if (!WorkItem.TYPE_TASK.equals(item.getType())) {
            log.info("[mr-merged] 关联项 {} 类型为 {}（非 task），不自动流转", key, item.getType());
            return;
        }
        if (WorkItem.doneStatusesOf(item.getType()).contains(item.getStatus())) {
            log.info("[mr-merged] 关联项 {} 已完结（{}），幂等跳过", key, item.getStatus());
            return;
        }
        // QA MUST-FIX ④：mergedBy 解析移入 try（畸形值按业务跳过而非逃逸死信）
        try {
            UUID actor = payload.path("mergedBy").isMissingNode()
                    || payload.path("mergedBy").asText().isBlank()
                    ? item.getReporterId()
                    : UUID.fromString(payload.path("mergedBy").asText());
            transitions.transition(item.getId(), WorkItem.STATUS_DONE, actor);
            log.info("[mr-merged] Closes 语义：{} 自动流转 done（MR !{}）",
                    key, payload.path("mrNumber").asText(""));
        } catch (cn.teamone.shared.api.PermissionDeniedException ex) {
            // ⑧ 挂账清偿：合并者无 product edit 权限时 Closes 被拒——独立 ERROR（主打功能静默失效需运营可见）
            log.error("[mr-merged] Closes 语义因权限失配未生效: {} 合并者无 product edit 权限（{}）",
                    key, ex.getMessage());
        } catch (cn.teamone.shared.api.BusinessException ex) {
            // QA MUST-FIX ①：仅状态机永久性拒绝跳过（非法流转）——
            // DataAccessException 等基础设施故障必须放行给 PEL 重投（at-least-once）
            log.warn("[mr-merged] {} 自动流转业务性跳过: {}", key, ex.getMessage());
        }
    }
}
