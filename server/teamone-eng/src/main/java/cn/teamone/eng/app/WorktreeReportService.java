package cn.teamone.eng.app;

import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.domain.WorktreeReport;
import cn.teamone.eng.dto.WorktreeReportRequest;
import cn.teamone.eng.dto.WorktreeReportResponse;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.eng.repo.WorktreeReportRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工作副本上报业务服务（FR-v2-10 / R7 / 05 §2.3 / M2-INC-3 V-16）。
 *
 * @author Ivan Yang, 2026-09-13
 */
@Service
public class WorktreeReportService {

    private final WorktreeReportRepository worktreeRepo;
    private final RepositoryRepository repositoryRepo;

    public WorktreeReportService(
            WorktreeReportRepository worktreeRepo,
            RepositoryRepository repositoryRepo) {
        this.worktreeRepo = worktreeRepo;
        this.repositoryRepo = repositoryRepo;
    }

    /**
     * 根据仓库 UUID 字符串或仓库唯一名称查找仓库实体。
     *
     * @param idOrName 仓库 UUID 或名称（如 "teamone"）
     * @return 仓库实体对象
     * @throws BusinessException 当标识为空或仓库不存在时抛出
     */
    public Repository findRepo(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库标识不能为空");
        }
        try {
            UUID id = UUID.fromString(idOrName.trim());
            return repositoryRepo.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        } catch (IllegalArgumentException e) {
            return repositoryRepo.findByName(idOrName.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        }
    }

    /**
     * 查询指定代码仓库的所有活跃工作副本列表（按最近一次心跳上报时间倒序）。
     *
     * @param repoIdOrName 仓库 ID 或名称
     * @return 格式化后的工作副本详情列表
     */
    @Transactional(readOnly = true)
    public List<WorktreeReportResponse> listWorktrees(String repoIdOrName) {
        Repository repo = findRepo(repoIdOrName);
        return worktreeRepo.findByRepoIdOrderByLastActiveAtDesc(repo.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 处理客户端单个本地工作副本（Worktree）心跳与状态上报。
     * <p>
     * 逻辑步骤：
     * <ol>
     *   <li>校验入参有效性（本地路径、分支名不能为空）</li>
     *   <li>依据 (repoId, localPath) 联合键查询已有记录：存在则执行原地属性覆盖与心跳续期；不存在则建档插入</li>
     *   <li>更新指标：dirtyFileCount（脏文件数）、aheadCount（领先数）、behindCount（落后数）、最新提交 SHA</li>
     *   <li>刷新 lastActiveAt 为当前时间，置生命周期为 active 活跃状态</li>
     * </ol>
     * </p>
     *
     * @param repoIdOrName 仓库 ID 或名称
     * @param req          工作副本上报请求数据
     * @param userId       当前上报客户端绑定的用户 ID
     * @return 上报更新后的工作副本响应 DTO
     */
    @Transactional
    public WorktreeReportResponse reportWorktree(String repoIdOrName, WorktreeReportRequest req, UUID userId) {
        Repository repo = findRepo(repoIdOrName);
        if (req.localPath() == null || req.localPath().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "工作副本本地路径不能为空");
        }
        if (req.branchName() == null || req.branchName().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "工作副本分支名不能为空");
        }

        String path = req.localPath().trim();
        WorktreeReport wt = worktreeRepo.findByRepoIdAndLocalPath(repo.getId(), path)
                .orElseGet(() -> {
                    WorktreeReport n = new WorktreeReport();
                    n.setRepoId(repo.getId());
                    n.setLocalPath(path);
                    return n;
                });

        wt.setBranchName(req.branchName().trim());
        wt.setBaseRef((req.baseRef() != null && !req.baseRef().isBlank()) ? req.baseRef().trim() : repo.getDefaultBranch());
        wt.setOwnerUserId(userId);
        wt.setDirtyFileCount(req.dirtyFileCount());
        wt.setAheadCount(req.aheadCount());
        wt.setBehindCount(req.behindCount());
        wt.setStatus("active");
        if (req.lastCommitSha() != null && !req.lastCommitSha().isBlank()) {
            wt.setLastCommitSha(req.lastCommitSha().trim());
            wt.setLastCommitAt(Instant.now());
        }
        wt.setLastActiveAt(Instant.now());
        wt.setUpdatedAt(Instant.now());

        wt = worktreeRepo.save(wt);
        return toResponse(wt);
    }

    /**
     * 批量上报多个本地工作副本（支持多工作树开发者一次性同步）。
     *
     * @param repoIdOrName 仓库 ID 或名称
     * @param reports      工作副本列表
     * @param userId       当前操作用户 ID
     * @return 批量上报结果列表
     */
    @Transactional
    public List<WorktreeReportResponse> reportBatch(String repoIdOrName, List<WorktreeReportRequest> reports, UUID userId) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        List<WorktreeReportResponse> result = new ArrayList<>();
        for (WorktreeReportRequest req : reports) {
            result.add(reportWorktree(repoIdOrName, req, userId));
        }
        return result;
    }

    /**
     * 将领域实体 WorktreeReport 映射转换为对外响应 DTO。
     */
    private WorktreeReportResponse toResponse(WorktreeReport wt) {
        return new WorktreeReportResponse(
                wt.getId(),
                wt.getRepoId(),
                wt.getLocalPath(),
                wt.getBranchName(),
                wt.getBaseRef(),
                wt.getOwnerUserId(),
                wt.getDirtyFileCount(),
                wt.getAheadCount(),
                wt.getBehindCount(),
                wt.getStatus(),
                wt.getLastCommitSha(),
                wt.getLastCommitAt(),
                wt.getLastActiveAt(),
                wt.getCreatedAt()
        );
    }
}
