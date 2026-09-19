package cn.teamone.platform.infra;

import java.util.UUID;

/**
 * 事务性发件箱写入口（05 §5 事件机制：跨模块联动只走 Outbox 事件）。
 *
 * <p><b>事务语义（MUST）</b>：实现类 {@code @Transactional}（REQUIRED）——有调用方事务则加入、
 * 无则自开；事件与业务写在同一事务内原子提交。实现绝不使用 REQUIRES_NEW 另开事务。</p>
 */
public interface OutboxWriter {

    /**
     * 追加一条待投递事件（published_at=NULL，retry_count=0）。
     *
     * @param aggregateType 聚合类型（如 work_item / release）
     * @param aggregateId   聚合 id
     * @param type          事件类型（如 defect.blocked_changed）
     * @param payload       载荷：Map/POJO 由 Jackson 序列化；String 视为已序列化的 JSON 文档；null 记 {}
     * @param actorId       操作人（系统动作为 null）
     */
    void append(String aggregateType, UUID aggregateId, String type, Object payload, UUID actorId);
}
