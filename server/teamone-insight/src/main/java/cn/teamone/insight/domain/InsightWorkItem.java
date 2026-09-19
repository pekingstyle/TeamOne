package cn.teamone.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * prd.work_item 只读映射（跨 schema 只读，红线①；权威实体 cn.teamone.prd.domain.WorkItem）。
 *
 * <p>列白名单（仅映射冲突批算所需列，其余列一律不映射——08 §5 风险表「只映射批算所需列」）：
 * id, key, title, assignee_id, product_id, sprint_id, release_id, status, type, start_date, due_date, estimate_hours。
 * {@code @Immutable}：Hibernate 拒绝任何 UPDATE 语句生成。</p>
 */
@Entity
@Immutable
@Table(name = "work_item", schema = "prd")
public class InsightWorkItem {

    @Id
    private UUID id;

    /** 业务键（T-12/D-88，冲突详情文案与 fingerprint 用） */
    @Column(name = "key", insertable = false, updatable = false)
    private String key;

    /** 标题（冲突 payload events 结构化字段透出，只读白名单增补列） */
    @Column(insertable = false, updatable = false)
    private String title;

    @Column(name = "assignee_id", insertable = false, updatable = false)
    private UUID assigneeId;

    @Column(name = "product_id", insertable = false, updatable = false)
    private UUID productId;

    @Column(name = "sprint_id", insertable = false, updatable = false)
    private UUID sprintId;

    @Column(name = "release_id", insertable = false, updatable = false)
    private UUID releaseId;

    @Column(insertable = false, updatable = false)
    private String status;

    @Column(insertable = false, updatable = false)
    private String type;

    @Column(name = "start_date", insertable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "due_date", insertable = false, updatable = false)
    private LocalDate dueDate;

    /** 预估工时（V8 加列，numeric(6,1)；红线④：estimate_hours 只做负载摊销，story_points 只做进度） */
    @Column(name = "estimate_hours", insertable = false, updatable = false)
    private BigDecimal estimateHours;

    /** 组件 ID（归属组件分析与缺陷分布） */
    @Column(name = "component_id", insertable = false, updatable = false)
    private UUID componentId;

    /** 故事点（用于迭代速率与燃尽计算） */
    @Column(name = "story_points", insertable = false, updatable = false)
    private BigDecimal storyPoints;

    /** 缺陷严重度（'致命' | '严重' | '一般' | '轻微'） */
    @Column(insertable = false, updatable = false)
    private String severity;

    /** 创建时间（用于 CFD 累积流图与周期度量起点） */
    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    /** 更新时间（用于流转与完成时间戳度量） */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public UUID getAssigneeId() { return assigneeId; }
    public UUID getProductId() { return productId; }
    public UUID getSprintId() { return sprintId; }
    public UUID getReleaseId() { return releaseId; }
    public String getStatus() { return status; }
    public String getType() { return type; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getEstimateHours() { return estimateHours; }
    public UUID getComponentId() { return componentId; }
    public BigDecimal getStoryPoints() { return storyPoints; }
    public String getSeverity() { return severity; }
    public java.time.Instant getCreatedAt() { return createdAt; }
    public java.time.Instant getUpdatedAt() { return updatedAt; }

    /**
     * 测试或数据构造工厂方法
     */
    public static InsightWorkItem of(UUID id, String key, String type, String status, UUID productId, UUID sprintId,
                                    BigDecimal storyPoints, String severity, java.time.Instant createdAt, java.time.Instant updatedAt) {
        InsightWorkItem item = new InsightWorkItem();
        item.id = id;
        item.key = key;
        item.type = type;
        item.status = status;
        item.productId = productId;
        item.sprintId = sprintId;
        item.storyPoints = storyPoints;
        item.severity = severity;
        item.createdAt = createdAt;
        item.updatedAt = updatedAt;
        return item;
    }
}
