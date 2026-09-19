package cn.teamone.insight.repo;

import cn.teamone.insight.domain.InsightCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

/** platform.calendar 只读仓库（工作日真相；V8 种子 2026-01-01~2027-12-31）。 */
public interface InsightCalendarRepository extends JpaRepository<InsightCalendar, LocalDate> {
}
