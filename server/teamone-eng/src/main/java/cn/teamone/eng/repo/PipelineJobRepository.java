package cn.teamone.eng.repo;

import cn.teamone.eng.domain.PipelineJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流水线作业仓储（eng.pipeline_job 表操作；M4-INC1 真实执行）。
 *
 * <p>注意：Runner 的「认领」（pending → running，FOR UPDATE SKIP LOCKED）不走本仓储，
 * 经 {@code PipelineRunner} 的 native SQL + JdbcTemplate 事务完成（照 OutboxRelay 先例）；
 * 本仓储承担认领后的读取与收口写回。</p>
 *
 * @author Ivan Yang, 2026-09-19
 */
public interface PipelineJobRepository extends JpaRepository<PipelineJob, UUID> {

    /**
     * 按运行查询全部作业（seq 升序——链式推进序）。
     *
     * @param runId 流水线运行 UUID
     * @return 作业列表（run 无作业返回空表：历史模拟 run / unknown 体系 run）
     */
    List<PipelineJob> findByRunIdOrderBySeqAsc(UUID runId);

    /**
     * 取 run 内最大 seq 作业（链式建下一作业的序号来源）。
     *
     * @param runId 流水线运行 UUID
     * @return 最大 seq 作业（无作业返回 empty）
     */
    Optional<PipelineJob> findFirstByRunIdOrderBySeqDesc(UUID runId);

    /**
     * 删除 run 全部作业（真实模式 rerun 重置作业链用；调用方须在事务内）。
     *
     * @param runId 流水线运行 UUID
     */
    void deleteByRunId(UUID runId);

    /**
     * 启动恢复：滞留 running 作业重派 pending（error 注明；须事务内调用）。
     * boot 前过滤：只重派<b>本实例启动前</b>认领的行——@Scheduled 先于 ApplicationReadyEvent
     * 激活的毫秒窗内新认领作业不被误重派重复执行（QA 复审 NICE-1）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value =
            "UPDATE eng.pipeline_job SET status='pending', locked_by=NULL, locked_at=NULL, "
            + "error_msg='实例重启重派（启动恢复）' WHERE status='running' AND locked_at < :boot", nativeQuery = true)
    int requeueRunningOnStartup(@org.springframework.data.repository.query.Param("boot") java.time.Instant boot);
}
