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
 * 冲突快照写实体（prd.conflict_snapshot，V7 建表；INC-1 红线②：冲突真相只认本表）。
 *
 * <p>与 V7 DDL 对齐：kind CHECK(CF-1~6)、payload jsonb、detected_at 快照批时间、resolved_at 消解时间。
 * 写法=「删当日同 kind 旧行再插」（幂等不追加，红线⑥）；本域唯一常驻写表。</p>
 */
@Entity
@Table(name = "conflict_snapshot", schema = "prd")
public class ConflictSnapshot {

    public static final String SEVERITY_RED = "red";
    public static final String SEVERITY_YELLOW = "yellow";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** CF-1 人员超载 / CF-2 区间重叠 / CF-3 里程碑挤压 / CF-4 跨产品争用 / CF-5 依赖倒挂 / CF-6 Deadline越级 */
    @Column(nullable = false, columnDefinition = "text")
    private String kind;

    /** 明细：severity/subjectType/subjectId/userId/detail/relatedTaskIds/fp（指纹，红色新增判定） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = Map.of();

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt = Instant.now();

    /** 复检不再命中时回填（写法重写当日行，历史行消解由后续演进处理） */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public UUID getId() { return id; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> v) { this.payload = v; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant v) { this.detectedAt = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
}
