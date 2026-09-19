package cn.teamone.eng.repo;

import cn.teamone.eng.domain.MergeRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MergeRequestRepository extends JpaRepository<MergeRequest, UUID> {

    Optional<MergeRequest> findByRepoIdAndMrNumber(UUID repoId, Integer mrNumber);

    List<MergeRequest> findByRepoIdOrderByMrNumberDesc(UUID repoId);

    Page<MergeRequest> findByRepoId(UUID repoId, Pageable pageable);

    Page<MergeRequest> findByRepoIdAndStatus(UUID repoId, String status, Pageable pageable);

    Page<MergeRequest> findByStatus(String status, Pageable pageable);

    @Query("SELECT COALESCE(MAX(m.mrNumber), 0) FROM MergeRequest m WHERE m.repoId = :repoId")
    int findMaxMrNumber(@Param("repoId") UUID repoId);
}
