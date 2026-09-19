package cn.teamone.prd.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 需求评审轮次纪要（R-6/D2，12-产品化体验 §1）。表 prd.review_round（V18）。
 *
 * <p>会签真相仍在 {@link RequirementReview}（round 内逐人 result）；本表按
 * (requirement_id, round) 唯一稀疏随行，只落派生事实：纪要文件引用（platform.file 两步制）、
 * 结论摘要、结论时刻（任一 rejected 回 draft / 全员 approved 受理时回写，评审中 NULL）。</p>
 */
@Entity
@Table(name = "review_round", schema = "prd",
       uniqueConstraints = @UniqueConstraint(name = "review_round_requirement_id_round_key",
               columnNames = {"requirement_id", "round"}))
public class ReviewRound {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    /** 评审轮次：submit 时 +1，与 requirement_review.round 同轴 */
    @Column(nullable = false)
    private int round;

    /** 纪要文件（platform.file 两步制：先 presign/complete 拿 fileId 再挂载；可空=未上传） */
    @Column(name = "minutes_file_id")
    private UUID minutesFileId;

    /** 轮次结论摘要（可随 PUT 补充） */
    @Column(columnDefinition = "text")
    private String summary;

    /** 轮次结论时刻（会签出结论时回写；评审中 NULL） */
    @Column(name = "concluded_at")
    private Instant concludedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID v) { this.requirementId = v; }
    public int getRound() { return round; }
    public void setRound(int v) { this.round = v; }
    public UUID getMinutesFileId() { return minutesFileId; }
    public void setMinutesFileId(UUID v) { this.minutesFileId = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public Instant getConcludedAt() { return concludedAt; }
    public void setConcludedAt(Instant v) { this.concludedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
