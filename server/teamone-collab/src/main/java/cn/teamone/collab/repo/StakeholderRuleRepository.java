package cn.teamone.collab.repo;

import cn.teamone.collab.domain.StakeholderRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 干系人规则仓库（collab.stakeholder_rule 种子读侧）。 */
public interface StakeholderRuleRepository extends JpaRepository<StakeholderRule, StakeholderRule.Pk> {

    List<StakeholderRule> findByTargetType(String targetType);
}
