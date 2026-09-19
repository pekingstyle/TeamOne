package cn.teamone.eng.app;

import cn.teamone.eng.domain.MergeCheck;
import cn.teamone.eng.domain.PipelineJob;
import cn.teamone.eng.domain.PipelineRun;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.JobDto;
import cn.teamone.eng.dto.PipelineJobDto;
import cn.teamone.eng.dto.PipelineRunResponse;
import cn.teamone.eng.dto.StageDto;
import cn.teamone.eng.dto.TriggerPipelineRequest;
import cn.teamone.eng.dto.UnitTestReportRequest;
import cn.teamone.eng.infra.git.GitCommit;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.MergeCheckRepository;
import cn.teamone.eng.repo.PipelineJobRepository;
import cn.teamone.eng.repo.PipelineRunRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 自研流水线业务服务（07 §2.4 / 05 §2.3 / M2-INC-3 V-16；M4-INC1 真实执行引擎）。
 *
 * <p>执行模式（灰度开关 {@code teamone.pipeline.simulated}，默认 false=真实执行）：
 * <ul>
 *   <li>真实执行：trigger 时 {@link BuildSystemDetector} 识别构建体系落 run 行
 *     （unknown 直接 failed 不建作业）→ 建首作业（seq=1, build）→ 内嵌 Runner
 *     （{@code cn.teamone.eng.runner.PipelineRunner}，SKIP LOCKED 认领）异步执行
 *     mvn/npm 真实命令 → {@link #completeJobAndAdvance} 链式推进（build 成功建 test）
 *     → run 聚合收口 + stages jsonb 展示快照回写 → surefire 聚合真实回填 MR 门禁
 *     （{@link #feedMrGateIfLinkedReal}）。</li>
 *   <li>模拟路径（true，IT/降级）：executePipelineStages 同步模拟恒 passed +
 *     演示数据回填门禁（不建作业行，前端 jobs 恒空）。</li>
 * </ul></p>
 *
 * <p>ACL 接入（⑥j-A M-b B1/B4 · docs/v2/13 §2.2/§5.2）：流水线权限锚点 = run 关联仓库
 * （§2.1）。{@code /repos/{idOrName}/pipelines/trigger} 走切面（trigger-pipeline，控制器标注）；
 * 无 /repos 前缀端点（GET /pipelines、GET /pipelines/{id}、POST /pipelines/{id}/rerun）
 * 在本服务层回退断言——查询→view、rerun→trigger-pipeline；GET /pipelines 跨仓列表对
 * PRIVATE 仓结果集逐仓剔除（§5.2 清单行「按仓库可见性过滤结果集」，B4）。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRunRepository pipelineRepo;
    private final RepositoryRepository repositoryRepo;
    private final GitPort gitPort;
    private final MergeRequestService mergeRequestService;
    private final RepoPermChecker permChecker;
    private final PipelineJobRepository jobRepo;
    private final MergeCheckRepository checkRepo;
    private final BuildSystemDetector buildSystemDetector;
    /** 灰度开关：true=旧模拟路径（executePipelineStages 恒 passed，IT/降级用）；false=真实执行（默认） */
    private final boolean simulated;

    public PipelineService(
            PipelineRunRepository pipelineRepo,
            RepositoryRepository repositoryRepo,
            GitPort gitPort,
            MergeRequestService mergeRequestService,
            RepoPermChecker permChecker,
            PipelineJobRepository jobRepo,
            MergeCheckRepository checkRepo,
            BuildSystemDetector buildSystemDetector,
            @Value("${teamone.pipeline.simulated:false}") boolean simulated) {
        this.pipelineRepo = pipelineRepo;
        this.repositoryRepo = repositoryRepo;
        this.gitPort = gitPort;
        this.mergeRequestService = mergeRequestService;
        this.permChecker = permChecker;
        this.jobRepo = jobRepo;
        this.checkRepo = checkRepo;
        this.buildSystemDetector = buildSystemDetector;
        this.simulated = simulated;
    }

    /**
     * 根据仓库 UUID 或名称解析获取仓库实体。
     *
     * @param idOrName 仓库 UUID 或名称（如 "teamone"）
     * @return 仓库实体
     * @throws BusinessException 当参数为空或仓库不存在时抛出
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
     * 分页查询流水线运行历史记录。
     * <p>
     * 支持多维度组合过滤：
     * <ul>
     *   <li>repoIdOrName：指定仓库过滤（为空时全局跨仓查询）</li>
     *   <li>status：按运行状态过滤（如 "passed", "failed", "running"，"all" 或空表示不过滤）</li>
     * </ul>
     * </p>
     *
     * <p>ACL（M-b B1/B4）：指定仓库时按单仓读口径断言 view（无 view → 403，§5.2）；
     * 跨仓列表对 PRIVATE 仓运行记录逐仓剔除（repoId 去重后逐仓 view 判定；平台管理员整体跳过）。
     * 现网无 PRIVATE 数据 = 行为零变化，仅过滤路径就位。简化口径（挂账）：剔除不重分页，
     * total 按剔除数等量下调；精确分页需查询侧 join 可见性，v2.1 评估。</p>
     *
     * @param me           当前登录用户（服务层回退判定主体；null 为防御分支，不过滤不断言）
     * @param repoIdOrName 可选的仓库 ID 或名称
     * @param status       可选的状态过滤
     * @param page         当前页码（从 1 开始）
     * @param size         每页大小
     * @return 包含仓库名与阶段轨道概要的分页流水线运行记录
     */
    @Transactional(readOnly = true)
    public Page<PipelineRunResponse> listPipelines(AppUser me, String repoIdOrName, String status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), Math.max(1, size));
        Page<PipelineRun> runs;
        Map<UUID, String> repoNames = new HashMap<>();

        if (repoIdOrName != null && !repoIdOrName.isBlank()) {
            Repository repo = findRepo(repoIdOrName);
            // 单仓过滤形态：读端点收口口径（§5.2「无 view → 403（单仓）」）
            requireRepoPerm(me, repo.getId(), RepoActions.VIEW);
            repoNames.put(repo.getId(), repo.getName());
            if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                runs = pipelineRepo.findByRepoIdAndStatusOrderByCreatedAtDesc(repo.getId(), status.toLowerCase(), pageable);
            } else {
                runs = pipelineRepo.findByRepoIdOrderByCreatedAtDesc(repo.getId(), pageable);
            }
        } else {
            if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                runs = pipelineRepo.findByStatusOrderByCreatedAtDesc(status.toLowerCase(), pageable);
            } else {
                runs = pipelineRepo.findAll(pageable);
            }
        }

        // B4 跨仓 PRIVATE 过滤：逐仓 view 判定（repoId 去重；平台管理员/无主体整体跳过）
        Set<UUID> hiddenRepos = hiddenRepos(me, runs.getContent());
        List<PipelineRunResponse> visible = new ArrayList<>(runs.getContent().size());
        for (PipelineRun r : runs.getContent()) {
            String repoName = repoNames.computeIfAbsent(r.getRepoId(), id ->
                    repositoryRepo.findById(id).map(Repository::getName).orElse(""));
            if (!hiddenRepos.contains(r.getRepoId())) {
                visible.add(toResponse(r, repoName));
            }
        }
        if (visible.size() == runs.getContent().size()) {
            return new PageImpl<>(visible, pageable, runs.getTotalElements());
        }
        // 简化口径：total 等量下调（本页剔除数），不重取页
        return new PageImpl<>(visible, pageable, runs.getTotalElements() - (runs.getContent().size() - visible.size()));
    }

    /** 结果集中对当前用户不可见（无 view）的仓库集合（repoId 去重判定） */
    private Set<UUID> hiddenRepos(AppUser me, List<PipelineRun> runs) {
        if (me == null || RepoPermChecker.isPlatformAdmin(me) || runs.isEmpty()) {
            return Set.of();
        }
        Set<UUID> hidden = new HashSet<>();
        Set<UUID> judged = new HashSet<>();
        for (PipelineRun r : runs) {
            if (judged.add(r.getRepoId()) && !permChecker.check(me.getId(), r.getRepoId(), RepoActions.VIEW)) {
                hidden.add(r.getRepoId());
            }
        }
        return hidden;
    }

    /**
     * 根据流水线运行主键 ID 获取详细执行记录（含全部阶段轨道与控制台输出日志）。
     *
     * <p>ACL（M-b B1）：view（服务层回退：run→关联仓库解析，§5.2「GET /pipelines/{id}
     * 无 view → 403」；me=null 为防御分支不断言，/api/** 已 authenticated）。</p>
     *
     * @param me 当前登录用户
     * @param id 流水线记录的唯一标识 UUID
     * @return 包含构建阶段、各作业步骤执行状态与实时控制台日志的完整流水线快照
     * @throws BusinessException 当记录不存在时抛出
     */
    @Transactional(readOnly = true)
    public PipelineRunResponse getPipeline(AppUser me, UUID id) {
        PipelineRun r = pipelineRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "流水线运行不存在: " + id));
        requireRepoPerm(me, r.getRepoId(), RepoActions.VIEW);
        String repoName = repositoryRepo.findById(r.getRepoId()).map(Repository::getName).orElse("");
        // 详情形态：契约加 jobs（真实执行作业行；历史模拟 run 恒空）
        return toDetailResponse(r, repoName);
    }

    /**
     * 触发执行新的自研流水线作业。
     * <p>
     * 核心步骤：
     * <ol>
     *   <li>定位目标仓库实体并获取仓库默认分支配置</li>
     *   <li>解析待执行分支与 Commit SHA（未指定 commitSha 时通过 GitPort 实时提取该分支 HEAD 提交）</li>
     *   <li>自动生成流水线递增编号标题（例如 "#103 · main 自动化构建与测试"）</li>
     *   <li>初始化流水线运行上下文，设定 trigger 类型与关联 MR（若有）</li>
     *   <li>按灰度开关分叉：simulated=true 走 executePipelineStages 同步模拟 + 演示数据回填门禁；
     *       默认（真实执行）识别构建体系落 run 行（unknown 直接 failed 不建作业），建首作业
     *       seq=1（stage=build），交内嵌 Runner 异步链式推进（build 成功建 test，终态聚合收口
     *       + stages 快照回写 + surefire 真实回填 MR 门禁）</li>
     * </ol>
     * </p>
     *
     * @param repoIdOrName   目标仓库 ID 或名称
     * @param req            触发参数（分支、Commit、触发源、关联 MR）
     * @param triggerUserId  触发操作的用户 ID
     * @return 执行后的流水线记录快照（真实模式返回 pending/running 态——执行是异步的）
     */
    @Transactional
    public PipelineRunResponse triggerPipeline(String repoIdOrName, TriggerPipelineRequest req, UUID triggerUserId) {
        Repository repo = findRepo(repoIdOrName);

        // 步骤 1：确定目标分支（缺省使用仓库主分支）
        String branch = (req != null && req.branch() != null && !req.branch().isBlank())
                ? req.branch().trim()
                : repo.getDefaultBranch();

        // 步骤 2：获取待测试的 Git Commit SHA，未提供时通过 GitPort 查询当前分支最新提交
        String commitSha = (req != null && req.commitSha() != null && !req.commitSha().isBlank())
                ? req.commitSha().trim()
                : null;

        if (commitSha == null) {
            try {
                List<GitCommit> commits = gitPort.commits(repo.getRepoPath(), branch, 1, 1);
                if (!commits.isEmpty()) {
                    commitSha = commits.get(0).sha();
                }
            } catch (Exception e) {
                log.warn("[pipeline] failed to fetch latest commit for branch {}: {}", branch, e.getMessage());
            }
        }
        if (commitSha == null) {
            commitSha = "0000000000000000000000000000000000000000";
        }
        String commitShort = commitSha.length() >= 7 ? commitSha.substring(0, 7) : commitSha;

        // 步骤 3：确定触发源（manual/push/mr/schedule）
        String trigger = (req != null && req.trigger() != null && !req.trigger().isBlank())
                ? req.trigger().trim().toLowerCase()
                : "manual";

        // 步骤 4：生成易于辨认的流水线序数标题
        long totalRuns = pipelineRepo.countByRepoId(repo.getId());
        String title = "#" + (totalRuns + 101) + " · " + branch + " 自动化构建与测试";

        PipelineRun run = new PipelineRun();
        run.setRepoId(repo.getId());
        run.setTitle(title);
        run.setBranch(branch);
        run.setCommitSha(commitSha);
        run.setCommitShort(commitShort);
        run.setTrigger(trigger);
        if (req != null && req.mrId() != null) {
            run.setMrId(req.mrId());
        }
        run.setTriggerUserId(triggerUserId);
        run.setStartedAt(Instant.now());

        // 步骤 5~7（灰度分叉，teamone.pipeline.simulated）：
        //   true  = 旧模拟路径——executePipelineStages 同步模拟 + 演示数据回填 MR 门禁（IT/降级）
        //   false = 真实执行（默认）——识别构建体系落 run 行，建首个作业，Runner 异步链式推进
        if (simulated) {
            executePipelineStages(run, repo);
            run = pipelineRepo.save(run);
            feedMrGateIfLinked(run);
            return toResponse(run, repo.getName());
        }

        BuildSystemDetector.Result detected = buildSystemDetector.detect(repo.getRepoPath(), branch);
        run.setBuildSystem(detected.buildSystem());
        run.setWorkdir(detected.workdir());

        if (detected.isUnknown()) {
            // 未识别构建体系：run 直接 failed（不建作业），错误注明于 stages 快照 summary
            run.setStatus("failed");
            run.setStages(unknownBuildSystemStages());
            run.setFinishedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            run = pipelineRepo.save(run);
            log.warn("[pipeline] run={} 构建体系未识别（branch={}），直接置 failed", run.getId(), branch);
            return toDetailResponse(run, repo.getName());
        }

        run.setStatus("pending");
        run = pipelineRepo.save(run);
        createNextJob(run, "build"); // 首作业 seq=1（stage=build），Runner 认领后链式推进
        return toDetailResponse(run, repo.getName());
    }

    /**
     * 重新执行（Rerun）指定的流水线。
     *
     * <p>ACL（M-b B1）：trigger-pipeline（§2.2「手动触发 / rerun 流水线」，Developer+；
     * 服务层回退：run→关联仓库解析；me=null 为防御分支不断言）。</p>
     *
     * @param me            当前登录用户
     * @param id            流水线记录 ID
     * @param triggerUserId 重新运行的发起人用户 ID
     * @return 重新执行后的流水线详情
     */
    @Transactional
    public PipelineRunResponse rerunPipeline(AppUser me, UUID id, UUID triggerUserId) {
        PipelineRun run = pipelineRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "流水线运行不存在: " + id));
        Repository repo = repositoryRepo.findById(run.getRepoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在"));
        requireRepoPerm(me, run.getRepoId(), RepoActions.TRIGGER_PIPELINE);

        run.setTriggerUserId(triggerUserId);
        run.setStartedAt(Instant.now());

        // 灰度分叉：true=旧模拟路径同步重跑；false=真实执行——重置作业链后重建首作业
        if (simulated) {
            executePipelineStages(run, repo);
            run = pipelineRepo.save(run);
            feedMrGateIfLinked(run);
            return toResponse(run, repo.getName());
        }

        // 历史 run（模拟期创建）无识别结果，rerun 时补识别
        if (run.getBuildSystem() == null || run.getBuildSystem().isBlank()) {
            BuildSystemDetector.Result detected = buildSystemDetector.detect(repo.getRepoPath(), run.getBranch());
            run.setBuildSystem(detected.buildSystem());
            run.setWorkdir(detected.workdir());
        }

        run.setStatus("pending");
        run.setDurationSec(0);
        run.setFinishedAt(null);
        run.setStages(new ArrayList<>());

        if ("unknown".equals(run.getBuildSystem())) {
            run.setStatus("failed");
            run.setStages(unknownBuildSystemStages());
            run.setFinishedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            run = pipelineRepo.save(run);
            return toDetailResponse(run, repo.getName());
        }

        run = pipelineRepo.save(run);
        // 重置作业链：清掉旧作业（含其锁与日志），从 seq=1 重新起链
        jobRepo.deleteByRunId(run.getId());
        jobRepo.flush();
        createNextJob(run, "build");
        return toDetailResponse(run, repo.getName());
    }

    // ==================== 内部 ====================

    /** 服务层回退断言（§4.4 定位器②）：run→仓库归一后走仓库五步链；me=null 防御分支不断言 */
    private void requireRepoPerm(AppUser me, UUID repoId, String action) {
        if (me == null) {
            return;
        }
        permChecker.require(me.getId(), repoId, action);
    }

    /**
     * 模拟流水线各个阶段（Stages）与作业（Jobs）的顺序执行与日志产出。
     * <p>
     * 包含三个核心阶段：
     * 1. 构建阶段 (Build)：Maven 编译打包与产物生成；
     * 2. 测试门禁 (Test Gate)：单元测试、架构合规测试（ArchUnit）与覆盖率统计，结果直接关联 MR 合并拦截门禁；
     * 3. 代码质量扫描 (Quality)：静态代码检查、SonarQube 规则审计与安全密钥扫描。
     * </p>
     *
     * @param run  流水线实体对象，将被回填各阶段状态与执行耗时
     * @param repo 运行所针对的目标仓库实体
     */
    private void executePipelineStages(PipelineRun run, Repository repo) {
        List<Map<String, Object>> stages = new ArrayList<>();

        // 阶段 1: 编译与构建 (Build)
        Map<String, Object> stage1 = new HashMap<>();
        stage1.put("name", "构建阶段 (Build)");
        stage1.put("status", "passed");
        stage1.put("durationSec", 42);
        stage1.put("jobs", List.of(Map.of(
                "id", "j-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Maven Compile & Package",
                "status", "passed",
                "durationSec", 42,
                "logs", List.of(
                        "[INFO] Scanning for projects in " + repo.getName(),
                        "[INFO] Building TeamOne Kernel Artifacts",
                        "[INFO] Compiling modules with Java 17...",
                        "[INFO] BUILD SUCCESS"
                )
        )));
        stages.add(stage1);

        // 阶段 2: 测试门禁 (Test Gate - 核心门禁环节，产出单测与增量行覆盖率)
        Map<String, Object> stage2 = new HashMap<>();
        stage2.put("name", "测试门禁 (Test Gate)");
        stage2.put("status", "passed");
        stage2.put("durationSec", 58);
        stage2.put("jobs", List.of(Map.of(
                "id", "j-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "JUnit & ArchUnit Suite",
                "status", "passed",
                "durationSec", 58,
                "logs", List.of(
                        "[INFO] Executing automated tests...",
                        "[INFO] ArchUnit 8/8 rules passed without violations",
                        "[INFO] Tests run: 82, Failures: 0, Errors: 0, Skipped: 0",
                        "[INFO] Total Line Coverage: 82.5% (Gate threshold: >=60.0%)",
                        "[INFO] Patch Coverage: 88.0% (Gate threshold: >=80.0%)",
                        "[INFO] Test Gate PASSED"
                )
        )));
        stages.add(stage2);

        // 阶段 3: 代码质量与安全扫描 (Quality Gate)
        Map<String, Object> stage3 = new HashMap<>();
        stage3.put("name", "代码质量扫描 (Quality)");
        stage3.put("status", "passed");
        stage3.put("durationSec", 21);
        stage3.put("jobs", List.of(Map.of(
                "id", "j-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "SonarQube & Security Audit",
                "status", "passed",
                "durationSec", 21,
                "logs", List.of(
                        "[INFO] Running static code inspection...",
                        "[INFO] 0 security vulnerabilities, 0 hardcoded secrets",
                        "[INFO] Quality Gate passed"
                )
        )));
        stages.add(stage3);

        // 汇总执行阶段与状态
        run.setStages(stages);
        run.setStatus("passed");
        run.setDurationSec(42 + 58 + 21);
        run.setFinishedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
    }

    /**
     * 若当前流水线由合并请求（Merge Request）触发，则将单测与覆盖率报告反哺回填给 MR，
     * 自动使 MR 门禁中的单测卡点（Gate）达成并通过。
     *
     * <p>【仅模拟路径】演示数据（82 用例 / 82.5% / 88.0%）硬编码——真实路径走
     * {@link #feedMrGateIfLinkedReal(UUID, TestSummary)}（surefire 真实聚合 + 沿用既有覆盖率）。</p>
     *
     * @param run 流水线实体
     */
    private void feedMrGateIfLinked(PipelineRun run) {
        if (run.getMrId() != null && "passed".equals(run.getStatus())) {
            try {
                // 构造单测门禁请求包（包含全量与增量覆盖率，以及指向当前流水线详情的链接）
                UnitTestReportRequest report = new UnitTestReportRequest(
                        String.valueOf(run.getMrId()),
                        run.getCommitSha(),
                        true,
                        82,
                        0,
                        82.5,
                        88.0,
                        "/pipelines/" + run.getId()
                );
                mergeRequestService.uploadUnitTestReport(run.getMrId(), report);
                log.info("[pipeline] successfully fed test results to MR !{}", run.getMrId());
            } catch (Exception e) {
                log.warn("[pipeline] failed to feed test results to MR !{}: {}", run.getMrId(), e.getMessage());
            }
        }
    }

    // ==================== 真实执行（M4-INC1）：作业链 / 收口 / 门禁真实化 ====================

    /**
     * 真实单测聚合摘要（Runner 解析 surefire TEST-*.xml 后回传；tests>0 才驱动 MR 门禁回填）。
     *
     * @param tests       用例总数
     * @param errors      错误数
     * @param failures    失败数
     * @param skipped     跳过数
     * @param reportFiles 实际解析的报告文件数（0=无报告，不动 MR 门禁）
     */
    public record TestSummary(int tests, int errors, int failures, int skipped, int reportFiles) {

        public boolean hasReports() {
            return reportFiles > 0;
        }

        /** 门禁通过判定：有用例且零错误零失败（docs/v2/11 §3.4 最小口径） */
        public boolean gatePassed() {
            return tests > 0 && errors == 0 && failures == 0;
        }
    }

    /**
     * 链式建下一作业（run 内 seq 递增）。
     *
     * <p>调用点：真实 trigger（seq=1, build）、rerun 重置后（seq=1, build）、build 作业成功后
     * （seq+1, test）。cmd/workdir 由 {@link PipelineJobTemplates} 按 run.buildSystem 生成。</p>
     *
     * @param run   运行实体（须已持久化）
     * @param stage build / test
     * @return 新建的作业实体
     */
    private PipelineJob createNextJob(PipelineRun run, String stage) {
        int nextSeq = jobRepo.findFirstByRunIdOrderBySeqDesc(run.getId())
                .map(PipelineJob::getSeq)
                .orElse(0) + 1;
        PipelineJob job = new PipelineJob();
        job.setRunId(run.getId());
        job.setSeq(nextSeq);
        job.setStage(stage);
        job.setName(PipelineJobTemplates.jobName(stage));
        job.setCmd(PipelineJobTemplates.cmd(run.getBuildSystem(), stage, run.getWorkdir()));
        job.setWorkdir(run.getWorkdir());
        job.setStatus("pending");
        return jobRepo.save(job);
    }

    /**
     * Runner 收口单个作业并推进链（M4-INC1 · docs/v2/11 §3.1 链式推进）。
     *
     * <p>时序：Runner 执行完进程（含超时/工具缺失失败）与 surefire 解析后调用本方法。
     * 单事务内完成：作业终态落库 → 成功且 build 则建 test 作业（run 保持 running）→
     * 否则收口 run（任一 failed → failed，全 success → passed）并回写 stages jsonb
     * 展示快照。幂等：仅 running 态作业可收口（重复回调/认领竞态直接跳过）。</p>
     *
     * @param jobId       作业 ID
     * @param exitCode    进程退出码（超时/未启动为 null，视为失败）
     * @param logTail     合流输出尾部文本（截 120 行落库）
     * @param errorMsg    失败原因（工具链缺失/超时/工作区导出失败等；成功为 null）
     * @param testSummary test 阶段的 surefire 聚合（非 test 阶段/无报告为 null）
     * @return run 是否已在本调用收口（Runner 据此决定是否回填 MR 门禁）
     */
    @Transactional
    public boolean completeJobAndAdvance(UUID jobId, Integer exitCode, String logTail, String errorMsg,
                                         TestSummary testSummary) {
        PipelineJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null || !"running".equals(job.getStatus())) {
            return false; // 幂等保护：仅 running 可收口
        }
        boolean success = exitCode != null && exitCode == 0 && errorMsg == null;
        job.setStatus(success ? "success" : "failed");
        job.setExitCode(exitCode);
        job.setLogTail(tailLines(logTail, 120));
        job.setErrorMsg(errorMsg);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);

        PipelineRun run = pipelineRepo.findById(job.getRunId()).orElse(null);
        if (run == null) {
            return false;
        }
        if (success && "build".equals(job.getStage())) {
            // 构建成功 → 链式建 test 作业，run 保持执行中。
            // 建链失败（QA 复审 NICE-4）：按 failed 终结 run 而非让收口事务整体回滚——
            // 否则作业滞留 running 只能等实例重启重派
            run.setStatus("running");
            run.setUpdatedAt(Instant.now());
            pipelineRepo.save(run);
            try {
                createNextJob(run, "test");
                log.info("[pipeline] run={} 构建（seq={}）成功，链式创建单测作业", run.getId(), job.getSeq());
            } catch (Exception e) {
                log.error("[pipeline] run={} 链式创建单测作业失败，run 按 failed 终结: {}", run.getId(), e.getMessage(), e);
                job.setErrorMsg("链式创建单测作业失败: " + e.getMessage());
                jobRepo.save(job);
                run.setStatus("failed");
                run.setUpdatedAt(Instant.now());
                pipelineRepo.save(run);
            }
            return false;
        }
        finalizeRun(run, testSummary);
        return true;
    }

    /**
     * 收口 run：作业聚合状态 + stages jsonb 展示快照回写（保持既有展示字段形状
     * name/status/durationSec/jobs[id|name|status|durationSec|logs]，另附 summary 注记）。
     *
     * @param run          运行实体
     * @param testSummary  test 阶段 surefire 聚合（可为 null：无报告/npm 体系/构建失败早收）
     */
    private void finalizeRun(PipelineRun run, TestSummary testSummary) {
        List<PipelineJob> jobs = jobRepo.findByRunIdOrderBySeqAsc(run.getId());
        boolean anyFailed = false;
        long durationSec = 0;
        for (PipelineJob job : jobs) {
            anyFailed |= "failed".equals(job.getStatus());
            durationSec += durationSec(job);
        }
        run.setStatus(anyFailed ? "failed" : "passed");
        run.setDurationSec((int) Math.min(durationSec, Integer.MAX_VALUE));
        run.setFinishedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        run.setStages(stagesSnapshot(jobs, testSummary));
        pipelineRepo.save(run);
        log.info("[pipeline] run={} 收口 status={} jobs={}（任一 failed→failed）",
                run.getId(), run.getStatus(), jobs.size());
    }

    /** 由作业结果重建 stages jsonb 展示快照（真实模式回写形状） */
    private List<Map<String, Object>> stagesSnapshot(List<PipelineJob> jobs, TestSummary testSummary) {
        List<Map<String, Object>> stages = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byStage = new LinkedHashMap<>();
        for (PipelineJob job : jobs) {
            Map<String, Object> j = new HashMap<>();
            j.put("id", String.valueOf(job.getId()));
            j.put("name", job.getName());
            j.put("status", displayStatus(job.getStatus()));
            j.put("durationSec", durationSec(job));
            j.put("logs", job.getLogTail() == null ? List.of() : job.getLogTail().lines().toList());
            byStage.computeIfAbsent(job.getStage(), k -> new ArrayList<>()).add(j);
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : byStage.entrySet()) {
            boolean stageFailed = e.getValue().stream().anyMatch(j -> "failed".equals(j.get("status")));
            boolean stageRunning = e.getValue().stream().anyMatch(j -> "running".equals(j.get("status")));
            int stageDur = e.getValue().stream()
                    .mapToInt(j -> j.get("durationSec") instanceof Number n ? n.intValue() : 0).sum();
            Map<String, Object> stage = new HashMap<>();
            stage.put("name", "test".equals(e.getKey()) ? "测试门禁 (Test Gate)" : "构建阶段 (Build)");
            stage.put("status", stageFailed ? "failed" : (stageRunning ? "running" : "passed"));
            stage.put("durationSec", stageDur);
            stage.put("jobs", e.getValue());
            stage.put("summary", stageSummary(e.getKey(), e.getValue(), testSummary));
            stages.add(stage);
        }
        return stages;
    }

    /** 阶段级注记：作业退出码 + 单测聚合（surefire）或无报告说明 */
    private String stageSummary(String stage, List<Map<String, Object>> jobMaps, TestSummary testSummary) {
        Object firstStatus = jobMaps.isEmpty() ? null : jobMaps.get(jobMaps.size() - 1).get("status");
        StringBuilder sb = new StringBuilder();
        sb.append(jobMaps.size()).append(" 个作业，末态 ").append(firstStatus);
        if ("test".equals(stage)) {
            if (testSummary != null && testSummary.hasReports()) {
                sb.append("；Tests run: ").append(testSummary.tests())
                        .append(", Failures: ").append(testSummary.failures())
                        .append(", Errors: ").append(testSummary.errors())
                        .append(", Skipped: ").append(testSummary.skipped());
            } else {
                sb.append("；未发现 surefire 报告（MR 门禁未回填，npm 体系不解析 surefire）");
            }
        }
        return sb.toString();
    }

    /** 未识别构建体系的 run 快照：单阶段 failed + 错误注记（无作业行） */
    private List<Map<String, Object>> unknownBuildSystemStages() {
        Map<String, Object> stage = new HashMap<>();
        stage.put("name", "构建阶段 (Build)");
        stage.put("status", "failed");
        stage.put("durationSec", 0);
        stage.put("jobs", List.of());
        stage.put("summary", "未识别构建体系：根目录与一级子目录均未发现 pom.xml/package.json（Dockerfile 不计构建），请配置构建方式或联系管理员");
        return new ArrayList<>(List.of(stage));
    }

    /** 作业级 DB 状态 → 既有展示状态词表（success→passed，其余直通） */
    private static String displayStatus(String jobStatus) {
        return "success".equals(jobStatus) ? "passed" : jobStatus;
    }

    /** 作业耗时（秒；未开始/未收口按 0） */
    private static int durationSec(PipelineJob job) {
        if (job.getStartedAt() == null || job.getFinishedAt() == null) {
            return 0;
        }
        return (int) Math.max(0, ChronoUnit.SECONDS.between(job.getStartedAt(), job.getFinishedAt()));
    }

    /** 尾部 n 行截断（真实执行日志落库形状：log_tail=尾部 ~120 行文本） */
    private static String tailLines(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return text;
        }
        List<String> lines = text.lines().toList();
        if (lines.size() <= maxLines) {
            return text;
        }
        return String.join("\n", lines.subList(lines.size() - maxLines, lines.size()));
    }

    /**
     * R8 门禁真实化（最小版 · docs/v2/11 §3.4）：以 surefire 聚合结果回填关联 MR 单测门禁。
     *
     * <p>passed=(errors+failures==0 && tests&gt;0)；coverageTotal/coveragePatch 沿用该 MR
     * mr_check payload 既有值回填（真实 patch/整体覆盖率计算归 M4-INC2，reportUrl 注明）；
     * 独立事务、失败只记 WARN——门禁回填失败不影响作业/run 已收口状态。
     * 无关联 MR / 无报告（tests==0）→ 不动门禁，返回 false。</p>
     *
     * @param runId   运行 ID
     * @param summary surefire 聚合摘要
     * @return 是否实际回填
     */
    @Transactional
    public boolean feedMrGateIfLinkedReal(UUID runId, TestSummary summary) {
        if (summary == null || !summary.hasReports() || summary.tests() <= 0) {
            return false; // 识别失败/无报告：不动 MR 门禁（仅 stages summary 注记）
        }
        PipelineRun run = pipelineRepo.findById(runId).orElse(null);
        if (run == null || run.getMrId() == null) {
            return false;
        }
        try {
            // 沿用既有覆盖率：从 mr_check payload 读旧值（首次上报无旧值时按 0 透出——门禁不误通过）
            Map<String, Object> old = checkRepo.findByMrIdAndKind(run.getMrId(), "unit_test")
                    .map(MergeCheck::getPayload)
                    .orElse(Map.of());
            double coverageTotal = asDouble(old.get("coverageTotal"));
            double coveragePatch = asDouble(old.get("coverageDelta"));
            UnitTestReportRequest report = new UnitTestReportRequest(
                    String.valueOf(run.getMrId()),
                    run.getCommitSha(),
                    summary.gatePassed(),
                    summary.tests(),
                    summary.failures() + summary.errors(),
                    coverageTotal,
                    coveragePatch,
                    "/pipelines/" + run.getId() + " （coverage=simulated(真实计算 M4-INC2);tests=surefire）"
            );
            mergeRequestService.uploadUnitTestReport(run.getMrId(), report);
            log.info("[pipeline] run={} 以 surefire 真实数据回填 MR !{} 门禁（tests={} failed={}）",
                    run.getId(), run.getMrId(), summary.tests(), summary.failures() + summary.errors());
            return true;
        } catch (Exception e) {
            log.warn("[pipeline] failed to feed surefire results to MR !{}: {}", run.getMrId(), e.getMessage());
            return false;
        }
    }

    /** payload 数值容错读取（jsonb 数字可能是 Number/字符串，缺省 0.0） */
    private static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // 缺省 0
            }
        }
        return 0.0;
    }

    /**
     * 将数据库持久化实体 PipelineRun 转换为 API 响应对象 PipelineRunResponse。
     * 内部负责反序列化 stages JSONB 结构为类型安全的 StageDto 和 JobDto 列表。
     * （包内可见：R-9 版本交付联动 ReleaseDeliveryService 复用同一解析口径）
     *
     * <p>列表形态：jobs 不装填（避免列表 N+1；契约只要求 buildSystem），Jackson non_null 缺省。</p>
     *
     * @param r        流水线实体对象
     * @param repoName 仓库名
     * @return 结构化的流水线响应 DTO
     */
    @SuppressWarnings("unchecked")
    PipelineRunResponse toResponse(PipelineRun r, String repoName) {
        return toResponse(r, repoName, null);
    }

    /**
     * 详情形态（GET /pipelines/{id}、trigger/rerun 同步响应）：在列表形态外加真实执行作业行
     * （契约：{"id","stage","name","status","exitCode","startedAt","finishedAt","logTail"}）。
     * 历史模拟 run 无作业行 → jobs 为空列表（前端缺省安全）。
     */
    PipelineRunResponse toDetailResponse(PipelineRun r, String repoName) {
        List<PipelineJobDto> jobs = jobRepo.findByRunIdOrderBySeqAsc(r.getId()).stream()
                .map(j -> new PipelineJobDto(
                        j.getId(), j.getStage(), j.getName(), j.getStatus(),
                        j.getExitCode(), j.getStartedAt(), j.getFinishedAt(), j.getLogTail(),
                        j.getErrorMsg()))
                .toList();
        return toResponse(r, repoName, jobs);
    }

    /** 通用转换（jobs 可空：列表形态 null / 详情形态空表或作业行） */
    @SuppressWarnings("unchecked")
    private PipelineRunResponse toResponse(PipelineRun r, String repoName, List<PipelineJobDto> jobs) {
        List<StageDto> stageDtos = new ArrayList<>();
        if (r.getStages() != null) {
            // 解析 JSONB 阶段数组
            for (Map<String, Object> s : r.getStages()) {
                String sName = String.valueOf(s.getOrDefault("name", ""));
                String sStatus = String.valueOf(s.getOrDefault("status", "pending"));
                Object sDurVal = s.get("durationSec");
                int sDur = (sDurVal instanceof Number n) ? n.intValue() : 0;

                // 解析当前阶段包含的作业列表 (Jobs)
                List<JobDto> jobDtos = new ArrayList<>();
                Object jobsObj = s.get("jobs");
                if (jobsObj instanceof List<?> jList) {
                    for (Object jObj : jList) {
                        if (jObj instanceof Map<?, ?> rawMap) {
                            Map<String, Object> jMap = (Map<String, Object>) rawMap;
                            String jId = String.valueOf(jMap.getOrDefault("id", ""));
                            String jName = String.valueOf(jMap.getOrDefault("name", ""));
                            String jStatus = String.valueOf(jMap.getOrDefault("status", "pending"));
                            Object durVal = jMap.get("durationSec");
                            int jDur = (durVal instanceof Number nd) ? nd.intValue() : 0;
                            Object logsVal = jMap.get("logs");
                            List<String> jLogs = (logsVal instanceof List<?> l)
                                    ? l.stream().map(String::valueOf).toList()
                                    : List.of();
                            jobDtos.add(new JobDto(jId, jName, jStatus, jDur, jLogs));
                        }
                    }
                }
                stageDtos.add(new StageDto(sName, sStatus, sDur, jobDtos));
            }
        }

        return new PipelineRunResponse(
                r.getId(),
                r.getRepoId(),
                repoName,
                r.getTitle(),
                r.getBranch(),
                r.getCommitSha(),
                r.getCommitShort(),
                r.getTrigger(),
                r.getMrId(),
                r.getStatus(),
                r.getTriggerUserId(),
                r.getDurationSec(),
                stageDtos,
                r.getStartedAt(),
                r.getFinishedAt(),
                r.getCreatedAt(),
                // 列表契约字段：历史/模拟 run（NULL）透出 unknown，前端徽章缺省安全
                r.getBuildSystem() == null ? "unknown" : r.getBuildSystem(),
                jobs
        );
    }
}
