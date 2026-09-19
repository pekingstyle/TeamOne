package cn.teamone.eng.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import cn.teamone.eng.domain.BranchProtection;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.BranchProtectionRepository;
import cn.teamone.eng.repo.RepositoryRepository;

/**
 * 分支保护规则种子（ApplicationRunner，幂等）。
 *
 * <p>背景：V12 的保护规则种子是「仓库存在才插入」的迁移期条件块——与 V11 的 MR 种子
 * 同因（本地库首次迁移早于 Git 仓库初始化）被跳过，导致默认分支处于零保护裸奔状态
 * （UT-32 复盘时实测：DELETE 默认分支未被 409 拦截）。本种子在运行期兜底：
 * 仓库一条保护规则都没有时，为其默认分支补一条平台标准规则；已有规则则不动。</p>
 */
@Component
public class BranchProtectionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BranchProtectionSeeder.class);

    private final RepositoryRepository repositoryRepo;
    private final BranchProtectionRepository protectionRepo;

    public BranchProtectionSeeder(RepositoryRepository repositoryRepo, BranchProtectionRepository protectionRepo) {
        this.repositoryRepo = repositoryRepo;
        this.protectionRepo = protectionRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Repository repo : repositoryRepo.findAll()) {
            String defaultBranch = repo.getDefaultBranch() == null || repo.getDefaultBranch().isBlank()
                    ? "main" : repo.getDefaultBranch();
            if (!protectionRepo.findByRepoId(repo.getId()).isEmpty()) {
                continue; // 已有规则（管理端配置或 V12 正常种子），不叠加
            }
            BranchProtection p = new BranchProtection();
            p.setRepoId(repo.getId());
            p.setBranchPattern(defaultBranch);
            p.setRequireMr(true);
            p.setMinApprovals(1);
            p.setRequireUnitTest(true);
            p.setBlockForcePush(true);
            protectionRepo.save(p);
            log.info("[protection-seed] 仓库 {} 默认分支 {} 无保护规则，已补平台标准规则（MR+1 批准+单测门禁+禁强推）",
                    repo.getRepoPath(), defaultBranch);
        }
    }
}
