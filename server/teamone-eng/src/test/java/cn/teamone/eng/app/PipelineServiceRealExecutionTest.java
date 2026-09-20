package cn.teamone.eng.app;

import cn.teamone.eng.app.PipelineService.CoverageResult;
import cn.teamone.eng.app.PipelineService.TestSummary;
import cn.teamone.eng.domain.MergeCheck;
import cn.teamone.eng.domain.PipelineJob;
import cn.teamone.eng.domain.PipelineRun;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.PipelineRunResponse;
import cn.teamone.eng.dto.UnitTestReportRequest;
import cn.teamone.eng.infra.git.GitCommit;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.MergeCheckRepository;
import cn.teamone.eng.repo.PipelineJobRepository;
import cn.teamone.eng.repo.PipelineRunRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PipelineService 真实执行单测（M4-INC1）：trigger 识别分叉（maven 建链 / unknown 直接
 * failed 不建作业）、作业链式推进（build 成功建 test）、run 聚合收口与 stages jsonb 回写
 * 形状、MR 门禁真实回填（surefire 聚合 + 沿用旧覆盖率 + reportUrl 注记）、灰度开关
 * simulated=true 完全走旧模拟路径。纯 Mockito 无 Spring（作业执行本身的进程层在
 * BuildCommandExecutor/PipelineRunner，此处只锁业务状态机）。
 *
 * @author Ivan Yang, 2026-09-19
 */
class PipelineServiceRealExecutionTest {

    private static final String SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b2c3d4e5f8a9b0";
    private static final UUID REPO_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID MR_ID = UUID.randomUUID();

    private PipelineRunRepository pipelineRepo;
    private RepositoryRepository repositoryRepo;
    private GitPort gitPort;
    private MergeRequestService mergeRequestService;
    private PipelineJobRepository jobRepo;
    private MergeCheckRepository checkRepo;
    private BuildSystemDetector detector;

    private PipelineService service;
    private final List<PipelineRun> savedRuns = new ArrayList<>();
    private final List<PipelineJob> savedJobs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pipelineRepo = mock(PipelineRunRepository.class);
        repositoryRepo = mock(RepositoryRepository.class);
        gitPort = mock(GitPort.class);
        mergeRequestService = mock(MergeRequestService.class);
        jobRepo = mock(PipelineJobRepository.class);
        checkRepo = mock(MergeCheckRepository.class);
        detector = mock(BuildSystemDetector.class);

        savedRuns.clear();
        savedJobs.clear();
        when(pipelineRepo.save(any())).thenAnswer(inv -> {
            PipelineRun r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(RUN_ID);
            }
            savedRuns.add(r);
            return r;
        });
        when(jobRepo.save(any())).thenAnswer(inv -> {
            PipelineJob j = inv.getArgument(0);
            savedJobs.add(j);
            return j;
        });
        when(jobRepo.findByRunIdOrderBySeqAsc(any())).thenAnswer(inv -> List.copyOf(savedJobs));

        when(repositoryRepo.findByName("teamone")).thenReturn(Optional.of(repo("teamone")));
        when(gitPort.commits(any(), eq("main"), eq(1), eq(1)))
                .thenReturn(List.of(new GitCommit(SHA, "Dev", null, Instant.now(), "s", "")));
        when(pipelineRepo.countByRepoId(REPO_ID)).thenReturn(0L);

        service = service(false);
    }

    private PipelineService service(boolean simulated) {
        return new PipelineService(pipelineRepo, repositoryRepo, gitPort, mergeRequestService,
                mock(RepoPermChecker.class), jobRepo, checkRepo, detector, simulated);
    }

    private static Repository repo(String name) {
        Repository r = new Repository();
        r.setId(REPO_ID);
        r.setName(name);
        r.setRepoPath("teamone/" + name + ".git");
        r.setDefaultBranch("main");
        return r;
    }

    private static PipelineRun run(String buildSystem, String workdir) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setRepoId(REPO_ID);
        run.setTitle("#101 · main 自动化构建与测试");
        run.setBranch("main");
        run.setCommitSha(SHA);
        run.setCommitShort(SHA.substring(0, 7));
        run.setBuildSystem(buildSystem);
        run.setWorkdir(workdir);
        run.setStatus("running");
        run.setStartedAt(Instant.now().minusSeconds(120));
        return run;
    }

    private static PipelineJob runningJob(String stage, int seq) {
        PipelineJob job = new PipelineJob();
        job.setId(UUID.randomUUID());
        job.setRunId(RUN_ID);
        job.setSeq(seq);
        job.setStage(stage);
        job.setName("test".equals(stage) ? "单测" : "构建");
        job.setCmd(stage.equals("build")
                ? PipelineJobTemplates.cmd("maven", "build", "server")
                : PipelineJobTemplates.cmd("maven", "test", "server"));
        job.setWorkdir("server");
        job.setStatus("running");
        job.setStartedAt(Instant.now().minusSeconds(60));
        return job;
    }

    // ==================== trigger：识别分叉 ====================

    @Test
    void trigger_maven_buildSystemPersisted_andFirstBuildJobCreated() {
        when(detector.detect("teamone/teamone.git", "main"))
                .thenReturn(new BuildSystemDetector.Result("maven", "server"));

        PipelineRunResponse resp = service.triggerPipeline("teamone", null, UUID.randomUUID());

        assertEquals(1, savedJobs.size());
        PipelineJob job = savedJobs.get(0);
        assertEquals(1, job.getSeq());
        assertEquals("build", job.getStage());
        assertEquals("构建", job.getName());
        assertEquals("mvn -q -f server/pom.xml package -DskipTests", job.getCmd());
        assertEquals("server", job.getWorkdir());
        assertEquals("pending", job.getStatus());

        PipelineRun saved = savedRuns.get(0);
        assertEquals("pending", saved.getStatus());
        assertEquals("maven", saved.getBuildSystem());
        assertEquals("server", saved.getWorkdir());

        // 契约：详情响应带 buildSystem + jobs（stage/status 用作业级词表）
        assertEquals("maven", resp.buildSystem());
        assertEquals(1, resp.jobs().size());
        assertEquals("build", resp.jobs().get(0).stage());
        assertEquals("pending", resp.jobs().get(0).status());
    }

    @Test
    void trigger_unknown_failsRunWithoutJobs_errorNotedInStages() {
        when(detector.detect("teamone/teamone.git", "main"))
                .thenReturn(new BuildSystemDetector.Result("unknown", ""));

        PipelineRunResponse resp = service.triggerPipeline("teamone", null, UUID.randomUUID());

        assertTrue(savedJobs.isEmpty(), "unknown 体系不建作业");
        PipelineRun saved = savedRuns.get(0);
        assertEquals("failed", saved.getStatus());
        assertEquals("unknown", saved.getBuildSystem());
        assertNotNull(saved.getFinishedAt());
        // 错误注明于 stages 快照 summary（无作业行）
        assertEquals(1, saved.getStages().size());
        assertEquals("failed", saved.getStages().get(0).get("status"));
        assertTrue(String.valueOf(saved.getStages().get(0).get("summary")).contains("未识别构建体系"));
        // 前端缺省安全：jobs 空列表、buildSystem=unknown
        assertEquals("unknown", resp.buildSystem());
        assertTrue(resp.jobs().isEmpty());
    }

    @Test
    void trigger_simulated_keepsLegacyMockPath_withoutJobs() {
        when(detector.detect(any(), any())).thenReturn(new BuildSystemDetector.Result("maven", ""));
        PipelineService simulated = service(true);

        PipelineRunResponse resp = simulated.triggerPipeline("teamone",
                new cn.teamone.eng.dto.TriggerPipelineRequest(null, null, "manual", MR_ID),
                UUID.randomUUID());

        assertTrue(savedJobs.isEmpty(), "模拟路径不建作业行");
        PipelineRun saved = savedRuns.get(0);
        assertEquals("passed", saved.getStatus());
        assertEquals(3, saved.getStages().size());
        assertNull(saved.getBuildSystem(), "模拟路径不识别构建体系");
        // 演示数据回填门禁（82/82.5/88.0）保持原语义
        ArgumentCaptor<UnitTestReportRequest> cap = ArgumentCaptor.forClass(UnitTestReportRequest.class);
        verify(mergeRequestService).uploadUnitTestReport(eq(MR_ID), cap.capture());
        assertEquals(82, cap.getValue().total());
        assertEquals(82.5, cap.getValue().coverageTotal());
        assertEquals(88.0, cap.getValue().coveragePatch());
        assertEquals("unknown", resp.buildSystem(), "模拟 run 无识别结果，透出 unknown");
        assertNull(resp.jobs(), "模拟路径走列表形态转换，jobs 不装填（Jackson non_null 缺省）");
    }

    // ==================== 作业链式推进与 run 收口 ====================

    @Test
    void complete_buildSuccess_createsTestJob_runKeepsRunning() {
        PipelineJob buildJob = runningJob("build", 1);
        when(jobRepo.findById(buildJob.getId())).thenReturn(Optional.of(buildJob));
        PipelineRun r = run("maven", "server");
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));
        when(jobRepo.findFirstByRunIdOrderBySeqDesc(RUN_ID)).thenReturn(Optional.of(buildJob));

        Boolean runFinished = service.completeJobAndAdvance(buildJob.getId(), 0, "BUILD SUCCESS", null, null, null);

        assertFalse(runFinished, "build 成功后 run 未收口（test 待执行）");
        assertEquals("success", buildJob.getStatus());
        assertEquals(0, buildJob.getExitCode());
        // 链式：seq=2 的 test 作业按 maven 模板生成
        assertEquals(2, savedJobs.size());
        PipelineJob testJob = savedJobs.get(1);
        assertEquals(2, testJob.getSeq());
        assertEquals("test", testJob.getStage());
        assertEquals("mvn -q -f server/pom.xml test", testJob.getCmd());
        assertEquals("pending", testJob.getStatus());
        // run 保持 running
        PipelineRun lastRun = savedRuns.get(savedRuns.size() - 1);
        assertEquals("running", lastRun.getStatus());
    }

    @Test
    void complete_buildFailed_finalizesRunFailed_withoutTestJob() {
        PipelineJob buildJob = runningJob("build", 1);
        when(jobRepo.findById(buildJob.getId())).thenReturn(Optional.of(buildJob));
        PipelineRun r = run("maven", "server");
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));

        boolean runFinished = service.completeJobAndAdvance(buildJob.getId(), 1, "BUILD FAILURE", null, null, null);
        assertTrue(runFinished);
        assertEquals("failed", buildJob.getStatus());
        assertEquals(1, savedJobs.size(), "失败不建后续作业");
        PipelineRun last = savedRuns.get(savedRuns.size() - 1);
        assertEquals("failed", last.getStatus(), "任一作业 failed → run failed");
        assertNotNull(last.getFinishedAt());
        // stages 快照回写：构建阶段 failed、jobs 直通日志行
        assertEquals(1, last.getStages().size());
        Map<String, Object> stage = last.getStages().get(0);
        assertEquals("构建阶段 (Build)", stage.get("name"));
        assertEquals("failed", stage.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stageJobs = (List<Map<String, Object>>) stage.get("jobs");
        assertEquals("failed", stageJobs.get(0).get("status"));
        verify(jobRepo, never()).findFirstByRunIdOrderBySeqDesc(any());
    }

    @Test
    void complete_testSuccess_finalizesRunPassed_withRealStagesSnapshot() {
        // 已成功的 build 作业（上一轮收口）+ 本轮 test 作业
        PipelineJob buildJob = runningJob("build", 1);
        buildJob.setStatus("success");
        buildJob.setFinishedAt(Instant.now().minusSeconds(30));
        PipelineJob testJob = runningJob("test", 2);
        when(jobRepo.findById(testJob.getId())).thenReturn(Optional.of(testJob));
        PipelineRun r = run("maven", "server");
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));
        // 完成前 run 内已有 build 作业，本作业收口时 save 进 savedJobs
        savedJobs.add(buildJob);

        TestSummary summary = new TestSummary(15, 0, 0, 2, 3);
        boolean runFinished = service.completeJobAndAdvance(
                testJob.getId(), 0, "[INFO] Tests run: 15", null, summary,
                new CoverageResult(78.3, 85.0, true, true));

        assertTrue(runFinished);
        PipelineRun last = savedRuns.get(savedRuns.size() - 1);
        assertEquals("passed", last.getStatus(), "全部作业 success → run passed");
        assertEquals(2, last.getStages().size());
        Map<String, Object> testStage = last.getStages().get(1);
        assertEquals("测试门禁 (Test Gate)", testStage.get("name"));
        assertEquals("passed", testStage.get("status"));
        // 展示字段形状保持（success→passed），单测聚合计入 summary 注记
        assertTrue(String.valueOf(testStage.get("summary")).contains("Tests run: 15"));
        // M4-INC2：真实覆盖率摘要计入 test 阶段 summary（total 78.3% / patch 85.0%）
        assertTrue(String.valueOf(testStage.get("summary")).contains("覆盖率 total 78.3% / patch 85.0%"),
                String.valueOf(testStage.get("summary")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stageJobs = (List<Map<String, Object>>) testStage.get("jobs");
        assertEquals("passed", stageJobs.get(0).get("status"));
    }

    @Test
    void complete_idempotent_onlyRunningJobCanBeClosed() {
        PipelineJob done = runningJob("build", 1);
        done.setStatus("success");
        when(jobRepo.findById(done.getId())).thenReturn(Optional.of(done));

        assertFalse(service.completeJobAndAdvance(done.getId(), 0, "x", null, null, null));
        verify(pipelineRepo, never()).findById(any());
    }

    // ==================== MR 门禁真实回填（M4-INC2：覆盖率真实值/降级两分支） ====================

    @Test
    void feedMrGate_degradedCoverage_carriesOldValuesAndSimulatedNote() {
        PipelineRun r = run("maven", "server");
        r.setMrId(MR_ID);
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));
        MergeCheck check = new MergeCheck();
        Map<String, Object> payload = new HashMap<>();
        payload.put("coverageTotal", 82.5);
        payload.put("coverageDelta", 88.0);
        check.setPayload(payload);
        when(checkRepo.findByMrIdAndKind(MR_ID, "unit_test")).thenReturn(Optional.of(check));

        // agent/exec 缺失（M4-INC2 降级矩阵）→ 沿用 ⑥k 行为：旧值 + 原 simulated 注记
        boolean fed = service.feedMrGateIfLinkedReal(RUN_ID, new TestSummary(15, 1, 2, 3, 2),
                new CoverageResult(0d, null, false, false));

        assertTrue(fed);
        ArgumentCaptor<UnitTestReportRequest> cap = ArgumentCaptor.forClass(UnitTestReportRequest.class);
        verify(mergeRequestService).uploadUnitTestReport(eq(MR_ID), cap.capture());
        UnitTestReportRequest req = cap.getValue();
        assertEquals(15, req.total(), "tests=surefire 真实聚合");
        assertEquals(3, req.failed(), "failures+errors");
        assertFalse(req.passed(), "errors+failures>0 → 不通过");
        assertEquals(82.5, req.coverageTotal(), "降级：沿用 MR 既有整体覆盖率");
        assertEquals(88.0, req.coveragePatch(), "降级：沿用 MR 既有 patch 覆盖率");
        assertTrue(req.reportUrl().contains("/pipelines/" + RUN_ID));
        assertTrue(req.reportUrl().contains("coverage=simulated(真实计算 M4-INC2);tests=surefire"),
                "降级保留原 simulated 注记: " + req.reportUrl());
    }

    @Test
    void feedMrGate_realCoverage_feedsJacocoValuesWithRealNote() {
        PipelineRun r = run("maven", "server");
        r.setMrId(MR_ID);
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));
        when(checkRepo.findByMrIdAndKind(MR_ID, "unit_test")).thenReturn(Optional.empty());

        boolean fed = service.feedMrGateIfLinkedReal(RUN_ID, new TestSummary(15, 0, 0, 3, 2),
                new CoverageResult(78.3, 85.0, true, true));

        assertTrue(fed);
        ArgumentCaptor<UnitTestReportRequest> cap = ArgumentCaptor.forClass(UnitTestReportRequest.class);
        verify(mergeRequestService).uploadUnitTestReport(eq(MR_ID), cap.capture());
        UnitTestReportRequest req = cap.getValue();
        assertTrue(req.passed());
        assertEquals(78.3, req.coverageTotal(), "coverageTotal=jacoco 真实值");
        assertEquals(85.0, req.coveragePatch(), "coveragePatch=变更行∩覆盖行 真实值");
        assertTrue(req.reportUrl().contains("tests=surefire;coverage=jacoco"),
                "真实注记替换 simulated: " + req.reportUrl());
        assertFalse(req.reportUrl().contains("simulated"));
    }

    @Test
    void feedMrGate_realCoverageNullPatch_feedsHundredWithNaNote() {
        PipelineRun r = run("maven", "server");
        r.setMrId(MR_ID);
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));
        when(checkRepo.findByMrIdAndKind(MR_ID, "unit_test")).thenReturn(Optional.empty());

        // 分母 0（变更行∩可执行行=0，无涉代码变更）：patch=null → 空真约定 100.0，门禁不因此挂
        boolean fed = service.feedMrGateIfLinkedReal(RUN_ID, new TestSummary(15, 0, 0, 3, 2),
                new CoverageResult(61.2, null, true, true));

        assertTrue(fed);
        ArgumentCaptor<UnitTestReportRequest> cap = ArgumentCaptor.forClass(UnitTestReportRequest.class);
        verify(mergeRequestService).uploadUnitTestReport(eq(MR_ID), cap.capture());
        UnitTestReportRequest req = cap.getValue();
        assertTrue(req.passed(), "无涉代码变更时 patch 不拖挂门禁");
        assertEquals(61.2, req.coverageTotal());
        assertEquals(100.0, req.coveragePatch(), "R8 协议无空态——n/a 按空真约定回填 100.0");
        assertTrue(req.reportUrl().contains("patch=n/a(无涉代码变更)"), req.reportUrl());
    }

    @Test
    void feedMrGate_nullCoverageBehavesAsDegraded() {
        PipelineRun r = run("maven", "server");
        r.setMrId(MR_ID);
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));
        when(checkRepo.findByMrIdAndKind(MR_ID, "unit_test")).thenReturn(Optional.empty());

        boolean fed = service.feedMrGateIfLinkedReal(RUN_ID, new TestSummary(5, 0, 0, 0, 1), null);

        assertTrue(fed);
        ArgumentCaptor<UnitTestReportRequest> cap = ArgumentCaptor.forClass(UnitTestReportRequest.class);
        verify(mergeRequestService).uploadUnitTestReport(eq(MR_ID), cap.capture());
        assertEquals(0.0, cap.getValue().coverageTotal(), "无旧值时按 0 透出（门禁不误通过）");
        assertEquals(0.0, cap.getValue().coveragePatch());
    }

    @Test
    void feedMrGate_noReports_untouched() {
        PipelineRun r = run("maven", "server");
        r.setMrId(MR_ID);
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(r));

        assertFalse(service.feedMrGateIfLinkedReal(RUN_ID, new TestSummary(0, 0, 0, 0, 0),
                new CoverageResult(78.3, 85.0, true, true)));
        verify(mergeRequestService, never()).uploadUnitTestReport(any(), any());
    }

    @Test
    void feedMrGate_noMrLinked_noop() {
        when(pipelineRepo.findById(RUN_ID)).thenReturn(Optional.of(run("maven", "server")));
        assertFalse(service.feedMrGateIfLinkedReal(RUN_ID, new TestSummary(5, 0, 0, 0, 1),
                new CoverageResult(78.3, 85.0, true, true)));
        verify(mergeRequestService, never()).uploadUnitTestReport(any(), any());
    }
}
