package cn.teamone.prd.domain;

import cn.teamone.platform.infra.AuditableListener;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 迭代（product_id、release_id、capacity_hours、date 界，05 架构文档 §2.3）。表 prd.sprint。 */
@Entity
@EntityListeners(AuditableListener.class)
@Table(name = "sprint", schema = "prd")
public class Sprint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "release_id")
    private UUID releaseId;

    @Column(name = "capacity_hours")
    private Integer capacityHours;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 完成标记（05 §6.1 迭代完成：complete 端点写入；NULL=进行中。V7 加列） */
    @Column(name = "completed_at")
    private Instant completedAt;

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
    public UUID getProductId() { return productId; }
    public void setProductId(UUID v) { this.productId = v; }
    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID v) { this.releaseId = v; }
    public Integer getCapacityHours() { return capacityHours; }
    public void setCapacityHours(Integer v) { this.capacityHours = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
