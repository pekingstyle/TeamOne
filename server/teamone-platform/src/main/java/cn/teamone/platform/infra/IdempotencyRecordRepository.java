package cn.teamone.platform.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/** 幂等快照仓库（表 infra.idempotency_record）。 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}
