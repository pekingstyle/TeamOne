package cn.teamone.eng.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流水线运行视图实体（eng.pipeline_run 表映射；07 §2.4 / 05 §2.3 / M2-INC-3 V-16）。
 *
 * <p>遵循 eng 域架构守恒：仅以 UUID 逻辑引用用户与 MR。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Entity
@Table(name = "pipeline_run", schema = "eng")
public class PipelineRun {

    /**
     * 流水线主键 UUID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 所属代码仓库 ID（逻辑外键映射 eng.repository.id）。
     */
    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    /**
     * 流水线展示标题（例如 "#102 · main CI 构建与测试门禁"）。
     */
    @Column(nullable = false)
    private String title;

    /**
     * 运行的目标 Git 分支名称（例如 "main"、"feature/demo"）。
     */
    @Column(nullable = false)
    private String branch;

    /**
     * 触发本次执行的完整 40 位 Git 提交 SHA。
     */
    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    /**
     * Git 提交短 SHA（前 7 位）。
     */
    @Column(name = "commit_short", nullable = false)
    private String commitShort;

    /**
     * 触发来源类型：push（代码推送）、manual（手动运行）、mr（合并请求门禁）、schedule（定时调度）。
     */
    @Column(nullable = false)
    private String trigger = "push";

    /**
     * 关联的合并请求 ID（若该流水线是为指定 MR 进行代码门禁验证而触发）。
     */
    @Column(name = "mr_id")
    private UUID mrId;

    /**
     * 关联的交付版本 ID（V17 加列 · R-9 发布一致性；逻辑引用 prd.release.id 不建外键。
     * 本批写入点仅部署登记回填；按 release 触发流水线的自动回填属 M4/M5 联动）。
     */
    @Column(name = "release_id")
    private UUID releaseId;

    /**
     * 流水线综合状态：pending（排队中）、running（执行中）、passed（成功）、failed（失败）、canceled（取消）、skipped（跳过）。
     */
    @Column(nullable = false)
    private String status = "pending";

    /**
     * 触发者用户 ID（逻辑外键映射 platform.app_user.id）。
     */
    @Column(name = "trigger_user_id")
    private UUID triggerUserId;

    /**
     * 流水线实际执行耗时（秒）。
     */
    @Column(name = "duration_sec", nullable = false)
    private int durationSec = 0;

    /**
     * 阶段轨道与各任务控制台日志明细，以 JSONB 格式存储。
     * 包含 Stage 名称、状态、耗时及 Job 清单与实时日志行。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> stages = new ArrayList<>();

    /**
     * 流水线开始执行时间戳。
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * 流水线执行结束时间戳。
     */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * 记录创建时间戳（不可变）。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * 记录最后更新时间戳。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PipelineRun() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRepoId() {
        return repoId;
    }

    public void setRepoId(UUID repoId) {
        this.repoId = repoId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getCommitShort() {
        return commitShort;
    }

    public void setCommitShort(String commitShort) {
        this.commitShort = commitShort;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public UUID getMrId() {
        return mrId;
    }

    public void setMrId(UUID mrId) {
        this.mrId = mrId;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(UUID releaseId) {
        this.releaseId = releaseId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getTriggerUserId() {
        return triggerUserId;
    }

    public void setTriggerUserId(UUID triggerUserId) {
        this.triggerUserId = triggerUserId;
    }

    public int getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(int durationSec) {
        this.durationSec = durationSec;
    }

    public List<Map<String, Object>> getStages() {
        return stages;
    }

    public void setStages(List<Map<String, Object>> stages) {
        this.stages = (stages != null) ? stages : new ArrayList<>();
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
