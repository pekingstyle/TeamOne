package cn.teamone.prd.repo;

import cn.teamone.prd.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * 项目交付实例仓库（表 prd.project，⑥o 客制化双轨）。
 *
 * @author Ivan Yang, 2026-09-28
 */
public interface ProjectRepository
        extends JpaRepository<Project, UUID>, JpaSpecificationExecutor<Project> {

    /** 项目名唯一（建项目重复名 422 判定） */
    Optional<Project> findByName(String name);

    /** 项目名唯一（存在性快查） */
    boolean existsByName(String name);
}
