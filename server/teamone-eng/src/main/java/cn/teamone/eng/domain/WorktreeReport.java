package cn.teamone.eng.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作副本上报实体（eng.worktree_report 表映射；FR-v2-10 / R7 / 05 §2.3 / M2-INC-3 V-16）。
 *
 * <p>遵循 eng 域架构守恒：仅以 UUID 逻辑引用上报人。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Entity
@Table(name = "worktree_report", schema = "eng")
public class WorktreeReport {

    /**
     * 工作副本上报主键 UUID。
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
     * 开发者工作站上的本地物理路径（如 "C:/Users/afire/Workspace/TeamOne"）。
     * 与 repoId 组成联合唯一约束，保证同机器同路径心跳幂等更新。
     */
    @Column(name = "local_path", nullable = false)
    private String localPath;

    /**
     * 该工作副本当前检出的 Git 分支名（如 "feature/task-42"）。
     */
    @Column(name = "branch_name", nullable = false)
    private String branchName;

    /**
     * 分叉基准分支或基准标签（默认为 "main"），拓扑关系树图依此连线。
     */
    @Column(name = "base_ref", nullable = false)
    private String baseRef = "main";

    /**
     * 工作副本所属开发人员的用户 UUID（用于展示归属者头像与团队专属色彩高亮）。
     */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    /**
     * 本地未暂存/未提交的脏文件数（dirty files count）；若 >0 标记橙色预警。
     */
    @Column(name = "dirty_file_count", nullable = false)
    private int dirtyFileCount = 0;

    /**
     * 领先主干基准的提交数（+ahead）；即本地已提交但未合并到主干的提交数。
     */
    @Column(name = "ahead_count", nullable = false)
    private int aheadCount = 0;

    /**
     * 落后主干基准的提交数（-behind）；若 >0 提示落后并建议 rebase 主干。
     */
    @Column(name = "behind_count", nullable = false)
    private int behindCount = 0;

    /**
     * 工作副本健康状态：active（活跃开发）、merged（分支已合入）、stale（长期无心跳/停滞）。
     */
    @Column(nullable = false)
    private String status = "active";

    /**
     * 本地工作副本最新 HEAD 指向的 Git 提交 SHA。
     */
    @Column(name = "last_commit_sha")
    private String lastCommitSha;

    /**
     * 本地工作副本最新提交产生的时间戳。
     */
    @Column(name = "last_commit_at")
    private Instant lastCommitAt;

    /**
     * 客户端最后一次心跳活跃上报时间戳。
     */
    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt = Instant.now();

    /**
     * 首次建档时间戳（不可变）。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * 记录更新时间戳。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public WorktreeReport() {}

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

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getBaseRef() {
        return baseRef;
    }

    public void setBaseRef(String baseRef) {
        this.baseRef = baseRef;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public int getDirtyFileCount() {
        return dirtyFileCount;
    }

    public void setDirtyFileCount(int dirtyFileCount) {
        this.dirtyFileCount = dirtyFileCount;
    }

    public int getAheadCount() {
        return aheadCount;
    }

    public void setAheadCount(int aheadCount) {
        this.aheadCount = aheadCount;
    }

    public int getBehindCount() {
        return behindCount;
    }

    public void setBehindCount(int behindCount) {
        this.behindCount = behindCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastCommitSha() {
        return lastCommitSha;
    }

    public void setLastCommitSha(String lastCommitSha) {
        this.lastCommitSha = lastCommitSha;
    }

    public Instant getLastCommitAt() {
        return lastCommitAt;
    }

    public void setLastCommitAt(Instant lastCommitAt) {
        this.lastCommitAt = lastCommitAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
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
