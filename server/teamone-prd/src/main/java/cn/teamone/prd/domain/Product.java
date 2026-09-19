package cn.teamone.prd.domain;

import cn.teamone.platform.infra.AuditableListener;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 产品（层级引用列 + owner，05 架构文档 §2.3）。表 prd.product。key 兼作 bare repo 命名根（05 §6.3 {productKey}）。 */
@Entity
@EntityListeners(AuditableListener.class)
@Table(name = "product", schema = "prd")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** 挂载的战略目标（strategic_goal ──< product） */
    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public void setKey(String v) { this.key = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public UUID getGoalId() { return goalId; }
    public void setGoalId(UUID v) { this.goalId = v; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
