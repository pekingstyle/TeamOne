package cn.teamone.eng.repo;

import cn.teamone.eng.domain.Baseline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基线数据访问仓储（M2-INC-3 U10）。
 *
 * @author Ivan Yang, 2026-09-13
 */
public interface BaselineRepository extends JpaRepository<Baseline, UUID> {

    List<Baseline> findByRepoIdOrderByCreatedAtDesc(UUID repoId);

    List<Baseline> findByRepoIdAndStatusOrderByCreatedAtDesc(UUID repoId, String status);

    Optional<Baseline> findByRepoIdAndTagRef(UUID repoId, String tagRef);
}
