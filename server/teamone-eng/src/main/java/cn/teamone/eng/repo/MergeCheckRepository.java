package cn.teamone.eng.repo;

import cn.teamone.eng.domain.MergeCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MergeCheckRepository extends JpaRepository<MergeCheck, UUID> {

    List<MergeCheck> findByMrId(UUID mrId);

    Optional<MergeCheck> findByMrIdAndKind(UUID mrId, String kind);

    void deleteByMrId(UUID mrId);
}
