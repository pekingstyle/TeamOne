package cn.teamone.insight.repo;

import cn.teamone.insight.domain.ConflictSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * prd.conflict_snapshot 快照写仓库（红线⑥：删当日旧行再插，幂等不追加）。
 * native SQL 处理 detected_at 的「当日」口径与 payload jsonb 取值。
 */
public interface ConflictSnapshotRepository
        extends JpaRepository<ConflictSnapshot, UUID>, JpaSpecificationExecutor<ConflictSnapshot> {

    /** 全量模式：删当日全部旧行（current_date 起算，含今日历史批次） */
    @Modifying
    @Query(value = "DELETE FROM prd.conflict_snapshot WHERE detected_at >= current_date", nativeQuery = true)
    void deleteTodaysRows();

    /**
     * 增量模式：只删当日该当事人行（CF-1/2/4/5/6 载荷带 userId；CF-3 产品级行不动）。
     * 参数用 String（payload->>'userId' 是 text；UUID 绑定会落 text=uuid 操作符错误）。
     */
    @Modifying
    @Query(value = "DELETE FROM prd.conflict_snapshot "
            + "WHERE detected_at >= current_date AND payload->>'userId' = :userId", nativeQuery = true)
    void deleteTodaysRowsOfUser(@Param("userId") String userId);

    /** 红色新增判定基线：删除/插入前先取存量未消解红色指纹（含历史日） */
    @Query(value = "SELECT DISTINCT payload->>'fp' FROM prd.conflict_snapshot "
            + "WHERE payload->>'severity' = 'red' AND resolved_at IS NULL", nativeQuery = true)
    List<String> unresolvedRedFingerprints();

    /**
     * 工作台「我的红色冲突」计数（GET /me/summary 只读投影，M2-W4 体验修复）：
     * 未消解红色且当事人=本人（payload.userId 或 subjectId；text 绑定同 deleteTodaysRowsOfUser 口径）。
     */
    @Query(value = "SELECT count(*) FROM prd.conflict_snapshot "
            + "WHERE payload->>'severity' = 'red' AND resolved_at IS NULL "
            + "AND (payload->>'userId' = :userId OR payload->>'subjectId' = :userId)", nativeQuery = true)
    long countUnresolvedRedOfUser(@Param("userId") String userId);
}
