package cn.teamone.prd.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 需求评审记录（会签，05 架构文档 §2.4）。表 prd.requirement_review。
 *
 * <p>会签判定：round 内无 pending 且无 rejected → 全部通过；
 * 任一 rejected → 需求驳回回 draft（05 §6.1，requirement 状态无 rejected 值）。</p>
 */
@Entity
@Table(name = "requirement_review", schema = "prd",
       uniqueConstraints = @UniqueConstraint(name = "requirement_review_requirement_id_round_reviewer_id_key",
               columnNames = {"requirement_id", "round", "reviewer_id"}))
public class RequirementReview {

    // ---------- result（CHECK: 'pending','approved','rejected'） ----------
    public static final String RESULT_PENDING = "pending";
    public static final String RESULT_APPROVED = "approved";
    public static final String RESULT_REJECTED = "rejected";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    /** 评审轮次：submit 时 +1 并重置本轮（驳回后重提） */
    @Column(nullable = false)
    private int round = 1;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Column(nullable = false, columnDefinition = "text")
    private String result = RESULT_PENDING;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID v) { this.requirementId = v; }
    public int getRound() { return round; }
    public void setRound(int v) { this.round = v; }
    public UUID getReviewerId() { return reviewerId; }
    public void setReviewerId(UUID v) { this.reviewerId = v; }
    public String getResult() { return result; }
    public void setResult(String v) { this.result = v; }
    public String getComment() { return comment; }
    public void setComment(String v) { this.comment = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
