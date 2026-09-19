package cn.teamone.insight.repo;

import cn.teamone.insight.domain.InsightRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** prd.release 只读仓库（CF-3 相邻发布 / CF-6 版本发布日）。 */
public interface InsightReleaseRepository extends JpaRepository<InsightRelease, UUID> {
}
