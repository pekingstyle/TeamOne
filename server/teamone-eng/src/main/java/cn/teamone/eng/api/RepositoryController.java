package cn.teamone.eng.api;

import cn.teamone.eng.app.BranchProtectionService;
import cn.teamone.eng.app.BranchRuleService;
import cn.teamone.eng.app.RepositoryProvisionService;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.CherryPickRequest;
import cn.teamone.eng.dto.CreateBranchRequest;
import cn.teamone.eng.dto.CreateRepositoryRequest;
import cn.teamone.eng.infra.git.GitBlob;
import cn.teamone.eng.infra.git.GitBranch;
import cn.teamone.eng.infra.git.GitCommit;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.infra.git.GitTag;
import cn.teamone.eng.infra.git.GitTreeItem;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.authz.RepoAclService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.auth.RequirePerm;
import cn.teamone.shared.auth.RequireRepoPerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Git 仓储查询/代码浏览与分支写端点（M2-INC-3 U1~U3 + 分支管理）。
 *
 * <p>提供仓库列表/详情、分支/Tag、提交历史分页、目录树与文件内容查询，以及分支创建/删除。
 * 遵循 07 §2.3 纪律：所有 Git 操作统一经过 {@link GitPort}，零直接命令行调用。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1/repos")
public class RepositoryController {

    private static final Logger log = LoggerFactory.getLogger(RepositoryController.class);

    private final RepositoryRepository repositoryRepository;
    private final GitPort gitPort;
    private final BranchProtectionService branchProtectionService;
    private final BranchRuleService branchRuleService;
    private final RepositoryProvisionService provisionService;
    private final RepoAclService repoAcl;

    public RepositoryController(RepositoryRepository repositoryRepository, GitPort gitPort,
                                BranchProtectionService branchProtectionService,
                                BranchRuleService branchRuleService,
                                RepositoryProvisionService provisionService,
                                RepoAclService repoAcl) {
        this.repositoryRepository = repositoryRepository;
        this.gitPort = gitPort;
        this.branchProtectionService = branchProtectionService;
        this.branchRuleService = branchRuleService;
        this.provisionService = provisionService;
        this.repoAcl = repoAcl;
    }

    /**
     * 新建仓库（⑥h 建仓批，替掉「原型演示」假按钮的真实现）。
     *
     * <p>权限口径：platform:manage（平台管理员）。本控制器此前只有读端点无写接口口径可对齐，
     * 建仓属全局影响面动作，取平台级管理侧（与 AdminController / ReleaseDeliveryController
     * 写端点同口径），在 {@code @RequirePerm} 断言，服务内另写审计 repo.create。</p>
     *
     * <p>行为：{@code git init --bare <GIT_ROOT>/<name>/<name>.git} + HEAD 指向默认分支
     * （缺省 main）→ 建 eng.repository 行（repoPath=&lt;name&gt;/&lt;name&gt;.git，与既有种子行同构）
     * → 即席套 GitFlow 分支规则模板 + 默认分支标准保护。name 非法 400；同名 409。</p>
     */
    @PostMapping
    @RequirePerm(resourceType = "platform", action = "platform:manage")
    public Map<String, Object> createRepo(
            @AuthenticationPrincipal AppUser me,
            @RequestBody CreateRepositoryRequest req) {
        UUID actorId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        Repository repo = provisionService.createRepository(req, actorId);
        return toRepoMap(repo);
    }

    /**
     * 仓库列表（含分支数与主分支提交数汇总）。
     *
     * <p>M-a A6（docs/v2/13 §5.2 清单首行）：列表端点无单一资源可断言，ACL 语义 =
     * 方法内结果集过滤——PRIVATE 仓仅对「命中 repo_member 的用户 + 平台 OWNER/ADMIN」可见，
     * INTERNAL/PUBLIC 全员可见。现网种子仓全 INTERNAL，过滤前后结果等价（零收紧验收；
     * 唯一可见变化 = PRIVATE 过滤能力的就位）。切面 {@code @RequireRepoPerm} 对无 idOrName
     * 的列表路径不做逐仓断言（{@code RequireRepoPermAspect} 约定），标注仅为端点 ACL 声明。</p>
     */
    @GetMapping
    @RequireRepoPerm(action = RepoActions.VIEW)
    public Map<String, Object> listRepos(@AuthenticationPrincipal AppUser me) {
        List<Repository> repos = repositoryRepository.findAll();
        boolean platformAdmin = me != null && (me.getPlatformRole() == AppUser.PlatformRole.OWNER
                || me.getPlatformRole() == AppUser.PlatformRole.ADMIN);
        // PRIVATE 过滤：非平台管理员且 visibility=private 时，须五步链 view 判定命中才可见
        List<Repository> visible = (platformAdmin || me == null)
                ? repos
                : repos.stream()
                        .filter(r -> !"PRIVATE".equalsIgnoreCase(r.getVisibility())
                                || repoAcl.checkRepoPerm(me.getId(), r.getId(), RepoActions.VIEW))
                        .toList();
        List<Map<String, Object>> items = new ArrayList<>(visible.size());
        for (Repository r : visible) {
            Map<String, Object> item = toRepoMap(r);
            try {
                int branchCount = gitPort.branches(r.getRepoPath()).size();
                int commitCount = gitPort.commitCount(r.getRepoPath(), r.getDefaultBranch());
                item.put("branchCount", branchCount);
                item.put("commitCount", commitCount);
            } catch (Exception e) {
                log.debug("获取仓库 Git 统计失败: repo={}, msg={}", r.getRepoPath(), e.getMessage());
                item.put("branchCount", 0);
                item.put("commitCount", 0);
            }
            items.add(item);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", items);
        res.put("total", items.size());
        return res;
    }

    /**
     * 仓库详情（支持 UUID 或仓库名查询）。
     */
    @GetMapping("/{idOrName}")
    public Map<String, Object> getRepo(@PathVariable String idOrName) {
        Repository repo = findRepo(idOrName);
        Map<String, Object> res = toRepoMap(repo);
        try {
            List<GitBranch> branches = gitPort.branches(repo.getRepoPath());
            List<GitTag> tags = gitPort.tags(repo.getRepoPath());
            int commitCount = gitPort.commitCount(repo.getRepoPath(), repo.getDefaultBranch());
            res.put("branchCount", branches.size());
            res.put("tagCount", tags.size());
            res.put("commitCount", commitCount);
            res.put("branches", branches);
            res.put("tags", tags);
        } catch (Exception e) {
            log.debug("获取仓库详情 Git 统计失败: repo={}, msg={}", repo.getRepoPath(), e.getMessage());
            res.put("branchCount", 0);
            res.put("tagCount", 0);
            res.put("commitCount", 0);
            res.put("branches", List.of());
            res.put("tags", List.of());
        }
        return res;
    }

    /**
     * 分支列表。
     */
    @GetMapping("/{idOrName}/branches")
    public List<GitBranch> getBranches(@PathVariable String idOrName) {
        Repository repo = findRepo(idOrName);
        return gitPort.branches(repo.getRepoPath());
    }

    /**
     * 创建分支（startRef 省略=默认分支头；已存在 409）。
     */
    @PostMapping("/{idOrName}/branches")
    public Map<String, Object> createBranch(
            @PathVariable String idOrName,
            @RequestBody CreateBranchRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "分支名称不能为空");
        }
        String name = req.name().trim();
        Repository repo = findRepo(idOrName);
        // 生效点一（分支治理批）：仓库存在 branch_rule 时分支名必须命中至少一条
        // name_pattern，否则 422（ENG_4255，message 列出允许前缀）；零规则仓库不设限
        branchRuleService.assertBranchNameAllowed(repo, name);
        boolean exists = gitPort.branches(repo.getRepoPath()).stream()
                .anyMatch(b -> b.name().equals(name));
        if (exists) {
            throw new BusinessException(ErrorCode.PLT_4091, "分支已存在: " + name);
        }
        String startRef = (req.startRef() == null || req.startRef().isBlank())
                ? repo.getDefaultBranch()
                : req.startRef().trim();
        gitPort.createBranch(repo.getRepoPath(), name, startRef);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("name", name);
        return res;
    }

    /**
     * 删除分支（branch_protection 命中的受保护分支 409；成功 204）。
     * {*name}：分支名常含斜杠（feature/x），PathPattern 单段变量吃不掉，须捕获剩余路径
     * （捕获值带前导 "/"，下方归一化剥掉）。
     */
    @DeleteMapping("/{idOrName}/branches/{*name}")
    public ResponseEntity<Void> deleteBranch(
            @PathVariable String idOrName,
            @PathVariable String name) {
        String cleanName = name == null ? "" : name.replaceFirst("^/", "").trim();
        Repository repo = findRepo(idOrName);
        // 受保护判定复用 BranchProtectionService 的通配符匹配（精确 / 'release/*'）
        if (branchProtectionService.findMatchingProtection(repo.getId(), cleanName).isPresent()) {
            throw new BusinessException(ErrorCode.PLT_4091, "分支 '" + cleanName + "' 受保护，禁止删除");
        }
        boolean exists = gitPort.branches(repo.getRepoPath()).stream()
                .anyMatch(b -> b.name().equals(cleanName));
        if (!exists) {
            throw new BusinessException(ErrorCode.PLT_4040, "分支不存在: " + cleanName);
        }
        gitPort.deleteBranch(repo.getRepoPath(), cleanName);
        return ResponseEntity.noContent().build();
    }

    /**
     * Tag 列表。
     */
    @GetMapping("/{idOrName}/tags")
    public List<GitTag> getTags(@PathVariable String idOrName) {
        Repository repo = findRepo(idOrName);
        return gitPort.tags(repo.getRepoPath());
    }

    /**
     * 提交历史分页（按时间倒序）。
     */
    @GetMapping("/{idOrName}/commits")
    public Map<String, Object> getCommits(
            @PathVariable String idOrName,
            @RequestParam(required = false) String ref,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Repository repo = findRepo(idOrName);
        String targetRef = (ref == null || ref.isBlank()) ? repo.getDefaultBranch() : ref.trim();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<GitCommit> commits = gitPort.commits(repo.getRepoPath(), targetRef, safePage, safeSize);
        int total = gitPort.commitCount(repo.getRepoPath(), targetRef);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", commits);
        res.put("page", safePage);
        res.put("size", safeSize);
        res.put("total", total);
        res.put("ref", targetRef);
        return res;
    }

    /**
     * 目录树（文件与子目录）。
     */
    @GetMapping("/{idOrName}/tree")
    public Map<String, Object> getTree(
            @PathVariable String idOrName,
            @RequestParam(required = false) String ref,
            @RequestParam(required = false) String path) {
        Repository repo = findRepo(idOrName);
        String targetRef = (ref == null || ref.isBlank()) ? repo.getDefaultBranch() : ref.trim();
        String targetPath = (path == null) ? "" : path.trim();
        List<GitTreeItem> items = gitPort.tree(repo.getRepoPath(), targetRef, targetPath);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", items);
        res.put("ref", targetRef);
        res.put("path", targetPath);
        return res;
    }

    /**
     * 文件内容读取。
     */
    @GetMapping("/{idOrName}/blob")
    public GitBlob getBlob(
            @PathVariable String idOrName,
            @RequestParam(required = false) String ref,
            @RequestParam String path) {
        Repository repo = findRepo(idOrName);
        String targetRef = (ref == null || ref.isBlank()) ? repo.getDefaultBranch() : ref.trim();
        return gitPort.blob(repo.getRepoPath(), targetRef, path);
    }

    /**
     * 对比两分支/Ref（U5）：输出公共基点、提交列表、三路 diff 与可合并性。
     */
    @GetMapping("/{idOrName}/compare")
    public cn.teamone.eng.infra.git.GitCompareResult compare(
            @PathVariable String idOrName,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String source) {
        Repository repo = findRepo(idOrName);
        String cleanTarget = (target == null || target.isBlank()) ? repo.getDefaultBranch() : target.trim();
        String cleanSource = (source == null || source.isBlank()) ? repo.getDefaultBranch() : source.trim();
        return gitPort.compare(repo.getRepoPath(), cleanTarget, cleanSource);
    }

    /**
     * cherry-pick（部分合并，分支治理批）：把一个提交的改动拣选到目标分支并生成新提交。
     *
     * <p>入参经 GitPort 白名单校验（commitSha 须 40 位小写 hex）；目标分支不存在 404；
     * 存在冲突抛 ENG_4251（422，附冲突文件清单）。实现走临时 worktree（详见 GitCommandPort）。</p>
     */
    @PostMapping("/{idOrName}/cherry-pick")
    public Map<String, Object> cherryPick(
            @PathVariable String idOrName,
            @RequestBody CherryPickRequest req) {
        if (req == null || req.commitSha() == null || req.commitSha().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "commitSha 不能为空");
        }
        if (req.targetBranch() == null || req.targetBranch().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "targetBranch 不能为空");
        }
        Repository repo = findRepo(idOrName);
        // 服务端治理兜底（QA 复审 MUST-FIX）：受保护分支不允许 cherry-pick 直写（前端禁选可被直调 API 绕过），
        // 治理语义与删除分支一致——走 branch_protection 通配符匹配
        if (branchProtectionService.findMatchingProtection(repo.getId(), req.targetBranch().trim()).isPresent()) {
            throw new BusinessException(ErrorCode.PLT_4091, "目标分支受保护，请在 MR 流程合入");
        }
        String newSha = gitPort.cherryPick(repo.getRepoPath(), req.commitSha().trim(), req.targetBranch().trim());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("commitSha", newSha);
        res.put("branch", req.targetBranch().trim());
        return res;
    }

    private Repository findRepo(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库标识不能为空");
        }
        try {
            UUID id = UUID.fromString(idOrName.trim());
            return repositoryRepository.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        } catch (IllegalArgumentException e) {
            return repositoryRepository.findByName(idOrName.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        }
    }

    private Map<String, Object> toRepoMap(Repository r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("name", r.getName());
        map.put("repoPath", r.getRepoPath());
        map.put("defaultBranch", r.getDefaultBranch());
        map.put("description", r.getDescription());
        map.put("productId", r.getProductId());
        map.put("componentId", r.getComponentId());
        map.put("visibility", r.getVisibility());
        map.put("ciEnabled", r.isCiEnabled());
        map.put("createdAt", r.getCreatedAt());
        map.put("updatedAt", r.getUpdatedAt());
        return map;
    }
}
