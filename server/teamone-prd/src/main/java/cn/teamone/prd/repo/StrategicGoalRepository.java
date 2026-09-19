package cn.teamone.prd.repo;

import cn.teamone.prd.domain.StrategicGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 战略目标仓库（表 prd.strategic_goal）。 */
public interface StrategicGoalRepository extends JpaRepository<StrategicGoal, UUID> {
}
