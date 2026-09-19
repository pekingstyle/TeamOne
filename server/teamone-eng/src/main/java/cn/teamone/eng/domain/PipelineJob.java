package cn.teamone.eng.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 流水线作业实体（eng.pipeline_job 表映射；M4-INC1 真实执行 · docs/v2/11 §3.6 作业表子集）。
 *
 * <p>作业是调度/执行/门禁的原子真相粒度：run 的 stages jsonb 降级为聚合展示快照，
 * 由本表作业结果驱动回写。cmd 由服务端按「构建体系 × 阶段」模板生成（{@code PipelineJobTemplates}），
 * 绝不拼装用户输入；Runner 以空白分列成参数数组经 ProcessBuilder 执行（无 shell）。</p>
 *
 * <p>状态机：pending（排队）→ running（已认领）→ success/failed/skipped（终态）。
 * 与 run 级状态（pending/running/passed/failed/...）相互独立，成功态用 success。</p>
 *
 * @author Ivan Yang, 2026-09-19
 */
@Entity
@Table(name = "pipeline_job", schema = "eng")
public class PipelineJob {

    /** 作业主键 UUID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属流水线运行 ID（外键映射 eng.pipeline_run.id，级联删除）。 */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** run 内作业序号（链式推进顺序，从 1 起；UNIQUE(run_id, seq)）。 */
    @Column(nullable = false)
    private int seq;

    /** 阶段：build（构建）/ test（单测门禁）。 */
    @Column(nullable = false)
    private String stage;

    /** 展示名（"构建"/"单测"）。 */
    @Column(nullable = false)
    private String name;

    /** 服务端模板命令（如 mvn -q -f server/pom.xml package -DskipTests）。 */
    @Column(nullable = false)
    private String cmd;

    /** 构建层相对路径（null/""=仓库根；如 server、web），surefire 扫描起点。 */
    private String workdir;

    /** 作业状态：pending/running/success/failed/skipped。 */
    @Column(nullable = false)
    private String status = "pending";

    /** 进程退出码（超时/未启动为 NULL）。 */
    @Column(name = "exit_code")
    private Integer exitCode;

    /** 合流输出尾部 ~120 行文本。 */
    @Column(name = "log_tail")
    private String logTail;

    /** 失败原因（工具链缺失/超时/工作区导出失败等）。 */
    @Column(name = "error_msg")
    private String errorMsg;

    /** 认领执行次数（SKIP LOCKED 认领时 +1）。 */
    @Column(nullable = false)
    private int attempt = 0;

    /** 认领者标识 hostname:pid（内嵌 Runner）。 */
    @Column(name = "locked_by")
    private String lockedBy;

    /** 最近认领时间。 */
    @Column(name = "locked_at")
    private Instant lockedAt;

    /** 开始执行时间。 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 执行结束时间。 */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 创建时间（认领排序键）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public PipelineJob() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public String getWorkdir() {
        return workdir;
    }

    public void setWorkdir(String workdir) {
        this.workdir = workdir;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getLogTail() {
        return logTail;
    }

    public void setLogTail(String logTail) {
        this.logTail = logTail;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
