package cn.teamone.eng.repo;

import cn.teamone.eng.domain.WorktreeReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作副本上报仓储（eng.worktree_report 表操作）。
 *
 * @author Ivan Yang, 2026-09-13
 */
public interface WorktreeReportRepository extends JpaRepository<WorktreeReport, UUID> {

    /**
     * 按仓库查询所有活跃工作副本上报（按最近一次心跳活跃时间倒序）。
     *
     * @param repoId 代码仓库 UUID
     * @return 工作副本列表
     */
    List<WorktreeReport> findByRepoIdOrderByLastActiveAtDesc(UUID repoId);

    /**
     * 根据仓库与本地绝对路径查找已有工作副本记录（用于客户端心跳幂等更新）。
     *
     * @param repoId    代码仓库 UUID
     * @param localPath 开发者工作站上的本地路径
     * @return 可能存在的已有记录
     */
    Optional<WorktreeReport> findByRepoIdAndLocalPath(UUID repoId, String localPath);

    /**
     * 查询指定仓库下绑定某分支的所有工作副本（用于感知多少人在同时并行修改同一分支）。
     *
     * @param repoId     代码仓库 UUID
     * @param branchName 分支名称
     * @return 绑定该分支的工作副本列表
     */
    List<WorktreeReport> findByRepoIdAndBranchName(UUID repoId, String branchName);
}
