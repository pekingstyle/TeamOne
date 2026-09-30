package cn.teamone.prd.repo;

import cn.teamone.prd.domain.WorkItemLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 工作项通用关联仓库（表 prd.work_item_link）。 */
public interface WorkItemLinkRepository extends JpaRepository<WorkItemLink, UUID> {

    List<WorkItemLink> findByFromItemId(UUID fromItemId);

    List<WorkItemLink> findByToItemId(UUID toItemId);

    /** ⑥s 查重（V4 UNIQUE(from,to,relation) 的友好 422 预判） */
    List<WorkItemLink> findByFromItemIdAndToItemIdAndRelation(UUID fromItemId, UUID toItemId, String relation);
}
