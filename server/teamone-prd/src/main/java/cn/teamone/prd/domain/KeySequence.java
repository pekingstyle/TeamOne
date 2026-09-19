package cn.teamone.prd.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** 业务键序号（type PK、next_val 行锁发放，05 架构文档 §2.3）。表 prd.key_sequence。 */
@Entity
@Table(name = "key_sequence", schema = "prd")
public class KeySequence {

    /** 类型：DEFECT/TASK/TEST_TASK/REQUIREMENT */
    @Id
    private String type;

    @Column(name = "next_val", nullable = false)
    private long nextVal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public long getNextVal() { return nextVal; }
    public void setNextVal(long v) { this.nextVal = v; }
    public Instant getCreatedAt() { return createdAt; }
}
