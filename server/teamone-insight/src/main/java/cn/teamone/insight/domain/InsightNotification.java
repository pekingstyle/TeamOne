package cn.teamone.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * collab.notification 写实体（红线①声明的唯一写映射例外，总监裁决；权威实体
 * cn.teamone.collab.domain.Notification——insight 禁 import collab，故按写所需列白名单重映射）。
 *
 * <p>列白名单：id, user_id, kind, payload, read_at, created_at。
 * 用途：conflict.detected 消费端落红色冲突站内通知（kind=conflict.red）。</p>
 */
@Entity
@Table(name = "notification", schema = "collab")
public class InsightNotification {

    /** 红色冲突新增通知（insight 侧约定 kind；collab 收件箱按 kind 渲染） */
    public static final String KIND_CONFLICT_RED = "conflict.red";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = Map.of();

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getUserId() { return userId; }
    public void setUserId(UUID v) { this.userId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> v) { this.payload = v; }
    public Instant getCreatedAt() { return createdAt; }
}
