package cn.teamone.prd.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cn.teamone.prd.domain.ProjectUpgrade;

/** 项目升级记录仓库（⑥r；查询侧惰性同步 MR 状态） */
public interface ProjectUpgradeRepository extends JpaRepository<ProjectUpgrade, UUID> {

    List<ProjectUpgrade> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<ProjectUpgrade> findByProjectIdAndStatus(UUID projectId, String status);
}
