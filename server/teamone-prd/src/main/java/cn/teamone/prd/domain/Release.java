package cn.teamone.prd.domain;

import cn.teamone.platform.infra.AuditableListener;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 版本发布（门禁字段即状态，05 架构文档 §2.4）。
 *
 * <p>表 prd.release。状态五值：planned/coding/blocked/code_freeze/released——
 * 无 testing（06 §0 决策 A5）。publish 门禁事务见 05 §6.1（②实现）。</p>
 */
@Entity
@EntityListeners(AuditableListener.class)
@Table(name = "release", schema = "prd")
public class Release {

    // ---------- status（CHECK: 'planned','coding','blocked','code_freeze','released'，五值无 testing） ----------
    public static final String STATUS_PLANNED = "planned";
    public static final String STATUS_CODING = "coding";
    public static final String STATUS_BLOCKED = "blocked";
    public static final String STATUS_CODE_FREEZE = "code_freeze";
    public static final String STATUS_RELEASED = "released";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 版本键，如 v2.4.0 */
    @Column(name = "key", nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "roadmap_item_id")
    private UUID roadmapItemId;

    @Column(nullable = false, columnDefinition = "text")
    private String status = STATUS_PLANNED;

    @Column(name = "plan_date")
    private LocalDate planDate;

    @Column(name = "code_freeze_date")
    private LocalDate codeFreezeDate;

    /** 门禁：致命/严重缺陷未关闭 */
    @Column(nullable = false)
    private boolean blocked = false;

    /** 冗余阻塞清单（UI 直读，真相见 work_item） */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "blocked_defect_ids", nullable = false, columnDefinition = "uuid[]")
    private List<UUID> blockedDefectIds = new ArrayList<>();

    /** 环境进度 jsonb，如 {"dev":"passed","staging":"pending"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env_progress", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> envProgress = Map.of();

    /** 定版基线（eng.baseline 逻辑引用） */
    @Column(name = "baseline_id")
    private UUID baselineId;

    @Column(name = "released_at")
    private Instant releasedAt;

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
    public UUID getProductId() { return productId; }
    public void setProductId(UUID v) { this.productId = v; }
    public UUID getRoadmapItemId() { return roadmapItemId; }
    public void setRoadmapItemId(UUID v) { this.roadmapItemId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDate getPlanDate() { return planDate; }
    public void setPlanDate(LocalDate v) { this.planDate = v; }
    public LocalDate getCodeFreezeDate() { return codeFreezeDate; }
    public void setCodeFreezeDate(LocalDate v) { this.codeFreezeDate = v; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean v) { this.blocked = v; }
    public List<UUID> getBlockedDefectIds() { return blockedDefectIds; }
    public void setBlockedDefectIds(List<UUID> v) { this.blockedDefectIds = v; }
    public Map<String, Object> getEnvProgress() { return envProgress; }
    public void setEnvProgress(Map<String, Object> v) { this.envProgress = v; }
    public UUID getBaselineId() { return baselineId; }
    public void setBaselineId(UUID v) { this.baselineId = v; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant v) { this.releasedAt = v; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
