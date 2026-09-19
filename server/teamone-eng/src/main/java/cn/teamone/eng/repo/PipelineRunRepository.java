package cn.teamone.eng.repo;

import cn.teamone.eng.domain.PipelineRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 流水线运行仓储（eng.pipeline_run 表操作）。
 *
 * @author Ivan Yang, 2026-09-13
 */
public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {

    /**
     * 按代码仓库查询全部流水线运行记录（时间倒序）。
     *
     * @param repoId 代码仓库 UUID
     * @return 运行记录列表
     */
    List<PipelineRun> findByRepoIdOrderByCreatedAtDesc(UUID repoId);

    /**
     * 按代码仓库分页查询流水线运行记录（时间倒序）。
     *
     * @param repoId   代码仓库 UUID
     * @param pageable 分页参数
     * @return 分页运行记录
     */
    Page<PipelineRun> findByRepoIdOrderByCreatedAtDesc(UUID repoId, Pageable pageable);

    /**
     * 按代码仓库与执行状态过滤分页查询流水线运行记录（时间倒序）。
     *
     * @param repoId   代码仓库 UUID
     * @param status   执行状态（passed/failed/running 等）
     * @param pageable 分页参数
     * @return 分页过滤后的运行记录
     */
    Page<PipelineRun> findByRepoIdAndStatusOrderByCreatedAtDesc(UUID repoId, String status, Pageable pageable);

    /**
     * 全局按执行状态过滤分页查询流水线运行记录（时间倒序）。
     *
     * @param status   执行状态
     * @param pageable 分页参数
     * @return 分页过滤后的运行记录
     */
    Page<PipelineRun> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /**
     * 统计指定代码仓库的流水线总运行次数。
     *
     * @param repoId 代码仓库 UUID
     * @return 运行总数
     */
    long countByRepoId(UUID repoId);

    /**
     * 统计指定代码仓库下特定状态的流水线运行次数（用于计算成功率）。
     *
     * @param repoId 代码仓库 UUID
     * @param status 执行状态
     * @return 对应状态运行次数
     */
    long countByRepoIdAndStatus(UUID repoId, String status);

    /**
     * 按交付版本查询流水线运行记录（时间倒序，R-9 版本详情「构建→测试→部署」数据源）。
     *
     * @param releaseId 交付版本 UUID（逻辑引用 prd.release.id）
     * @return 运行记录列表
     */
    List<PipelineRun> findByReleaseIdOrderByCreatedAtDesc(UUID releaseId);
}
