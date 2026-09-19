package cn.teamone.platform.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@link OutboxWriter} 的 JPA 实现：直接 save，不另开事务。
 *
 * <p>@Transactional(REQUIRED) 是「随调用方事务提交」语义的唯一保障——
 * 调用方（门禁/状态机服务）的事务提交时事件才可见；调用方回滚则事件一并回滚。</p>
 */
@Component
public class OutboxJpaWriter implements OutboxWriter {

    /** 静态共享并注册 jsr310 等模块（Instant 时间序列化；质量整改 M2，替代每次 new） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.findAndRegisterModules();
    }

    private final OutboxEventRepository repository;

    public OutboxJpaWriter(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional // REQUIRED：加入调用方事务（MUST，不另开事务）
    public void append(String aggregateType, UUID aggregateId, String type, Object payload, UUID actorId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setType(type);
        event.setPayload(toJson(payload));
        event.setActorId(actorId);
        repository.save(event);
    }

    static String toJson(Object payload) {
        if (payload == null) {
            return "{}";
        }
        if (payload instanceof String s) {
            return s; // 已是 JSON 文档（调用方自行序列化的场景）
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("outbox payload 序列化失败: " + payload.getClass(), ex);
        }
    }
}
