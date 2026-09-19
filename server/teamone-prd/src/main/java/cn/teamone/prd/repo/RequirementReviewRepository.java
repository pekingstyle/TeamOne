package cn.teamone.prd.repo;

import cn.teamone.prd.domain.RequirementReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 需求评审记录仓库（表 prd.requirement_review，会签判定按 round 内聚合）。 */
public interface RequirementReviewRepository extends JpaRepository<RequirementReview, UUID> {

    List<RequirementReview> findByRequirementIdOrderByRoundAscIdAsc(UUID requirementId);

    /** 当前评审轮（round 最大一行；submit 时 round = 该值 +1） */
    Optional<RequirementReview> findFirstByRequirementIdOrderByRoundDesc(UUID requirementId);

    /** 某一轮的全部评审行（会签聚合判定） */
    List<RequirementReview> findByRequirementIdAndRound(UUID requirementId, int round);

    /** 定位当前评审人的行（review 动作） */
    Optional<RequirementReview> findByRequirementIdAndRoundAndReviewerId(UUID requirementId, int round, UUID reviewerId);
}
