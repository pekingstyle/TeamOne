package cn.teamone.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * prd.sprint 只读映射（跨 schema 只读，红线①；权威实体 cn.teamone.prd.domain.Sprint）。
 *
 * <p>列白名单（CF-5 迭代倒挂 + CF-6 迭代截止哨兵）：id, name, product_id, start_date, due_date。
 * due_date NULL 时引擎取哨兵 '9999-12-31'（原型 sprintEnd() 逐字一致）。</p>
 */
@Entity
@Immutable
@Table(name = "sprint", schema = "prd")
public class InsightSprint {

    @Id
    private UUID id;

    @Column(insertable = false, updatable = false)
    private String name;

    @Column(name = "product_id", insertable = false, updatable = false)
    private UUID productId;

    @Column(name = "start_date", insertable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "due_date", insertable = false, updatable = false)
    private LocalDate dueDate;

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getProductId() { return productId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getDueDate() { return dueDate; }

    /**
     * 测试或快照工厂方法
     */
    public static InsightSprint of(UUID id, String name, UUID productId, LocalDate startDate, LocalDate dueDate) {
        InsightSprint sprint = new InsightSprint();
        sprint.id = id;
        sprint.name = name;
        sprint.productId = productId;
        sprint.startDate = startDate;
        sprint.dueDate = dueDate;
        return sprint;
    }
}
