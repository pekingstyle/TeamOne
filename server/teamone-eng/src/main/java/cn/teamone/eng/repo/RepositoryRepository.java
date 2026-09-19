package cn.teamone.eng.repo;

import cn.teamone.eng.domain.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Git 仓库数据访问（M2-INC-3 U1~U3）。
 *
 * @author Ivan Yang, 2026-09-13
 */
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    Optional<Repository> findByRepoPath(String repoPath);

    Optional<Repository> findByName(String name);
}
