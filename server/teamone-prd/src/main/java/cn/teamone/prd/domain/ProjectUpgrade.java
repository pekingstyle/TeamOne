package cn.teamone.prd.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 项目升级记录（⑥r · docs/v2/16 §1）：release → project/* 升级 MR 的意图与状态。
 *
 * <p>mr_id 为 eng.mr_review 逻辑引用（跨 schema 不建 FK——镜像 eng 零 prd 依赖的对称纪律）；
 * status=requested 时由查询侧惰性同步 MR 实际状态（merged/closed）。</p>
 *
 * @author Ivan Yang, 2026-09-28
 */
@Entity
@Table(name = "project_upgrade", schema = "prd")
@EntityListeners(AuditingEntityListener.class)
public class ProjectUpgrade {

    public static final String STATUS_REQUESTED = "requested";
    public static final String STATUS_MERGED = "merged";
    public static final String STATUS_CLOSED = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "mr_id", nullable = false)
    private UUID mrId;

    @Column(name = "from_ref", nullable = false)
    private String fromRef;

    @Column(name = "status", nullable = false)
    private String status = STATUS_REQUESTED;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID v) { this.projectId = v; }
    public UUID getMrId() { return mrId; }
    public void setMrId(UUID v) { this.mrId = v; }
    public String getFromRef() { return fromRef; }
    public void setFromRef(String v) { this.fromRef = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
