package cn.teamone.eng.repo;

import cn.teamone.eng.domain.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 部署登记仓储（eng.deployment 表操作，R-9 发布一致性 · B2 批）。
 */
public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    /** 某版本的部署登记清单（时间倒序，交付页「部署」区数据源） */
    List<Deployment> findByReleaseIdOrderByDeployedAtDesc(UUID releaseId);
}
