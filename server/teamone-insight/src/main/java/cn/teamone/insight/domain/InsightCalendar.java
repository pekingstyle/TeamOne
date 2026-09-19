package cn.teamone.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/**
 * platform.calendar 只读映射（跨 schema 只读，红线①；V4:196 建表、V7 自 prd 迁入 platform）。
 *
 * <p>列白名单：date（主键）、is_workday。原型 store.ts workdays() 的「周末+HOLIDAYS 集合」
 * 由本表 is_workday 表达（公式映射允许差异①：workdays→calendar，V8 种子 2026~2027 + 三天节假日）。</p>
 */
@Entity
@Immutable
@Table(name = "calendar", schema = "platform")
public class InsightCalendar {

    /** 列名即 date（非 cal_date——V7 实际结构核对后定） */
    @Id
    @Column(name = "date", insertable = false, updatable = false)
    private LocalDate calDate;

    @Column(name = "is_workday", insertable = false, updatable = false)
    private boolean workday;

    public LocalDate getCalDate() { return calDate; }
    public boolean isWorkday() { return workday; }
}
