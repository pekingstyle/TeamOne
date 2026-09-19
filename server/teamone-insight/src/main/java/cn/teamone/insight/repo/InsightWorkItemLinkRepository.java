package cn.teamone.insight.repo;

import cn.teamone.insight.domain.InsightWorkItemLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** prd.work_item_link 只读仓库（CF-5 blocks 反查；relation 取值受 V4 CHECK 约束）。 */
public interface InsightWorkItemLinkRepository extends JpaRepository<InsightWorkItemLink, UUID> {

    /** blocks 方向（红线⑤）：from 阻塞 to；引擎侧反建成 to→from 映射 */
    List<InsightWorkItemLink> findByRelation(String relation);
}
