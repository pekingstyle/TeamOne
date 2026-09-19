package cn.teamone.prd.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 工作项通用关联（发现于/关联/修复/阻塞，05 架构文档 §2.3）。表 prd.work_item_link。 */
@Entity
@Table(name = "work_item_link", schema = "prd")
public class WorkItemLink {

    // ---------- relation（CHECK: 'discovered_in','relates_to','fixed_by','blocks'） ----------
    public static final String RELATION_DISCOVERED_IN = "discovered_in"; // 发现于
    public static final String RELATION_RELATES_TO = "relates_to";       // 关联
    public static final String RELATION_FIXED_BY = "fixed_by";           // 修复
    public static final String RELATION_BLOCKS = "blocks";               // 阻塞

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "from_item_id", nullable = false)
    private UUID fromItemId;

    @Column(name = "to_item_id", nullable = false)
    private UUID toItemId;

    @Column(nullable = false, columnDefinition = "text")
    private String relation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getFromItemId() { return fromItemId; }
    public void setFromItemId(UUID v) { this.fromItemId = v; }
    public UUID getToItemId() { return toItemId; }
    public void setToItemId(UUID v) { this.toItemId = v; }
    public String getRelation() { return relation; }
    public void setRelation(String v) { this.relation = v; }
    public Instant getCreatedAt() { return createdAt; }
}
