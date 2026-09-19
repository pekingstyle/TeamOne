package cn.teamone.prd.domain;

import cn.teamone.platform.infra.AuditableListener;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** RoadMap 条目（goal/release 关联链，05 架构文档 §2.3）。表 prd.roadmap_item。M1 只建表不填种子（06 §3 W2）。 */
@Entity
@EntityListeners(AuditableListener.class)
@Table(name = "roadmap_item", schema = "prd")
public class RoadmapItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "product_id")
    private UUID productId;

    /** 关联链：条目 → 目标 */
    @Column(name = "goal_id")
    private UUID goalId;

    /** 关联链：条目 → 版本（DDL 上以 ALTER 后置外键落地，规避与 release 的建表环） */
    @Column(name = "release_id")
    private UUID releaseId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID v) { this.productId = v; }
    public UUID getGoalId() { return goalId; }
    public void setGoalId(UUID v) { this.goalId = v; }
    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID v) { this.releaseId = v; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
