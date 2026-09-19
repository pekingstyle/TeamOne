package cn.teamone.eng.repo;

import cn.teamone.eng.domain.MergeReviewer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MergeReviewerRepository extends JpaRepository<MergeReviewer, UUID> {

    List<MergeReviewer> findByMrId(UUID mrId);

    Optional<MergeReviewer> findByMrIdAndUserId(UUID mrId, UUID userId);

    void deleteByMrId(UUID mrId);
}
