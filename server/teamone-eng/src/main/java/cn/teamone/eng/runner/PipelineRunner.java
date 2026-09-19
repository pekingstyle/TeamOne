package cn.teamone.eng.runner;

import cn.teamone.eng.app.PipelineJobTemplates;
import cn.teamone.eng.app.PipelineService;
import cn.teamone.eng.app.PipelineService.TestSummary;
import cn.teamone.eng.domain.PipelineJob;
import cn.teamone.eng.domain.PipelineRun;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.PipelineJobRepository;
import cn.teamone.eng.repo.PipelineRunRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌流水线 Runner（M4-INC1 真实执行 · docs/v2/11 §4 执行架构最小版）。
 *
 * <p>职责与时序（单作业全链）：@Scheduled fixedDelay 5s 只做<b>认领分发</b>——
 * native {@code SELECT ... FOR UPDATE SKIP LOCKED}（照 OutboxRelay 先例，DB 作业表为
 * 唯一真相）抢一个 pending 作业 → 认领（status=running, locked_by=hostname:pid,
 * started_at, attempt+1）→ 交独立执行线程池（单线程 + 单飞布尔，全局并发 1，不占
 * scheduler 线程）→ {@code git archive} 导出工作区（经 GitPort，R8 纪律：git 只走
 * infra.git 出墙口）→ which 探测工具链 → ProcessBuilder 真实执行模板命令（15 分钟
 * 超时强杀，stdout/stderr 合流尾部 120 行）→ surefire 聚合（test+maven）→
 * {@link PipelineService#completeJobAndAdvance} 收口链式推进（build 成功建 test）→
 * run 终态时 {@link PipelineService#feedMrGateIfLinkedReal} 真实回填 MR 门禁 →
 * finally 整卷回收工作区。</p>
 *
 * <p>灰度：{@code teamone.pipeline.simulated=true}（模拟模式）时本 Bean 不装配
 * （无认领者；模拟路径也不建作业行）。</p>
 *
 * <p>遗留（M4-INC2/M5）：running 态崩溃恢复（locked_by 心跳超时重派）、作业容器隔离、
 * 取消信号（Valkey 辅助面）、并发度配置。</p>
 *
 * @author Ivan Yang, 2026-09-19
 */
@Component
@ConditionalOnProperty(name = "teamone.pipeline.simulated", havingValue = "false", matchIfMissing = true)
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    /**
     * 认领查询契约（native + SKIP LOCKED，FOR UPDATE 锁行至事务提交；单飞并发下取 1 条）。
     * 契约由 PipelineRunnerContractTest 反射钉死。
     */
    static final String CLAIM_SELECT_SQL = """
            SELECT id
              FROM eng.pipeline_job
             WHERE status = 'pending'
             ORDER BY created_at
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """;

    /** 认领落库：置 running + 认领者标识 + started_at + attempt 计数 */
    static final String CLAIM_UPDATE_SQL = """
            UPDATE eng.pipeline_job
               SET status = 'running', locked_by = ?, locked_at = now(),
                   started_at = now(), attempt = attempt + 1
             WHERE id = ?
            """;

    /** 作业执行超时（docs/v2/11 §3.2 通用 step 契约：构建 15min 上限，最小版统一档） */
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(15);
    /** 合流输出尾部行数（log_tail 落库形状） */
    private static final int LOG_TAIL_LINES = 120;
    /** DB 故障告警节流（5s 轮询下防刷屏） */
    private static final long WARN_INTERVAL_MS = 30_000;

    /** 认领者标识 hostname:pid（内嵌 Runner 单实例；崩溃恢复语义归 M4-INC2） */
    private static final String RUNNER_IDENTITY = resolveIdentity();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PipelineService pipelineService;
    private final GitPort gitPort;
    private final BuildCommandExecutor buildExecutor;
    private final PipelineJobRepository jobRepo;
    private final PipelineRunRepository runRepo;
    private final RepositoryRepository repositoryRepo;

    /** 单飞：上一作业未收口前不认领新作业（全局并发 1；@Scheduled 只做认领分发） */
    private final AtomicBoolean executing = new AtomicBoolean(false);
    /** 作业执行线程池（独立于 scheduler 线程；单线程即可——单飞下至多 1 个在飞） */
    private final ExecutorService jobPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-job-executor");
        t.setDaemon(true);
        return t;
    });

    private volatile long lastWarnAt = 0L;

    public PipelineRunner(JdbcTemplate jdbc,
                          TransactionTemplate tx,
                          PipelineService pipelineService,
                          GitPort gitPort,
                          BuildCommandExecutor buildExecutor,
                          PipelineJobRepository jobRepo,
                          PipelineRunRepository runRepo,
                          RepositoryRepository repositoryRepo) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.pipelineService = pipelineService;
        this.gitPort = gitPort;
        this.buildExecutor = buildExecutor;
        this.jobRepo = jobRepo;
        this.runRepo = runRepo;
        this.repositoryRepo = repositoryRepo;
    }

    /** 认领分发（5s 轮询；事务内 SKIP LOCKED 抢行 → 交执行线程池） */
    /**
     * 启动恢复（M4-INC1 崩溃自愈）：实例重启/VM 抖动后，上一进程认领的 running 作业必然滞留
     * （无心跳超时机制前的最小兜底）——重派回 pending 由本轮 Runner 重新导出工作区执行。
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverOrphanedJobs() {
        int n = jobRepo.requeueRunningOnStartup(java.time.Instant.now());
        if (n > 0) {
            log.warn("[pipeline-runner] 启动恢复：重派滞留 running 作业 {} 个（实例重启前认领）", n);
        }
    }

    @Scheduled(fixedDelayString = "${teamone.pipeline.poll-interval-ms:5000}")
    public void poll() {
        if (!executing.compareAndSet(false, true)) {
            return; // 单飞：上一作业在飞，本轮跳过
        }
        UUID jobId = null;
        try {
            jobId = claimNext();
        } catch (DataAccessException e) {
            warnThrottled("[pipeline-runner] 认领批次失败（下轮重试）: " + e.getMessage());
        }
        if (jobId == null) {
            executing.set(false);
            return;
        }
        final UUID claimedJobId = jobId;
        try {
            jobPool.submit(() -> {
                try {
                    executeJob(claimedJobId);
                } finally {
                    executing.set(false); // 收口（含异常路径）后放行下一认领
                }
            });
        } catch (RejectedExecutionException e) {
            executing.set(false);
            log.error("[pipeline-runner] 作业 {} 派发失败（线程池已关闭）", jobId);
        }
    }

    /** SKIP LOCKED 抢单 + 认领落库（同事务提交释放行锁） */
    private UUID claimNext() {
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(CLAIM_SELECT_SQL);
            if (rows.isEmpty()) {
                return null;
            }
            UUID id = (UUID) rows.get(0).get("id");
            jdbc.update(CLAIM_UPDATE_SQL, RUNNER_IDENTITY, id);
            log.info("[pipeline-runner] 认领作业 {}（{}）", id, RUNNER_IDENTITY);
            return id;
        });
    }

    // ==================== 单作业执行全链 ====================

    private void executeJob(UUID jobId) {
        Path workspace = null;
        try {
            PipelineJob job = jobRepo.findById(jobId).orElse(null);
            if (job == null) {
                return; // 作业被清理（rerun 重置）：无事可做
            }
            PipelineRun run = runRepo.findById(job.getRunId()).orElse(null);
            if (run == null) {
                pipelineService.completeJobAndAdvance(jobId, null, "", "所属流水线运行不存在", null);
                return;
            }
            Repository repo = repositoryRepo.findById(run.getRepoId()).orElse(null);
            if (repo == null) {
                pipelineService.completeJobAndAdvance(jobId, null, "", "所属仓库不存在", null);
                return;
            }

            // 1) 工作区准备：git archive 导出 run 提交到 /tmp/teamone-job-{jobId}
            //    （run.commitSha 为分支 HEAD 提交；全零占位（空分支兜底）退化为分支名）
            workspace = workspaceDir(jobId);
            cleanDirectory(workspace);
            Files.createDirectories(workspace);
            String ref = isRealSha(run.getCommitSha()) ? run.getCommitSha() : run.getBranch();
            try {
                gitPort.exportArchive(repo.getRepoPath(), ref, workspace);
            } catch (Exception e) {
                pipelineService.completeJobAndAdvance(jobId, null, "",
                        "工作区导出失败: " + e.getMessage(), null);
                return;
            }

            // 2) 工具链探测（容器隔离形态=工具镜像是否配置）：npm 未配镜像等——快速失败，
            //    错误注明 M5 工具链镜像化（不把环境缺口冒充构建失败）
            String tool = toolOf(job.getCmd());
            if (tool != null && !buildExecutor.toolAvailable(tool)) {
                pipelineService.completeJobAndAdvance(jobId, null, "",
                        toolUnavailableMsg(tool), null);
                return;
            }

            // 3) 真实执行：命令为服务端模板（空白分列成参数数组，无 shell）；
            //    MAVEN_OPTS/-Dmaven.repo.local 不显式注入（容器 HOME=/root，.m2 缓存卷天然生效）
            BuildCommandExecutor.Outcome out = buildExecutor.run(
                    PipelineJobTemplates.split(job.getCmd()), workspace, JOB_TIMEOUT, LOG_TAIL_LINES);

            // 4) surefire 聚合（test 阶段 + maven 体系；npm 体系跳过——无 surefire 报告）
            TestSummary summary = null;
            if ("test".equals(job.getStage()) && "maven".equals(run.getBuildSystem())) {
                String dir = run.getWorkdir() == null ? "" : run.getWorkdir();
                summary = SurefireReportParser.parse(workspace.resolve(dir));
            }

            // 5) 收口 + 链式推进（build 成功建 test；终态聚合 run + stages 快照回写）
            boolean runFinished = pipelineService.completeJobAndAdvance(
                    jobId, out.exitCode(), out.logTail(), out.errorMsg(), summary);

            // 6) MR 门禁真实回填（run 终态 + 有真实单测聚合；无报告不动门禁；
            //    独立事务、失败仅 WARN——不回滚已收口的作业状态）
            if (runFinished && summary != null) {
                pipelineService.feedMrGateIfLinkedReal(run.getId(), summary);
            }
        } catch (Exception e) {
            log.error("[pipeline-runner] 作业 {} 执行器异常", jobId, e);
            try {
                pipelineService.completeJobAndAdvance(jobId, null, "",
                        "执行器异常: " + e.getMessage(), null);
            } catch (Exception suppressed) {
                log.error("[pipeline-runner] 作业 {} 异常收口失败（将滞留 running，待 M4-INC2 崩溃恢复）: {}",
                        jobId, suppressed.getMessage());
            }
        } finally {
            if (workspace != null) {
                deleteRecursively(workspace); // 作业结束整卷回收（成功/失败一律清理）
            }
        }
    }

    // ==================== 小工具 ====================

    /**
     * 工作区根：docker daemon 可见路径优先（/teamone-ws 双向 bind，作业容器才能挂载）；
     * 目录不存在时回退 tmpdir（无 sock 环境下进程内降级路径，docker 形态将因挂载失败而诚实报错）。
     */
    /** 工作区根（容器内视角）：与 yml teamone.pipeline.workspace-dir 同键——目录存在则用之 */
    @org.springframework.beans.factory.annotation.Value("${teamone.pipeline.workspace-dir:/teamone-ws}")
    private String configuredWorkspaceDir;

    private Path workspaceRoot() {
        Path shared = Path.of(configuredWorkspaceDir);
        return java.nio.file.Files.isDirectory(shared) ? shared : Path.of(System.getProperty("java.io.tmpdir"));
    }

    /** 工作区目录：{workspaceRoot}/teamone-job-{jobId}（实例方法——workspaceRoot 读配置注入） */
    private Path workspaceDir(UUID jobId) {
        return workspaceRoot().resolve("teamone-job-" + jobId);
    }

    /** 确保目录存在且为空（rerun 重置后 jobId 不会复用，此为磁盘残留兜底） */
    private static void cleanDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> p.toFile().delete());
        }
    }

    /** 整卷递归删除（finally 兜底，绝不抛出） */
    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception e) {
            log.warn("[pipeline-runner] 工作区清理失败 {}: {}", dir, e.getMessage());
        }
    }

    /** 模板命令的工具名（mvn/npm；其它（未知体系兜底 script）不探测直接执行） */
    private static String toolOf(String cmd) {
        if (cmd == null) {
            return null;
        }
        if (cmd.startsWith("mvn ") || "mvn".equals(cmd)) {
            return "mvn";
        }
        if (cmd.startsWith("npm ") || "npm".equals(cmd)) {
            return "npm";
        }
        return null;
    }

    /** 工具链缺失文案（npm 按任务口径钉死：容器内未安装 node/npm） */
    private static String toolUnavailableMsg(String tool) {
        return "工具链不可用：未配置 " + tool + " 工具镜像（teamone.pipeline.tool-image.*，M5 工具链镜像化）";
    }

    /** 40 位非全零 sha 判定（trigger 对空分支兜底写过全零占位） */
    private static boolean isRealSha(String sha) {
        return sha != null && sha.matches("[0-9a-f]{40}") && !"0".repeat(40).equals(sha);
    }

    private static String resolveIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + ":" + ProcessHandle.current().pid();
    }

    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_INTERVAL_MS) {
            lastWarnAt = now;
            log.warn(message);
        }
    }

    @PreDestroy
    void shutdown() {
        jobPool.shutdownNow();
    }
}
