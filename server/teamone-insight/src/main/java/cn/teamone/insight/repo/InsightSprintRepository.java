package cn.teamone.insight.repo;

import cn.teamone.insight.domain.InsightSprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * prd.sprint 只读仓库（CF-5/CF-6 迭代截止及效能度量；缺省哨兵在引擎内）。
 */
public interface InsightSprintRepository extends JpaRepository<InsightSprint, UUID> {

    /**
     * 按产品 ID 检索关联的迭代列表
     *
     * @param productId 产品唯一标识
     * @return 归属于该产品的迭代列表
     */
    List<InsightSprint> findByProductId(UUID productId);
}
