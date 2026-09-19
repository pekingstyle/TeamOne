package cn.teamone.platform.infra;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** 事务性发件箱（05 架构文档 §2.4 原样）。表 infra.outbox_event。 */
@Entity
@Table(name = "outbox_event", schema = "infra")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** 事件类型，如 defect.closed / release.published */
    @Column(nullable = false)
    private String type;

    /** jsonb 载荷（以原始 JSON 文档字符串读写） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    /** NULL=待投递（投递器扫描命中部分索引 idx_outbox_unpublished） */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String v) { this.aggregateType = v; }
    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID v) { this.aggregateId = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID v) { this.actorId = v; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int v) { this.retryCount = v; }
}
