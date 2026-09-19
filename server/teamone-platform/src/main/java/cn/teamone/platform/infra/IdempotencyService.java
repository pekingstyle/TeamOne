package cn.teamone.platform.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * REST 幂等服务（05 §3.1：非幂等写支持 Idempotency-Key 头，服务端去重 24h）。
 *
 * <p>用法（②的 Controller）：进来先 {@link #find}——命中直接回放快照；
 * 业务写成功后 {@link #record} 存响应快照（与业务写同一事务）。</p>
 *
 * <p>record 为原生 INSERT .. ON CONFLICT(key) DO NOTHING（质量整改 B1）：
 * 首写原子胜出，并发同 Key 不再依赖「先查后插」而产生主键冲突 500。</p>
 */
@Service
public class IdempotencyService {

    /** 幂等命中的响应快照 */
    public record StoredResponse(int status, String bodyJson) {}

    /** 静态共享并注册 jsr310 等模块（Instant 时间序列化；质量整改 M2，替代每次 new） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.findAndRegisterModules();
    }

    /** 首写原子胜出：冲突静默放弃（首个事务的快照保留） */
    private static final String RECORD_SQL =
            "INSERT INTO infra.idempotency_record(key,response_status,response_body,created_at) "
            + "VALUES(:k,:s,cast(:b as jsonb),now()) ON CONFLICT(key) DO NOTHING";

    private final IdempotencyRecordRepository repository;
    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyService(IdempotencyRecordRepository repository, NamedParameterJdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** 查幂等快照；命中说明同 Key 请求已成功处理过 */
    public Optional<StoredResponse> find(String key) {
        return repository.findById(key)
                .map(r -> new StoredResponse(r.getResponseStatus(), r.getResponseBody()));
    }

    /** 记录响应快照（随调用方事务提交；body 为 Map/POJO 则 Jackson 序列化，String 视为 JSON 文档） */
    @Transactional // REQUIRED：与业务写同一事务（同提交/同回滚）
    public void record(String key, int status, Object body) {
        jdbc.update(RECORD_SQL, new MapSqlParameterSource()
                .addValue("k", key)
                .addValue("s", status)
                .addValue("b", toJson(body)));
    }

    private static String toJson(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("idempotency response 序列化失败: " + body.getClass(), ex);
        }
    }
}
