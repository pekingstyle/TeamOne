package cn.teamone.eng.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 部署登记实体（eng.deployment 表映射，R-9 发布一致性 · B2 批）。
 *
 * <p>本批仅「登记 + 展示」闭环：真实部署执行联动属 M4/M5（docs/v2/11）。遵循 eng 域架构守恒：
 * release_id / pipeline_run_id / deployed_by 均为 UUID 逻辑引用，不建外键（不引 prd）。</p>
 */
@Entity
@Table(name = "deployment", schema = "eng")
public class Deployment {

    // ---------- env（dev/staging/prod；V17 无 CHECK，应用层校验白名单） ----------
    public static final String ENV_DEV = "dev";
    public static final String ENV_STAGING = "staging";
    public static final String ENV_PROD = "prod";

    // ---------- status（V17 CHECK: running/success/failed/rolled_back） ----------
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_ROLLED_BACK = "rolled_back";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 交付版本（逻辑引用 prd.release.id，不建 FK——eng 不引 prd 纪律） */
    @Column(name = "release_id", nullable = false)
    private UUID releaseId;

    /** 关联流水线运行（逻辑引用 eng.pipeline_run.id；登记时回填该运行的 release_id 形成展示闭环） */
    @Column(name = "pipeline_run_id")
    private UUID pipelineRunId;

    /** 部署环境：dev / staging / prod */
    @Column(nullable = false, columnDefinition = "text")
    private String env;

    /** 部署状态：running / success / failed / rolled_back（登记缺省 success） */
    @Column(nullable = false, columnDefinition = "text")
    private String status = STATUS_SUCCESS;

    /** 部署制品版本（如 2.4.0-rc.3） */
    @Column(name = "artifact_version", columnDefinition = "text")
    private String artifactVersion;

    /** 登记人（逻辑引用 platform.app_user.id） */
    @Column(name = "deployed_by")
    private UUID deployedBy;

    /** 部署/登记时间 */
    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt = Instant.now();

    /** 备注 */
    @Column(columnDefinition = "text")
    private String note;

    public UUID getId() { return id; }
    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID v) { this.releaseId = v; }
    public UUID getPipelineRunId() { return pipelineRunId; }
    public void setPipelineRunId(UUID v) { this.pipelineRunId = v; }
    public String getEnv() { return env; }
    public void setEnv(String v) { this.env = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getArtifactVersion() { return artifactVersion; }
    public void setArtifactVersion(String v) { this.artifactVersion = v; }
    public UUID getDeployedBy() { return deployedBy; }
    public void setDeployedBy(UUID v) { this.deployedBy = v; }
    public Instant getDeployedAt() { return deployedAt; }
    public void setDeployedAt(Instant v) { this.deployedAt = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
}
