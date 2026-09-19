package cn.teamone.eng.repo;

import cn.teamone.eng.domain.BranchRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 分支规则数据访问仓储（分支治理批）。
 *
 * @author Ivan Yang, 2026-09-14
 */
public interface BranchRuleRepository extends JpaRepository<BranchRule, UUID> {

    List<BranchRule> findByRepoId(UUID repoId);

    Optional<BranchRule> findByRepoIdAndBranchType(UUID repoId, String branchType);

    /** 整仓替换式保存（PUT）的清理步：派生删除，须在事务内调用；调用方随即 flush 保证删先于插落库 */
    void deleteByRepoId(UUID repoId);

    /** 强制刷写挂起的删除/插入（JpaRepository 自带；用于删除后立即落库再插入，防同键冲突） */
    void flush();
}
