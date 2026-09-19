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
import java.util.UUID;

/**
 * 基线实体（eng.baseline 表映射；FR-v2-13 / R9 / U10；M2-INC-3）。
 *
 * <p>遵循 eng 域零 prd 依赖纪律：仅以 UUID 逻辑引用创建人与审批人。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Entity
@Table(name = "baseline", schema = "eng")
public class Baseline {

    /**
     * 基线唯一主键 UUID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 所属代码仓库 ID。
     */
    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    /**
     * 基线名称（如 "V1.0.0-GA 正式发布基线"）。
     */
    @Column(nullable = false)
    private String name;

    /**
     * 基线类型：
     * <ul>
     *   <li><code>functional</code> - 功能基线（对应系统分析与需求规格冻结）</li>
     *   <li><code>allocated</code> - 分配基线（对应架构设计与模块分配）</li>
     *   <li><code>product</code> - 产品基线（对应验收发布版本）</li>
     * </ul>
     */
    @Column(nullable = false)
    private String type;

    /**
     * Git 对应标签名称（如 "v1.0.0"、"baseline-202609-01"）。
     */
    @Column(name = "tag_ref", nullable = false)
    private String tagRef;

    /**
     * 基线固化时锁定的 Git 提交 40 位完整 SHA。
     */
    @Column(name = "commit_sha")
    private String commitSha;

    /**
     * 产物包版本号（如 Maven/Npm 版本 "1.0.0"）。
     */
    @Column(name = "artifact_version")
    private String artifactVersion;

    /**
     * 关联的需求快照或跟踪条目 ID。
     */
    @Column(name = "requirement_snapshot_id")
    private String requirementSnapshotId;

    /**
     * 基线状态：
     * <ul>
     *   <li><code>draft</code> - 草稿（初始状态）</li>
     *   <li><code>in_review</code> - 评审中（已提交审批流）</li>
     *   <li><code>approved</code> - 已定版冻结（达到双人审批，Git 打 Tag 固化，不可变）</li>
     *   <li><code>superseded</code> - 已废止（已被后继更高版本基线替代）</li>
     * </ul>
     */
    @Column(nullable = false)
    private String status = "draft";

    /**
     * 替代此基线的新基线 ID（当状态为 superseded 时记录指向）。
     */
    @Column(name = "superseded_by_id")
    private UUID supersededById;

    /**
     * 审批同意此基线的用户 ID 列表（采用 JSONB 存储，需达到 2 人方可自动定版）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approver_ids", columnDefinition = "jsonb", nullable = false)
    private List<UUID> approverIds = new ArrayList<>();

    /**
     * 基线创建人用户 ID。
     */
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    /**
     * 基线创建时间。
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * 基线正式定版冻结时间（由第二个审批人批准后触发打 Tag 并记录）。
     */
    @Column(name = "approved_at")
    private Instant approvedAt;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTagRef() {
        return tagRef;
    }

    public void setTagRef(String tagRef) {
        this.tagRef = tagRef;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getArtifactVersion() {
        return artifactVersion;
    }

    public void setArtifactVersion(String artifactVersion) {
        this.artifactVersion = artifactVersion;
    }

    public String getRequirementSnapshotId() {
        return requirementSnapshotId;
    }

    public void setRequirementSnapshotId(String requirementSnapshotId) {
        this.requirementSnapshotId = requirementSnapshotId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getSupersededById() {
        return supersededById;
    }

    public void setSupersededById(UUID supersededById) {
        this.supersededById = supersededById;
    }

    public List<UUID> getApproverIds() {
        return approverIds;
    }

    public void setApproverIds(List<UUID> approverIds) {
        this.approverIds = approverIds != null ? approverIds : new ArrayList<>();
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }
}
