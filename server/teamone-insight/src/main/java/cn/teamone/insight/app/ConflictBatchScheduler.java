package cn.teamone.insight.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 冲突批算调度器（02 FR-v2-04「每日全量」；08 §5 W2）。
 *
 * <p>开关口径与 W3 模式一致（{@code @ConditionalOnProperty teamone.event.enabled}）：
 * IT/集成测试关闭事件骨干时同样不跑批——批算产出会写 prd.conflict_snapshot，
 * 与 outbox 消费共用一套启停（方向审查裁决：scheduler 归 event 开关管）。</p>
 *
 * <p>每日 02:00 全量重算（重写当日快照，幂等）；异常由调度器记录、下轮重试。
 * 变更增量不在此——由 workitem.updated 事件消费端触发（同一纯函数入口）。</p>
 */
@Component
@ConditionalOnProperty(name = "teamone.event.enabled", havingValue = "true", matchIfMissing = true)
public class ConflictBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConflictBatchScheduler.class);

    private final ConflictBatchService batch;

    public ConflictBatchScheduler(ConflictBatchService batch) {
        this.batch = batch;
    }

    /** 每日 02:00 全量（cron 秒 分 时 日 月 周） */
    @Scheduled(cron = "0 0 2 * * *")
    public void dailyFullRecompute() {
        try {
            batch.recomputeAll();
        } catch (Exception ex) {
            log.warn("[conflict-batch] daily recompute failed, retry next schedule: {}", ex.getMessage(), ex);
        }
    }
}
