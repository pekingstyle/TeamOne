package cn.teamone.prd.repo;

import cn.teamone.prd.domain.ReviewRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 评审轮次纪要仓库（表 prd.review_round，V18；round 唯一，稀疏随行 requirement_review）。 */
public interface ReviewRoundRepository extends JpaRepository<ReviewRound, UUID> {

    /** 某需求全部轮次（round 升序） */
    List<ReviewRound> findByRequirementIdOrderByRoundAsc(UUID requirementId);

    /** 定位某一轮（minutes 上传/更新） */
    Optional<ReviewRound> findByRequirementIdAndRound(UUID requirementId, int round);
}
