package cn.teamone.eng.repo;

import cn.teamone.eng.domain.MergeConflictResolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MergeConflictResolutionRepository extends JpaRepository<MergeConflictResolution, UUID> {

    List<MergeConflictResolution> findByMrIdOrderByResolvedAtAsc(UUID mrId);

    Optional<MergeConflictResolution> findByMrIdAndFilePath(UUID mrId, String filePath);

    void deleteByMrIdAndFilePath(UUID mrId, String filePath);
}
