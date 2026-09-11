package cn.teamone.platform.authz;

import java.util.Optional;
import java.util.UUID;

/**
 * 授权决策缓存 SPI（架构设计 05 文档 §1.5：决策结果写 Valkey {@code acl:{uid}}，TTL 5min 兜底；
 * acl.changed 事件精准失效——事件链路 M1-W3 接入，此前仅靠 TTL 过期）。
 *
 * <p>实现放在 teamone-app（装配层），platform 不依赖任何缓存客户端。
 * 缓存是加速器而非真相：真相永远在 {@code platform.resource_acl} 查询链；
 * 缓存故障不得影响授权主流程（实现方需自行容错降级）。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
public interface DecisionCache {

    /** 取缓存决策；miss 或缓存不可用返回 empty（调用方回退查库） */
    Optional<Boolean> get(UUID userId, String resourceType, UUID resourceId, String action);

    /** 写缓存决策；实现方自行容错（写失败静默忽略） */
    void put(UUID userId, String resourceType, UUID resourceId, String action, boolean allowed);

    /** 精准失效：acl.changed 事件到达时按用户清空其全部决策（W3 事件消费者调用） */
    void evictUser(UUID userId);
}
