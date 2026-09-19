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
 * 分支规则实体（eng.branch_rule 表映射；分支治理批：分支模型可配置 + MR 目标约束）。
 *
 * <p>每仓库每分支类型（main/develop/release/hotfix/feature/fix/poc/other）至多一条规则；
 * {@code namePattern} 为分支名 glob 模式（'*' 通配、忽略大小写），是建分支与 MR 目标
 * 两处生效点的匹配依据；受保护语义（最少批准/门禁/禁强推）仍由既有
 * branch_protection 承担，二者互补不重叠。</p>
 *
 * @author Ivan Yang, 2026-09-14
 */
@Entity
@Table(name = "branch_rule", schema = "eng")
public class BranchRule {

    /**
     * 分支规则唯一主键 UUID。
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
     * 分支类型（main/develop/release/hotfix/feature/fix/poc/other，每仓库唯一）。
     */
    @Column(name = "branch_type", nullable = false, length = 16)
    private String branchType;

    /**
     * 分支名 glob 模式（如 feature/*，'*' 通配任意字符，忽略大小写）。
     */
    @Column(name = "name_pattern", nullable = false, length = 200)
    private String namePattern;

    /**
     * 起源分支（如 feature/* 基于 develop；NULL=不限定起源）。
     */
    @Column(name = "base_branch")
    private String baseBranch;

    /**
     * 合入目标（如 release/* → main；NULL=不设固定目标；非 NULL 时 MR 创建强校验）。
     */
    @Column(name = "merge_target")
    private String mergeTarget;

    /**
     * 是否允许绕过 MR 直接推送（执行点在 git hook 侧）。
     */
    @Column(name = "allow_direct_push", nullable = false)
    private boolean allowDirectPush = false;

    /**
     * 合并后是否自动删除源分支（执行点在合并流程）。
     */
    @Column(name = "auto_delete_after_merge", nullable = false)
    private boolean autoDeleteAfterMerge = false;

    /**
     * 规则说明（如 hotfix 的「合回 main 并同步 develop」双合入语义）。
     */
    @Column(name = "description")
    private String description;

    /**
     * 规则创建时间。
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * 规则最后更新时间。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

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

    public String getBranchType() {
        return branchType;
    }

    public void setBranchType(String branchType) {
        this.branchType = branchType;
    }

    public String getNamePattern() {
        return namePattern;
    }

    public void setNamePattern(String namePattern) {
        this.namePattern = namePattern;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public String getMergeTarget() {
        return mergeTarget;
    }

    public void setMergeTarget(String mergeTarget) {
        this.mergeTarget = mergeTarget;
    }

    public boolean isAllowDirectPush() {
        return allowDirectPush;
    }

    public void setAllowDirectPush(boolean allowDirectPush) {
        this.allowDirectPush = allowDirectPush;
    }

    public boolean isAutoDeleteAfterMerge() {
        return autoDeleteAfterMerge;
    }

    public void setAutoDeleteAfterMerge(boolean autoDeleteAfterMerge) {
        this.autoDeleteAfterMerge = autoDeleteAfterMerge;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
