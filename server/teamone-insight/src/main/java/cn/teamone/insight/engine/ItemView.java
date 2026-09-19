package cn.teamone.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 工作项视图（引擎输入行；由 insight 只读实体映射装配，字段=批算所需最小集）。
 *
 * @param id            工作项 id
 * @param key           业务键（T-12/D-88，文案与指纹）
 * @param title         标题（冲突 payload events 结构化字段透出；尽力而为，可为 null）
 * @param assigneeId    负责人（可为 null——无主任务只参与 CF-5/CF-6）
 * @param productId     所属产品（CF-4 跨产品判定）
 * @param sprintId      迭代（CF-2 跨迭代 / CF-5/CF-6 容器截止）
 * @param releaseId     交付版本（CF-6 发布日）
 * @param status        原始状态字面量（CF-2「双双进行中」按 'in_progress' 逐字判定）
 * @param startDate     开始日（摊销起点，含）
 * @param dueDate       截止日（摊销终点，含）
 * @param estimateHours 预估工时（V8 列；null=无摊销原料，不参与负载）
 */
public record ItemView(UUID id, String key, String title, UUID assigneeId, UUID productId, UUID sprintId,
                       UUID releaseId, String status, LocalDate startDate, LocalDate dueDate,
                       BigDecimal estimateHours) {

    /** 兼容旧装配（热力图实时聚合 / 基线单测的 10 参构造）：title 留空——events 为尽力而为字段 */
    public ItemView(UUID id, String key, UUID assigneeId, UUID productId, UUID sprintId,
                    UUID releaseId, String status, LocalDate startDate, LocalDate dueDate,
                    BigDecimal estimateHours) {
        this(id, key, null, assigneeId, productId, sprintId, releaseId, status, startDate, dueDate, estimateHours);
    }
}
