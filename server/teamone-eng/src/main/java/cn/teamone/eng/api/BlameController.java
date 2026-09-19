package cn.teamone.eng.api;

import cn.teamone.eng.app.BlameService;
import cn.teamone.eng.domain.MergeRequest;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.infra.git.GitBlameResult;
import cn.teamone.eng.repo.MergeRequestRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Git 逐文件 Blame 溯源与预热 REST 控制器（S-1' 正式收口）。
 * <p>
 * 提供仓库任意版本的文件行级溯源查询、MR 关联变更文件的异步预热触发，
 * 配合后端两级缓存机制实现纳秒/毫秒级 Blame 数据响应。
 * </p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1")
public class BlameController {

    private final BlameService blameService;
    private final MergeRequestRepository mergeRequestRepo;
    private final RepositoryRepository repositoryRepo;

    public BlameController(
            BlameService blameService,
            MergeRequestRepository mergeRequestRepo,
            RepositoryRepository repositoryRepo) {
        this.blameService = blameService;
        this.mergeRequestRepo = mergeRequestRepo;
        this.repositoryRepo = repositoryRepo;
    }

    /**
     * 查询指定仓库、特定引用版本下某一文件的逐行 Blame 溯源记录。
     *
     * @param idOrName 仓库 ID 或短名称（如 "teamone"）
     * @param ref      分支名称、Tag 或 Commit SHA（缺省时使用仓库主分支）
     * @param path     相对文件路径（如 "server/pom.xml"）
     * @return 结构化的逐行 Blame 结果（包含行号、提交 SHA、作者、时间与源码）
     */
    @GetMapping("/repos/{idOrName}/blame")
    public GitBlameResult getRepoBlame(
            @PathVariable String idOrName,
            @RequestParam(required = false) String ref,
            @RequestParam String path) {
        return blameService.getBlame(idOrName, ref, path);
    }

    /**
     * 为指定的合并请求（MR）触发涉及变更文件的后台异步 Blame 预热（Warmup）。
     * <p>
     * 在 MR 被打开或浏览时由客户端或网关发起，将相关文件的行溯源信息预先计算并存入两级缓存，
     * 保障评审人员在展开 Diff 行 Blame 详情时享有极致低延迟响应。
     * </p>
     *
     * @param id 合并请求唯一标识 UUID
     * @return 包含预热文件计数与操作状态的响应 Map
     */
    @PostMapping("/mrs/{id}/blame/preload")
    public Map<String, Object> preloadMrBlame(@PathVariable UUID id) {
        int count = blameService.preloadMrBlame(id);
        return Map.of(
                "ok", true,
                "mrId", id,
                "queuedFiles", count,
                "message", "已触发 MR 变更文件 Blame 异步预热"
        );
    }

    /**
     * 获取指定 MR 上下文中某一具体变更文件的 Blame 溯源快照。
     *
     * @param id   合并请求唯一标识 UUID
     * @param path 相对文件路径
     * @return 该文件在 MR 源分支最新提交下的 Blame 数据
     */
    @GetMapping("/mrs/{id}/blame")
    public GitBlameResult getMrFileBlame(
            @PathVariable UUID id,
            @RequestParam String path) {
        MergeRequest mr = mergeRequestRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "合并请求不存在: " + id));
        Repository repo = repositoryRepo.findById(mr.getRepoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在"));

        return blameService.getBlame(repo.getId().toString(), mr.getSourceBranch(), path);
    }
}
