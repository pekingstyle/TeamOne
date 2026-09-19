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
 * 提交↔工作项映射（05 §2.3 / 07 §2.2：post-receive hook 解析 refs #KEY 的落点）。
 *
 * <p><b>eng 零 prd 依赖</b>：work_item_key 文本化（如 D-88），不建 prd.work_item 外键、
 * 不校验存在性（未命中的 refs 静默跳过，事件消费方自行取舍）。
 * 幂等：UNIQUE(repo_key, commit_sha, work_item_key) + ON CONFLICT DO NOTHING。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
@Entity
@Table(name = "commit_work_item", schema = "eng")
public class CommitWorkItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repo_key", nullable = false, columnDefinition = "text")
    private String repoKey;

    @Column(name = "commit_sha", nullable = false, columnDefinition = "text")
    private String commitSha;

    @Column(name = "author_name")
    private String authorName;

    @Column(name = "author_email")
    private String authorEmail;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(columnDefinition = "text")
    private String subject;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "work_item_key", nullable = false, columnDefinition = "text")
    private String workItemKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getRepoKey() { return repoKey; }
    public void setRepoKey(String v) { this.repoKey = v; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String v) { this.commitSha = v; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String v) { this.authorName = v; }
    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String v) { this.authorEmail = v; }
    public Instant getCommittedAt() { return committedAt; }
    public void setCommittedAt(Instant v) { this.committedAt = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public String getWorkItemKey() { return workItemKey; }
    public void setWorkItemKey(String v) { this.workItemKey = v; }
    public Instant getCreatedAt() { return createdAt; }
}
