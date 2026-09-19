package cn.teamone.insight.repo;

import cn.teamone.insight.domain.InsightWorkItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * prd.work_item 只读仓库（效能指标与冲突批算原料；跨 schema 只读）。
 */
public interface InsightWorkItemRepository extends JpaRepository<InsightWorkItem, UUID> {

    /**
     * 按产品 ID 检索关联的工作项列表
     *
     * @param productId 产品唯一标识
     * @return 归属于该产品的工作项列表
     */
    List<InsightWorkItem> findByProductId(UUID productId);

    /**
     * 按迭代 ID 检索关联的工作项列表
     *
     * @param sprintId 迭代唯一标识
     * @return 归属于该迭代的工作项列表
     */
    List<InsightWorkItem> findBySprintId(UUID sprintId);

    /**
     * 按产品与迭代组合筛选工作项列表
     *
     * @param productId 产品唯一标识
     * @param sprintId 迭代唯一标识
     * @return 同时归属于该产品和迭代的工作项列表
     */
    List<InsightWorkItem> findByProductIdAndSprintId(UUID productId, UUID sprintId);
}
