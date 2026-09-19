package cn.teamone.prd.domain;

import cn.teamone.platform.infra.AuditableListener;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 单表工作项（task/test_task/defect/requirement 四类型，05 架构文档 §2.4）。
 *
 * <p>表 prd.work_item。状态机双保险：DB 侧类型感知复合 CHECK（ck_work_item_type_status）+
 * 应用侧转移表（②的 transition 接口，唯一入口）。枚举值与 CHECK 字节级一致——
 * CHECK 为小写英文/中文，故用字符串常量而非 Java enum（enum 名会生成大写英文）。</p>
 */
@Entity
@EntityListeners(AuditableListener.class)
@Table(name = "work_item", schema = "prd")
public class WorkItem {

    // ---------- type（CHECK: type IN ('requirement','task','test_task','defect')） ----------
    public static final String TYPE_REQUIREMENT = "requirement";
    public static final String TYPE_TASK = "task";
    public static final String TYPE_TEST_TASK = "test_task";
    public static final String TYPE_DEFECT = "defect";

    // ---------- task 状态（CHECK: 'todo','in_progress','done'） ----------
    public static final String STATUS_TODO = "todo";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_DONE = "done";

    // ---------- test_task 状态（CHECK: 'pending','in_progress','passed','failed'） ----------
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PASSED = "passed";
    public static final String STATUS_FAILED = "failed";

    // ---------- defect 状态（CHECK: '新建','修复中','已修复','回归通过','已关闭','重新打开'；无 rejected） ----------
    public static final String STATUS_DEFECT_NEW = "新建";
    public static final String STATUS_DEFECT_FIXING = "修复中";
    public static final String STATUS_DEFECT_FIXED = "已修复";
    public static final String STATUS_DEFECT_REGRESSION_PASSED = "回归通过";
    public static final String STATUS_DEFECT_CLOSED = "已关闭";
    public static final String STATUS_DEFECT_REOPENED = "重新打开";

    // ---------- requirement 状态（CHECK: 'draft','pending_review','accepted','in_dev','delivered','closed'；驳回回 draft） ----------
    public static final String STATUS_REQ_DRAFT = "draft";
    public static final String STATUS_REQ_PENDING_REVIEW = "pending_review";
    public static final String STATUS_REQ_ACCEPTED = "accepted";
    public static final String STATUS_REQ_IN_DEV = "in_dev";
    public static final String STATUS_REQ_DELIVERED = "delivered";
    public static final String STATUS_REQ_CLOSED = "closed";

    // ---------- priority（CHECK: 'P0','P1','P2','P3'） ----------
    public static final String PRIORITY_P0 = "P0";
    public static final String PRIORITY_P1 = "P1";
    public static final String PRIORITY_P2 = "P2";
    public static final String PRIORITY_P3 = "P3";

    // ---------- severity（CHECK: '致命','严重','一般','轻微'，四级） ----------
    public static final String SEVERITY_FATAL = "致命";
    public static final String SEVERITY_CRITICAL = "严重";
    public static final String SEVERITY_MAJOR = "一般";
    public static final String SEVERITY_MINOR = "轻微";

    // ---------- R-9 发布一致性：类型感知「完结集」公共口径 ----------
    // 与 insight EfficiencyReportService.isDone 字节级同源（跨域不可编译依赖，ArchUnit R3/R6，
    // 双侧以常量对齐 + 注释互指维持一致；改动此处必须同步 insight isDone，反之亦然）。

    /** task/缺省终态辅助值（task CHECK 只允许 todo/in_progress/done，closed 仅为与 insight 缺省分支对齐保留） */
    public static final String STATUS_CLOSED = "closed";

    /**
     * 类型感知完结集（状态 ∈ 集合 = 已完结）：
     * task=done；test_task=passed；defect=已修复/回归通过/已关闭；requirement=delivered/closed；
     * 缺省（task/未知）=done/closed——与 insight isDone 口径一致。
     */
    public static List<String> doneStatusesOf(String type) {
        if (TYPE_DEFECT.equals(type)) {
            return List.of(STATUS_DEFECT_FIXED, STATUS_DEFECT_REGRESSION_PASSED, STATUS_DEFECT_CLOSED);
        }
        if (TYPE_TEST_TASK.equals(type)) {
            return List.of(STATUS_PASSED);
        }
        if (TYPE_REQUIREMENT.equals(type)) {
            return List.of(STATUS_REQ_DELIVERED, STATUS_REQ_CLOSED);
        }
        return List.of(STATUS_DONE, STATUS_CLOSED);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 业务键，如 D-88 / T-12 / TT-9 / REQ-1（prd.key_sequence 行锁发号） */
    @Column(name = "key", nullable = false, unique = true)
    private String key;

    @Column(nullable = false, columnDefinition = "text")
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(columnDefinition = "text")
    private String priority;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "component_id")
    private UUID componentId;

    @Column(name = "parent_id")
    private UUID parentId;

    /** 物化路径 '/g1/rq3/w12/'，LIKE 前缀查子树（前缀索引 idx_work_item_path） */
    @Column(nullable = false, columnDefinition = "text")
    private String path;

    @Column(name = "sprint_id")
    private UUID sprintId;

    /** 交付版本 */
    @Column(name = "release_id")
    private UUID releaseId;

    @Column(name = "roadmap_item_id")
    private UUID roadmapItemId;

    /** 需求直挂目标（R2） */
    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "story_points", precision = 5, scale = 1)
    private BigDecimal storyPoints;

    /** 预估工时（V8 加列，numeric(6,1)；红线④：estimate_hours 只做负载摊销，story_points 只做进度） */
    @Column(name = "estimate_hours", precision = 6, scale = 1)
    private BigDecimal estimateHours;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** jsonb 标签数组，默认 '[]'（Hibernate 以原始 JSON 文档读写字符串） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String labels = "[]";

    // ---------- 类型专用列（按 type 启用，ck_work_item_type_severity 保证） ----------

    @Column(columnDefinition = "text")
    private String severity;

    /** 缺陷阻塞的版本（门禁真相；部分索引 idx_work_item_gate 命中） */
    @Column(name = "blocked_release_id")
    private UUID blockedReleaseId;

    /** 缺陷发现于测试任务 */
    @Column(name = "found_in_id")
    private UUID foundInId;

    /** 关联工作项 */
    @Column(name = "related_id")
    private UUID relatedId;

    /** 修复 MR（eng.mr_review 逻辑引用） */
    @Column(name = "fixed_mr_id")
    private UUID fixedMrId;

    /** 任务/测试任务挂需求（defect 恒为 NULL，CHECK 保证） */
    @Column(name = "requirement_id")
    private UUID requirementId;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public void setKey(String v) { this.key = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getPriority() { return priority; }
    public void setPriority(String v) { this.priority = v; }
    public UUID getAssigneeId() { return assigneeId; }
    public void setAssigneeId(UUID v) { this.assigneeId = v; }
    public UUID getReporterId() { return reporterId; }
    public void setReporterId(UUID v) { this.reporterId = v; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID v) { this.productId = v; }
    public UUID getComponentId() { return componentId; }
    public void setComponentId(UUID v) { this.componentId = v; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID v) { this.parentId = v; }
    public String getPath() { return path; }
    public void setPath(String v) { this.path = v; }
    public UUID getSprintId() { return sprintId; }
    public void setSprintId(UUID v) { this.sprintId = v; }
    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID v) { this.releaseId = v; }
    public UUID getRoadmapItemId() { return roadmapItemId; }
    public void setRoadmapItemId(UUID v) { this.roadmapItemId = v; }
    public UUID getGoalId() { return goalId; }
    public void setGoalId(UUID v) { this.goalId = v; }
    public BigDecimal getStoryPoints() { return storyPoints; }
    public void setStoryPoints(BigDecimal v) { this.storyPoints = v; }
    public BigDecimal getEstimateHours() { return estimateHours; }
    public void setEstimateHours(BigDecimal v) { this.estimateHours = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public String getLabels() { return labels; }
    public void setLabels(String v) { this.labels = v; }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { this.severity = v; }
    public UUID getBlockedReleaseId() { return blockedReleaseId; }
    public void setBlockedReleaseId(UUID v) { this.blockedReleaseId = v; }
    public UUID getFoundInId() { return foundInId; }
    public void setFoundInId(UUID v) { this.foundInId = v; }
    public UUID getRelatedId() { return relatedId; }
    public void setRelatedId(UUID v) { this.relatedId = v; }
    public UUID getFixedMrId() { return fixedMrId; }
    public void setFixedMrId(UUID v) { this.fixedMrId = v; }
    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID v) { this.requirementId = v; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
