package cn.teamone.eng.seed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import cn.teamone.eng.domain.MergeCheck;
import cn.teamone.eng.domain.MergeRequest;
import cn.teamone.eng.domain.MergeReviewer;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.MergeCheckRepository;
import cn.teamone.eng.repo.MergeRequestRepository;
import cn.teamone.eng.repo.MergeReviewerRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;

/**
 * MR 演示数据种子（ApplicationRunner，幂等）。
 *
 * <p>背景：V11 的 MR 种子是「仓库存在才插入」的迁移期条件块——本地库首次迁移早于 Git 仓库
 * 初始化，两条种子被跳过，真实 MR 列表恒为空（用户实测评审页只能落到前端 mock，
 * UT-33/34 的「评审确认按钮」场景无从演示）。本种子在运行期补位：仓库无任何 MR 时
 * 造三条覆盖关键门禁形态的演示 MR；已有 MR 则整体跳过，绝不叠加。</p>
 *
 * <ul>
 *   <li>!1 open·单测门禁通过·dev1 已批准——「可合并」正例；</li>
 *   <li>!2 open·关联 T-104·无单测门禁不通过·admin/dev1 待评审——登录评审人可见
 *       批准/请求修改按钮的主场景；</li>
 *   <li>!3 draft·admin 待评审——草稿态同样允许评审动作（UT-34 放开后的形态）。</li>
 * </ul>
 *
 * <p>源分支经 {@link GitPort#createBranch} 从默认分支真实创建（已存在则复用），
 * 保证详情页 diff/检查等 git 读取不 404；数值为演示首态，真相以 CI 回传为准。</p>
 *
 * <p>R-13 种子开关一拆二（评审必改①/D7）：本类为<b>演示数据</b>，挂
 * {@code teamone.seed.demo}（默认 true 保持现有行为）；规则类种子
 * （BranchRuleSeeder/BranchProtectionSeeder）不受该开关影响。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "teamone.seed.demo", havingValue = "true", matchIfMissing = true)
public class MrDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MrDemoSeeder.class);

    private final MergeRequestRepository mrRepo;
    private final MergeReviewerRepository reviewerRepo;
    private final MergeCheckRepository checkRepo;
    private final RepositoryRepository repositoryRepo;
    private final AppUserRepository users;
    private final GitPort gitPort;

    public MrDemoSeeder(MergeRequestRepository mrRepo, MergeReviewerRepository reviewerRepo,
                        MergeCheckRepository checkRepo, RepositoryRepository repositoryRepo,
                        AppUserRepository users, GitPort gitPort) {
        this.mrRepo = mrRepo;
        this.reviewerRepo = reviewerRepo;
        this.checkRepo = checkRepo;
        this.repositoryRepo = repositoryRepo;
        this.users = users;
        this.gitPort = gitPort;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 与 V11 同口径守卫：目标仓库不存在（Git 仓库未初始化）则整体跳过
        Repository repo = repositoryRepo.findAll().stream()
                .filter(r -> r.getRepoPath() != null && r.getRepoPath().endsWith("teamone/teamone.git"))
                .findFirst().orElse(null);
        if (repo == null) {
            return;
        }
        if (mrRepo.findMaxMrNumber(repo.getId()) > 0) {
            return; // 已有 MR（真实创建或此前种子），不叠加
        }
        AppUser admin = users.findByUsername("admin").orElse(null);
        AppUser dev1 = users.findByUsername("dev1").orElse(null);
        AppUser dev2 = users.findByUsername("dev2").orElse(null);
        if (admin == null || dev1 == null || dev2 == null) {
            return; // 用户未就绪（DevSeeder 未跑），下轮启动重试
        }

        // 演示源分支（从默认分支创建，已存在则复用）
        String base = repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch();
        for (String b : List.of("feat/pipeline-parallel-scheduler", "fix/receipt-perf", "docs/onboarding-handbook")) {
            try {
                gitPort.createBranch(repo.getRepoPath(), b, base);
            } catch (Exception e) {
                log.debug("[mr-seed] 分支 {} 已存在或创建失败（复用）: {}", b, e.getMessage());
            }
        }

        seedMr(repo, 1, "feat: 流水线引擎并行任务调度优化",
                "并行任务调度改造，关联 T-103。\n\nCloses T-103",
                "feat/pipeline-parallel-scheduler", base, "open", "T-103", admin, false,
                List.of(rev(dev1, "approved"), rev(dev2, "pending")),
                utCheck(true, 78.5, 88.0, null));
        seedMr(repo, 2, "fix: 协同服务已读回执性能调优",
                "已读回执批量合并推送，关联 T-104。\n\nCloses T-104",
                "fix/receipt-perf", base, "open", "T-104", dev2, false,
                List.of(rev(admin, "pending"), rev(dev1, "pending")),
                utCheck(false, 55.0, 0.0, "未检测到关联单测文件（启发式 A 未命中）"));
        seedMr(repo, 3, "docs: 新成员接入手册与术语表",
                "接入流程、分支规范与全站术语表索引。",
                "docs/onboarding-handbook", base, "draft", null, dev1, false,
                List.of(rev(admin, "pending")),
                utCheck(false, 62.0, 0.0, "纯文档变更，未检测到代码单测"));
        log.info("[mr-seed] teamone 仓库演示 MR ×3 就绪（!1 可合并正例 / !2 无单测待评审 / !3 草稿评审）");
    }

    private static MergeReviewer rev(AppUser u, String state) {
        MergeReviewer r = new MergeReviewer();
        r.setUserId(u.getId());
        r.setState(state);
        return r;
    }

    /** 单测门禁检查项（演示首态：名称内嵌的阈值文案与 teamone.mr.gate.* 配置无联动，真相以 CI 回传为准） */
    private MergeCheck utCheck(boolean passed, double total, double patch, String note) {
        MergeCheck c = new MergeCheck();
        c.setKind("unit_test");
        c.setName("单测检测（R8 门禁：整体 ≥60% · patch ≥80%）");
        c.setPassed(passed);
        Map<String, Object> p = new HashMap<>();
        p.put("passed", passed);
        p.put("coverageTotal", total);
        p.put("coveragePatch", patch);
        p.put("hasTests", patch > 0);
        if (note != null) {
            p.put("note", note);
        }
        c.setPayload(p);
        return c;
    }

    private void seedMr(Repository repo, int expectNumber, String title, String description,
                        String sourceBranch, String targetBranch, String status, String linkedKey,
                        AppUser author, boolean rebaseRequired, List<MergeReviewer> reviewers,
                        MergeCheck unitTest) {
        MergeRequest mr = new MergeRequest();
        mr.setRepoId(repo.getId());
        mr.setMrNumber(expectNumber);
        mr.setTitle(title);
        mr.setDescription(description);
        mr.setSourceBranch(sourceBranch);
        mr.setTargetBranch(targetBranch);
        mr.setAuthorId(author.getId());
        mr.setStatus(status);
        mr.setLinkedWorkItemKey(linkedKey);
        mr.setRebaseRequired(rebaseRequired);
        mr = mrRepo.save(mr);
        for (MergeReviewer r : reviewers) {
            r.setMrId(mr.getId());
            reviewerRepo.save(r);
        }
        unitTest.setMrId(mr.getId());
        checkRepo.save(unitTest);
    }
}
