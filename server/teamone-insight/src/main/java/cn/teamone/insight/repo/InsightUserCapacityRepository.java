package cn.teamone.insight.repo;

import cn.teamone.insight.domain.InsightUserCapacity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** platform.app_user 只读仓库（CF-1/CF-4 daily_capacity_hours）。 */
public interface InsightUserCapacityRepository extends JpaRepository<InsightUserCapacity, UUID> {
}
