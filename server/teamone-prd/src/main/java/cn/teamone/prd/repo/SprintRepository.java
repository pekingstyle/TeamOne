package cn.teamone.prd.repo;

import cn.teamone.prd.domain.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 迭代仓库（表 prd.sprint）。 */
public interface SprintRepository extends JpaRepository<Sprint, UUID> {

    List<Sprint> findByProductIdOrderByStartDateAsc(UUID productId);

    /** 活跃迭代（GET /me/summary 工作台投影：completed_at 为 NULL 即进行中，最早开始的在前） */
    List<Sprint> findByCompletedAtIsNullOrderByStartDateAsc();
}
