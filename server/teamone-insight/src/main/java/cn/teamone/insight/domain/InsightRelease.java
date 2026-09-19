package cn.teamone.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * prd.release 只读映射（跨 schema 只读，红线①；权威实体 cn.teamone.prd.domain.Release）。
 *
 * <p>列白名单（CF-3 相邻发布挤压 + CF-6 版本发布日）：id, key, name, product_id, plan_date, code_freeze_date。</p>
 */
@Entity
@Immutable
@Table(name = "release", schema = "prd")
public class InsightRelease {

    @Id
    private UUID id;

    @Column(name = "key", insertable = false, updatable = false)
    private String key;

    @Column(insertable = false, updatable = false)
    private String name;

    @Column(name = "product_id", insertable = false, updatable = false)
    private UUID productId;

    @Column(name = "plan_date", insertable = false, updatable = false)
    private LocalDate planDate;

    @Column(name = "code_freeze_date", insertable = false, updatable = false)
    private LocalDate codeFreezeDate;

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public UUID getProductId() { return productId; }
    public LocalDate getPlanDate() { return planDate; }
    public LocalDate getCodeFreezeDate() { return codeFreezeDate; }
}
