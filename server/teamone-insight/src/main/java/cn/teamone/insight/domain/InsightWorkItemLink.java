package cn.teamone.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * prd.work_item_link 只读映射（跨 schema 只读，红线①；权威实体 cn.teamone.prd.domain.WorkItemLink）。
 *
 * <p>列白名单（CF-5 依赖倒挂 blocks 反查）：id, from_item_id, to_item_id, relation。
 * 方向语义（红线⑤，全局唯一）：from 阻塞 to——from 未了结而 to 先到期即倒挂。</p>
 */
@Entity
@Immutable
@Table(name = "work_item_link", schema = "prd")
public class InsightWorkItemLink {

    @Id
    private UUID id;

    @Column(name = "from_item_id", insertable = false, updatable = false)
    private UUID fromItemId;

    @Column(name = "to_item_id", insertable = false, updatable = false)
    private UUID toItemId;

    @Column(insertable = false, updatable = false)
    private String relation;

    public UUID getId() { return id; }
    public UUID getFromItemId() { return fromItemId; }
    public UUID getToItemId() { return toItemId; }
    public String getRelation() { return relation; }
}
