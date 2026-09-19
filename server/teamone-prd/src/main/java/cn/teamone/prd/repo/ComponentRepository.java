package cn.teamone.prd.repo;

import cn.teamone.prd.domain.Component;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 组件仓库（表 prd.component）。 */
public interface ComponentRepository extends JpaRepository<Component, UUID> {

    Optional<Component> findByKey(String key);

    List<Component> findByProductIdOrderByCreatedAtAsc(UUID productId);
}
