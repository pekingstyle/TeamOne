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
 * MR 评审单实体（eng.mr_review 表映射；M2-INC-3 U6/U7）。
 *
 * <p><b>eng 零 prd 依赖</b>：author_id、merged_by_id、linked_work_item_key 仅为逻辑引用，不建外键约束。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Entity
@Table(name = "mr_review", schema = "eng")
public class MergeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    @Column(name = "mr_number", nullable = false)
    private Integer mrNumber;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "source_branch", nullable = false)
    private String sourceBranch;

    @Column(name = "target_branch", nullable = false)
    private String targetBranch;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false)
    private String status = "open"; // draft, open, merged, closed

    @Column(name = "merge_commit_sha")
    private String mergeCommitSha;

    @Column(name = "merged_by_id")
    private UUID mergedById;

    @Column(name = "merged_at")
    private Instant mergedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "linked_work_item_key")
    private String linkedWorkItemKey;

    @Column(name = "based_on_baseline_id")
    private UUID basedOnBaselineId;

    @Column(name = "rebase_required", nullable = false)
    private boolean rebaseRequired = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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

    public Integer getMrNumber() {
        return mrNumber;
    }

    public void setMrNumber(Integer mrNumber) {
        this.mrNumber = mrNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public void setSourceBranch(String sourceBranch) {
        this.sourceBranch = sourceBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public void setTargetBranch(String targetBranch) {
        this.targetBranch = targetBranch;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMergeCommitSha() {
        return mergeCommitSha;
    }

    public void setMergeCommitSha(String mergeCommitSha) {
        this.mergeCommitSha = mergeCommitSha;
    }

    public UUID getMergedById() {
        return mergedById;
    }

    public void setMergedById(UUID mergedById) {
        this.mergedById = mergedById;
    }

    public Instant getMergedAt() {
        return mergedAt;
    }

    public void setMergedAt(Instant mergedAt) {
        this.mergedAt = mergedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getLinkedWorkItemKey() {
        return linkedWorkItemKey;
    }

    public void setLinkedWorkItemKey(String linkedWorkItemKey) {
        this.linkedWorkItemKey = linkedWorkItemKey;
    }

    public UUID getBasedOnBaselineId() {
        return basedOnBaselineId;
    }

    public void setBasedOnBaselineId(UUID basedOnBaselineId) {
        this.basedOnBaselineId = basedOnBaselineId;
    }

    public boolean isRebaseRequired() {
        return rebaseRequired;
    }

    public void setRebaseRequired(boolean rebaseRequired) {
        this.rebaseRequired = rebaseRequired;
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
