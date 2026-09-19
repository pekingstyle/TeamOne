package cn.teamone.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * platform.app_user 只读映射（跨 schema 只读，红线①；权威实体 cn.teamone.platform.domain.AppUser）。
 *
 * <p>列白名单（CF-1/CF-4 容量 + conflict.detected 通知取人）：id, daily_capacity_hours, platform_role。
 * 不映射展示名列——冲突 detail 文案以 userId/business key 表达。</p>
 */
@Entity
@Immutable
@Table(name = "app_user", schema = "platform")
public class InsightUserCapacity {

    @Id
    private UUID id;

    /** CF-1 容量（原型 u.dailyCapacityHours；DB 默认 8） */
    @Column(name = "daily_capacity_hours", insertable = false, updatable = false)
    private int dailyCapacityHours;

    @Column(name = "platform_role", insertable = false, updatable = false)
    private String platformRole;

    public UUID getId() { return id; }
    public int getDailyCapacityHours() { return dailyCapacityHours; }
    public String getPlatformRole() { return platformRole; }
}
