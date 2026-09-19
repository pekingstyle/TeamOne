package cn.teamone.eng.app;

import cn.teamone.eng.domain.Deployment;
import cn.teamone.eng.domain.PipelineRun;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.DeploymentRequest;
import cn.teamone.eng.dto.PipelineRunResponse;
import cn.teamone.eng.repo.DeploymentRepository;
import cn.teamone.eng.repo.PipelineRunRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 版本交付联动应用服务（R-9 发布一致性 · B2 批）。
 *
 * <p>路径挂在 /api/v1/releases/{id}/** 下但落 eng 模块——prd ⊥ eng 互禁编译依赖（ArchUnit R3/R5），
 * eng 只把 {id} 当 release UUID 逻辑引用，不回查 prd.release。本批提供「登记 + 展示」闭环：
 * 流水线各阶段只读展示 + 部署登记；真实部署执行联动属 M4/M5（docs/v2/11）。</p>
 */
@Service
public class ReleaseDeliveryService {

    /** 部署环境白名单（V17 未建 CHECK，应用层校验） */
    private static final List<String> ENVS =
            List.of(Deployment.ENV_DEV, Deployment.ENV_STAGING, Deployment.ENV_PROD);

    private final PipelineRunRepository pipelineRepo;
    private final DeploymentRepository deploymentRepo;
    private final RepositoryRepository repositoryRepo;
    private final PipelineService pipelineService;
    private final AuditService audit;

    public ReleaseDeliveryService(PipelineRunRepository pipelineRepo,
                                  DeploymentRepository deploymentRepo,
                                  RepositoryRepository repositoryRepo,
                                  PipelineService pipelineService,
                                  AuditService audit) {
        this.pipelineRepo = pipelineRepo;
        this.deploymentRepo = deploymentRepo;
        this.repositoryRepo = repositoryRepo;
        this.pipelineService = pipelineService;
        this.audit = audit;
    }

    /** 路径中的 release id 解析（仅接受 UUID——业务键 v2.4.0 属 prd 域，eng 不可回查） */
    public static UUID releaseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "版本 id 须为 UUID: " + id);
        }
    }

    // ==================== 查询（只读展示闭环） ====================

    /**
     * 某版本的流水线运行清单（createdAt 倒序，最新 20 条）。
     *
     * @return items：{id, repoName, branch, commitSha, status, stages, createdAt}
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pipelinesOfRelease(UUID releaseId) {
        List<PipelineRun> runs = pipelineRepo.findByReleaseIdOrderByCreatedAtDesc(releaseId);
        return runs.stream().limit(20).map(this::pipelineItem).toList();
    }

    /** 某版本的部署登记清单（deployed_at 倒序）：{env, status, artifactVersion, deployedAt, note} */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deploymentsOfRelease(UUID releaseId) {
        return deploymentRepo.findByReleaseIdOrderByDeployedAtDesc(releaseId).stream()
                .map(this::deploymentItem)
                .toList();
    }

    // ==================== 登记（真实执行联动属 M4/M5，本批只登记留痕） ====================

    /**
     * 登记一次部署（权限=发布同族管理侧 platform:manage，在控制器 @RequirePerm 断言）。
     * <p>pipelineRunId 提供且该运行尚未挂版本时回填 pipeline_run.release_id——本批 release_id
     * 的唯一写入口（按 release 自动触发流水线的回填属 M4/M5）。登记写审计（release.deploy）。</p>
     *
     * @return 登记后的部署视图
     */
    @Transactional
    public Map<String, Object> register(UUID releaseId, DeploymentRequest req, UUID actorId) {
        String env = req.env() == null ? "" : req.env().trim().toLowerCase();
        if (!ENVS.contains(env)) {
            throw new BusinessException(ErrorCode.PLT_4000, "env 必填且须为 dev/staging/prod: " + req.env());
        }

        Deployment d = new Deployment();
        d.setReleaseId(releaseId);
        d.setEnv(env);
        d.setArtifactVersion(blankToNull(req.artifactVersion()));
        d.setNote(blankToNull(req.note()));
        d.setDeployedBy(actorId);

        if (req.pipelineRunId() != null) {
            PipelineRun run = pipelineRepo.findById(req.pipelineRunId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                            "流水线运行不存在: " + req.pipelineRunId()));
            d.setPipelineRunId(run.getId());
            if (run.getReleaseId() == null) {
                // 展示闭环：把运行挂到本版本，使「流水线」区可见（不改既有关联）
                run.setReleaseId(releaseId);
                pipelineRepo.save(run);
            }
        }

        Deployment saved = deploymentRepo.save(d);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("releaseId", releaseId);
        detail.put("deploymentId", saved.getId());
        detail.put("env", saved.getEnv());
        detail.put("artifactVersion", saved.getArtifactVersion());
        detail.put("pipelineRunId", saved.getPipelineRunId());
        audit.record(actorId, "release.deploy", "release", releaseId.toString(), detail);
        return deploymentItem(saved);
    }

    // ==================== 内部 ====================

    /** 流水线行投影（R-9 契约字段；stages 沿 PipelineRunResponse 的解析结果原样透出） */
    private Map<String, Object> pipelineItem(PipelineRun r) {
        PipelineRunResponse resp = pipelineService.toResponse(
                r, repositoryRepo.findById(r.getRepoId()).map(Repository::getName).orElse(""));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", resp.id());
        m.put("repoName", resp.repoName());
        m.put("branch", resp.branch());
        m.put("commitSha", resp.commitSha());
        m.put("status", resp.status());
        m.put("stages", resp.stages());
        m.put("createdAt", resp.createdAt());
        return m;
    }

    /** 部署行投影（R-9 契约字段） */
    private Map<String, Object> deploymentItem(Deployment d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("env", d.getEnv());
        m.put("status", d.getStatus());
        m.put("artifactVersion", d.getArtifactVersion());
        m.put("deployedAt", d.getDeployedAt());
        m.put("note", d.getNote());
        return m;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
