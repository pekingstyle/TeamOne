package cn.teamone.eng.api;

import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.infra.git.GitSearchResult;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Git 裸仓代码全文检索 REST 控制器（V-21 / U4）。
 * <p>
 * 提供基于自研内核针对指定版本（分支/Tag/Commit）的代码内容级全文检索能力，
 * 支持路径模式过滤与返回行上限控制。
 * </p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1/repos")
public class CodeSearchController {

    private final RepositoryRepository repositoryRepo;
    private final GitPort gitPort;

    public CodeSearchController(RepositoryRepository repositoryRepo, GitPort gitPort) {
        this.repositoryRepo = repositoryRepo;
        this.gitPort = gitPort;
    }

    /**
     * 在指定仓库的指定版本中全文检索代码关键字（git grep）。
     *
     * @param idOrName 仓库 ID (UUID) 或仓库名称（如 "teamone"）
     * @param q        检索关键词（至少 2 个字符）
     * @param ref      分支名称、Tag 或 Commit SHA（缺省为仓库主分支）
     * @param path     文件路径或通配符过滤（可选，如 "*.java" 或 "server"）
     * @param limit    最大返回匹配条数（默认 50，上限 200）
     * @return 结构化的匹配行列表与执行耗时
     */
    @GetMapping("/{idOrName}/search")
    public GitSearchResult searchCode(
            @PathVariable String idOrName,
            @RequestParam String q,
            @RequestParam(required = false) String ref,
            @RequestParam(required = false) String path,
            @RequestParam(defaultValue = "50") int limit) {

        Repository repo = findRepo(idOrName);
        String targetRef = (ref != null && !ref.isBlank()) ? ref.trim() : repo.getDefaultBranch();

        return gitPort.search(repo.getRepoPath(), targetRef, q, path, limit);
    }

    private Repository findRepo(String idOrName) {
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
}
