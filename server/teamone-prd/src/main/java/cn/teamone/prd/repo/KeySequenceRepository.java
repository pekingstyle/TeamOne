package cn.teamone.prd.repo;

import cn.teamone.prd.domain.KeySequence;
import org.springframework.data.jpa.repository.JpaRepository;

/** 业务键序号仓库（表 prd.key_sequence）。发号走 KeySequenceService 的原生 UPSERT（行锁天然串行）。 */
public interface KeySequenceRepository extends JpaRepository<KeySequence, String> {
}
