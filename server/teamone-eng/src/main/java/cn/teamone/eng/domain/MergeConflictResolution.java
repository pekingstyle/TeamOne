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
 * MR 冲突解决留痕实体（eng.mr_conflict_resolution 表映射；M2-INC-3 U7）。
 *
 * @author Ivan Yang, 2026-09-13
 */
@Entity
@Table(name = "mr_conflict_resolution", schema = "eng")
public class MergeConflictResolution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "mr_id", nullable = false)
    private UUID mrId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(nullable = false, columnDefinition = "text")
    private String solution;

    @Column(name = "confirmed_by_id")
    private UUID confirmedById;

    @Column(name = "reviewed_by_id")
    private UUID reviewedById;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMrId() {
        return mrId;
    }

    public void setMrId(UUID mrId) {
        this.mrId = mrId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getSolution() {
        return solution;
    }

    public void setSolution(String solution) {
        this.solution = solution;
    }

    public UUID getConfirmedById() {
        return confirmedById;
    }

    public void setConfirmedById(UUID confirmedById) {
        this.confirmedById = confirmedById;
    }

    public UUID getReviewedById() {
        return reviewedById;
    }

    public void setReviewedById(UUID reviewedById) {
        this.reviewedById = reviewedById;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
