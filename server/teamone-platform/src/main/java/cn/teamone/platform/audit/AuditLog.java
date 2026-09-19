package cn.teamone.platform.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志（append-only）。表 audit.audit_log——M1 为普通表，按月分区 M2 再落地（表注释已注明）。
 * 必审计项（05 §7）：登录成功/失败、ACL 变更、发布、合并、基线、豁免。
 */
@Entity
@Table(name = "audit_log", schema = "audit")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_id")
    private UUID actorId;

    /** 动作，如 login.success / login.failure / acl.changed / release.publish */
    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID v) { this.actorId = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String v) { this.resourceType = v; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String v) { this.resourceId = v; }
    public Map<String, Object> getDetail() { return detail; }
    public void setDetail(Map<String, Object> v) { this.detail = v; }
    public Instant getCreatedAt() { return createdAt; }
}
