package cn.teamone.eng.app;

import cn.teamone.eng.domain.BranchProtection;
import cn.teamone.eng.domain.BranchRule;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.CreateRepositoryRequest;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.BranchProtectionRepository;
import cn.teamone.eng.repo.BranchRuleRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.eng.seed.BranchRuleSeeder;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.authz.RepoMemberService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 仓库开通应用服务（⑥h 建仓批：POST /api/v1/repos 替掉「原型演示」假入口的真实现）。
 *
 * <p>流程：name/defaultBranch 白名单校验 → 数据查重 409 → {@link GitPort#initRepo}
 * 落盘 bare 库（{@code <GIT_ROOT>/<name>/<name>.git} + HEAD 指向默认分支）→ 建
 * eng.repository 行（repoPath 形态 {@code <name>/<name>.git}，与 V10 种子行
 * {@code teamone/teamone.git} 同构）→ 立即套 GitFlow 分支规则模板
 * （复用 {@link BranchRuleSeeder#gitFlowTemplate}，public 即为此开放）+ 默认分支
 * 标准保护一条（对齐 {@link BranchProtectionSeeder} 的平台标准规则口径）。</p>
 *
 * <p>权限：platform:manage 管理员口径（RepositoryController 此前无写接口可比对；
 * 建仓影响面全局，取平台级管理侧，在控制器 {@code @RequirePerm} 断言），写审计 repo.create。
 * git init 为进程外动作不随事务回滚——DB 步失败会遗留空库目录，属可接受偏差
 * （同名重建会被 409 拦截，目录可人工清理）。</p>
 *
 * @author Ivan Yang, 2026-09-14
 */
@Service
public class RepositoryProvisionService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryProvisionService.class);

    /** 仓库名白名单：字母/数字/_/-，1~64（与 GitPort.initRepo 的 repoKey 段规则一致，双重校验） */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-]{1,64}");

    /** 默认分支名白名单：对齐 GitPort ref 口径（字母/数字/_/./-//，1~100，禁 .. 与首尾特殊位） */
    private static final Pattern BRANCH_PATTERN = Pattern.compile("[a-zA-Z0-9_./\\-]{1,100}");

    private final RepositoryRepository repositoryRepo;
    private final BranchRuleRepository branchRuleRepo;
    private final BranchProtectionRepository protectionRepo;
    private final GitPort gitPort;
    private final AuditService audit;
    private final RepoMemberService repoMembers;

    public RepositoryProvisionService(RepositoryRepository repositoryRepo,
                                      BranchRuleRepository branchRuleRepo,
                                      BranchProtectionRepository protectionRepo,
                                      GitPort gitPort,
                                      AuditService audit,
                                      RepoMemberService repoMembers) {
        this.repositoryRepo = repositoryRepo;
        this.branchRuleRepo = branchRuleRepo;
        this.protectionRepo = protectionRepo;
        this.gitPort = gitPort;
        this.audit = audit;
        this.repoMembers = repoMembers;
    }

    /**
     * 开通一个新仓库：Git 落盘 + 元数据落库 + GitFlow 规则与默认分支保护即席生效。
     *
     * @param req     建仓请求（name 必填；defaultBranch 缺省 main）
     * @param actorId 操作人（审计留痕）
     * @return 新建仓库实体
     */
    @Transactional
    public Repository createRepository(CreateRepositoryRequest req, UUID actorId) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库名称不能为空");
        }
        String name = req.name().trim();
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.PLT_4000,
                    "仓库名称非法（限字母/数字/_/-，长度 1~64）: " + name);
        }
        String defaultBranch = (req.defaultBranch() == null || req.defaultBranch().isBlank())
                ? "main" : req.defaultBranch().trim();
        if (!BRANCH_PATTERN.matcher(defaultBranch).matches() || defaultBranch.contains("..")
                || defaultBranch.startsWith("-") || defaultBranch.endsWith(".lock")) {
            throw new BusinessException(ErrorCode.PLT_4000, "默认分支名非法: " + defaultBranch);
        }

        // 查重 409：name 与 repoPath 双口径（repoPath 有唯一约束，name 是业务键）
        String repoPath = name + "/" + name + ".git";
        if (repositoryRepo.findByName(name).isPresent() || repositoryRepo.findByRepoPath(repoPath).isPresent()) {
            throw new BusinessException(ErrorCode.PLT_4091, "同名仓库已存在: " + name);
        }

        // 1) Git 落盘：init --bare + HEAD 指向默认分支（进程外动作，失败即整体失败，不写脏数据）
        gitPort.initRepo(name, defaultBranch);

        // 2) 元数据落库（repoPath=<name>/<name>.git，与 V10 种子行 teamone/teamone.git 同构；
        //    createdAt/updatedAt 由实体构造时缺省 now，无 setter）
        Repository repo = new Repository();
        repo.setName(name);
        repo.setRepoPath(repoPath);
        repo.setDefaultBranch(defaultBranch);
        repo.setDescription((req.description() == null || req.description().isBlank()) ? null : req.description().trim());
        repo.setVisibility("INTERNAL");
        repo.setCiEnabled(false);
        repo = repositoryRepo.save(repo);

        // 3) 即席套 GitFlow 分支规则模板（新库必为空规则，直接落 7 条；V15 auto_delete_after_merge 随模板生效）
        Instant now = Instant.now();
        for (BranchRule rule : BranchRuleSeeder.gitFlowTemplate(repo.getId(), now)) {
            branchRuleRepo.save(rule);
        }

        // 4) 默认分支标准保护一条（MR + 1 批准 + 单测门禁 + 禁强推，对齐 BranchProtectionSeeder 口径）
        BranchProtection p = new BranchProtection();
        p.setRepoId(repo.getId());
        p.setBranchPattern(defaultBranch);
        p.setRequireMr(true);
        p.setMinApprovals(1);
        p.setRequireUnitTest(true);
        p.setBlockForcePush(true);
        protectionRepo.save(p);

        // 5) 建仓人 INHERITED Owner 行（⑥i-A1 M-a，docs/v2/13 §1.4）：同事务系统授予，
        //    面板显示「建仓人」徽标；仅 V21 之后新建的仓库产生（存量仓无行=平台 OWNER/ADMIN 短路兜底）。
        //    幂等、不单独落 acl.grant 审计（repo.create 审计已覆盖）、不 bump 缓存版本（新仓无缓存）
        repoMembers.grantInheritedOwner(repo.getId(), actorId);

        // 6) 审计留痕
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("repoId", repo.getId());
        detail.put("repoPath", repoPath);
        detail.put("defaultBranch", defaultBranch);
        detail.put("gitflowRules", 7);
        audit.record(actorId, "repo.create", "repository", repo.getId().toString(), detail);
        log.info("[repo-provision] 仓库 {} 已开通（默认分支 {}，GitFlow 规则与默认分支保护已即席生效）",
                repoPath, defaultBranch);
        return repo;
    }
}
