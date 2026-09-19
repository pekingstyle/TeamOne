package cn.teamone.prd.repo;

import cn.teamone.prd.domain.Release;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 版本发布仓库（表 prd.release）。 */
public interface ReleaseRepository extends JpaRepository<Release, UUID> {

    Optional<Release> findByKey(String key);

    List<Release> findByProductIdOrderByCreatedAtDesc(UUID productId);

    /** SELECT .. FOR UPDATE（05 §6.1 publish 门禁 / recalcGate 的行锁读取） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Release r where r.id = :id")
    Optional<Release> findForUpdate(@Param("id") UUID id);
}
