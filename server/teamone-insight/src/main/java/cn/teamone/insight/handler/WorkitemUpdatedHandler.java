package cn.teamone.insight.handler;

import cn.teamone.insight.app.ConflictBatchService;
import cn.teamone.shared.event.W3EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * workitem.updated 消费端（05 §5.1 W2 增订行；02 FR-v2-04「变更增量」）：
 * prd PUT 工作项成功后补发事件，本端按 payload.assigneeId 触发该当事人冲突重算
 * （同一纯函数入口，快照幂等重写）。无主任务变更无当事人 → 静默跳过（每日全量兜底）。
 */
@Component
public class WorkitemUpdatedHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkitemUpdatedHandler.class);

    private final ConflictBatchService batch;

    public WorkitemUpdatedHandler(ConflictBatchService batch) {
        this.batch = batch;
    }

    @Override
    public Set<String> types() {
        return Set.of("workitem.updated");
    }

    @Override
    public void handle(JsonNode payload) {
        String assignee = payload.path("assigneeId").asText(null);
        if (assignee == null || assignee.isBlank()) {
            return;
        }
        try {
            batch.recomputeForUser(UUID.fromString(assignee));
            log.info("[insight-conflict] incremental recompute done for user={} key={}",
                    assignee, payload.path("key").asText(""));
        } catch (RuntimeException ex) {
            log.warn("[insight-conflict] incremental recompute failed user={}: {}",
                    assignee, ex.getMessage());
            throw ex; // PEL 重投（至少一次；重写快照幂等）
        }
    }
}
