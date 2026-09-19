package cn.teamone.platform.infra;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** REST 幂等快照（Idempotency-Key → 响应快照 24h，05 架构文档 §3.1）。表 infra.idempotency_record。 */
@Entity
@Table(name = "idempotency_record", schema = "infra")
public class IdempotencyRecord {

    /** 客户端 Idempotency-Key（表主键） */
    @Id
    private String key;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    /** jsonb 响应体快照（原始 JSON 文档字符串） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String getKey() { return key; }
    public void setKey(String v) { this.key = v; }
    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int v) { this.responseStatus = v; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String v) { this.responseBody = v; }
    public Instant getCreatedAt() { return createdAt; }
}
