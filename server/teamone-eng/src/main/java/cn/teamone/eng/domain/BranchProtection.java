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
 * 分支保护规则实体（eng.branch_protection 表映射；FR-v2-14 / U8 / ProtectionPolicy；M2-INC-3）。
 *
 * @author Ivan Yang, 2026-09-13
 */
@Entity
@Table(name = "branch_protection", schema = "eng")
public class BranchProtection {

    /**
     * 分支保护规则唯一主键 UUID。
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
     * 目标分支匹配模式表达式（支持精确名称如 "main" 或通配符如 "release/*"、"feature/*"）。
     */
    @Column(name = "branch_pattern", nullable = false)
    private String branchPattern;

    /**
     * 是否强制要求通过合并请求（MR）进行代码合入，禁止直接 push 到保护分支。
     */
    @Column(name = "require_mr", nullable = false)
    private boolean requireMr = true;

    /**
     * MR 合入该分支前所需达到的最少独立评审批准（Approve）人数。
     */
    @Column(name = "min_approvals", nullable = false)
    private int minApprovals = 1;

    /**
     * 是否要求单元测试及自动化 CI 门禁通过（或管理员特权豁免）。
     */
    @Column(name = "require_unit_test", nullable = false)
    private boolean requireUnitTest = true;

    /**
     * 是否阻断强推（git push --force）破坏版本提交历史。
     */
    @Column(name = "block_force_push", nullable = false)
    private boolean blockForcePush = true;

    /**
     * 保护规则创建时间。
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * 保护规则最后更新时间。
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

    public String getBranchPattern() {
        return branchPattern;
    }

    public void setBranchPattern(String branchPattern) {
        this.branchPattern = branchPattern;
    }

    public boolean isRequireMr() {
        return requireMr;
    }

    public void setRequireMr(boolean requireMr) {
        this.requireMr = requireMr;
    }

    public int getMinApprovals() {
        return minApprovals;
    }

    public void setMinApprovals(int minApprovals) {
        this.minApprovals = minApprovals;
    }

    public boolean isRequireUnitTest() {
        return requireUnitTest;
    }

    public void setRequireUnitTest(boolean requireUnitTest) {
        this.requireUnitTest = requireUnitTest;
    }

    public boolean isBlockForcePush() {
        return blockForcePush;
    }

    public void setBlockForcePush(boolean blockForcePush) {
        this.blockForcePush = blockForcePush;
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
