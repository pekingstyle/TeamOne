package cn.teamone.eng.seed;

import cn.teamone.eng.domain.BranchRule;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.BranchRuleRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 分支规则种子（ApplicationRunner，运行期幂等；分支治理批）。
 *
 * <p>背景：GitFlow 默认规则若放迁移期种子会遇到 V11/V12 同款问题——本地库首次迁移
 * 早于 Git 仓库初始化、eng.repository 为空，条件插入被跳过后主干裸奔。故改为运行期兜底：
 * 仓库<b>一条 branch_rule 都没有</b>时按 GitFlow 模板补默认规则；已有规则（管理端配置）
 * 一律不动。迁移 V15 本身不插任何种子（零硬编码 repo uuid）。</p>
 *
 * <p>GitFlow 模板：受保护语义靠既有 branch_protection；hotfix 的「合回 main 并同步
 * develop」双合入语义写 description；poc/* 不设固定合入目标且允许直推。</p>
 */
@Component
public class BranchRuleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BranchRuleSeeder.class);

    private final RepositoryRepository repositoryRepo;
    private final BranchRuleRepository branchRuleRepo;

    public BranchRuleSeeder(RepositoryRepository repositoryRepo, BranchRuleRepository branchRuleRepo) {
        this.repositoryRepo = repositoryRepo;
        this.branchRuleRepo = branchRuleRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Repository repo : repositoryRepo.findAll()) {
            if (!branchRuleRepo.findByRepoId(repo.getId()).isEmpty()) {
                continue; // 已有规则（管理端配置或历史数据），不叠加不改动
            }
            Instant now = Instant.now();
            for (BranchRule rule : gitFlowTemplate(repo.getId(), now)) {
                branchRuleRepo.save(rule);
            }
            log.info("[branch-rule-seed] 仓库 {} 零分支规则，已按 GitFlow 模板补默认规则（7 条）",
                    repo.getRepoPath());
        }
    }

    /** GitFlow 默认规则模板（顺序即展示次序；allow_direct_push 仅 poc 放开） */
    public static List<BranchRule> gitFlowTemplate(UUID repoId, Instant now) {
        return List.of(
                rule(repoId, now, "main", "main", null, null, false, false,
                        "主干分支：仅经 release/hotfix 分支合入；保护语义（MR+批准+门禁+禁强推）由 branch_protection 承担"),
                rule(repoId, now, "develop", "develop", "main", "develop", false, false,
                        "集成分支：基于 main 拉出；feature/fix 完成后经 MR 合入，发版期由 release/* 承接"),
                rule(repoId, now, "release", "release/*", "develop", "main", false, false,
                        "发布分支：基于 develop 拉出做稳定化，验收后合入 main（并按需回灌 develop）"),
                rule(repoId, now, "hotfix", "hotfix/*", "main", "main", false, false,
                        "热修复分支：基于 main 拉出，修复后合回 main；GitFlow 双合入语义（同步合入 develop）需另行发起第二个 MR 完成"),
                rule(repoId, now, "feature", "feature/*", "develop", "develop", false, false,
                        "功能分支：基于 develop 拉出，完成后经 MR 合回 develop"),
                rule(repoId, now, "fix", "fix/*", "develop", "develop", false, false,
                        "修复分支：基于 develop 拉出，完成后经 MR 合回 develop"),
                rule(repoId, now, "poc", "poc/*", null, null, true, true,
                        "技术验证分支：可基于任意分支拉出，允许直接推送，验证后归档或删除，不设固定合入目标")
        );
    }

    private static BranchRule rule(UUID repoId, Instant now, String branchType, String namePattern,
                                   String baseBranch, String mergeTarget,
                                   boolean allowDirectPush, boolean autoDelete, String description) {
        BranchRule r = new BranchRule();
        r.setRepoId(repoId);
        r.setBranchType(branchType);
        r.setNamePattern(namePattern);
        r.setBaseBranch(baseBranch);
        r.setMergeTarget(mergeTarget);
        r.setAllowDirectPush(allowDirectPush);
        r.setAutoDeleteAfterMerge(autoDelete);
        r.setDescription(description);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }
}
