package cn.teamone.eng.repo;

import cn.teamone.eng.domain.BranchProtection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 分支保护规则数据访问仓储（M2-INC-3 U8）。
 *
 * @author Ivan Yang, 2026-09-13
 */
public interface BranchProtectionRepository extends JpaRepository<BranchProtection, UUID> {

    List<BranchProtection> findByRepoId(UUID repoId);

    Optional<BranchProtection> findByRepoIdAndBranchPattern(UUID repoId, String branchPattern);
}
