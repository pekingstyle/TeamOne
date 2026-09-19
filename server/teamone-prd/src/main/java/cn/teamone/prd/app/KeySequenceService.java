package cn.teamone.prd.app;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 业务键发号（05 架构文档 §2.4 key_sequence：行锁天然串行）。
 *
 * <p>原生 UPSERT：命中冲突即对既有行加行锁并自增，RETURNING 取回最新序号；
 * 同一事务内串行、跨事务靠行锁排队，绝不重号。调用方在业务写事务内调用，
 * 锁随事务提交释放（发号与业务写同生共死）。</p>
 *
 * <p>业务键格式（05 §2.4 示例）：D-88 / T-12 / TT-9 / REQ-1。</p>
 */
@Service
public class KeySequenceService {

    /** key_sequence 类型 → 业务键前缀 */
    private static final Map<String, String> PREFIXES = Map.of(
            "DEFECT", "D",
            "TASK", "T",
            "TEST_TASK", "TT",
            "REQUIREMENT", "REQ");

    private static final String NEXT_VAL_SQL =
            "INSERT INTO prd.key_sequence(type,next_val) VALUES(?,1) " +
            "ON CONFLICT(type) DO UPDATE SET next_val=key_sequence.next_val+1 " +
            "RETURNING next_val";

    private final JdbcTemplate jdbc;

    public KeySequenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 发下一个业务键，如 nextKey("DEFECT") → "D-89"。
     *
     * @param type DEFECT / TASK / TEST_TASK / REQUIREMENT
     * @throws IllegalArgumentException 类型未注册前缀
     */
    @Transactional // 无外层事务时自开；有则加入（锁随调用方事务提交释放）
    public String nextKey(String type) {
        String prefix = PREFIXES.get(type);
        if (prefix == null) {
            throw new IllegalArgumentException("未注册的 key_sequence 类型: " + type);
        }
        Long nextVal = jdbc.queryForObject(NEXT_VAL_SQL, Long.class, type);
        return prefix + "-" + nextVal;
    }
}
