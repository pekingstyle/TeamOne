package cn.teamone.shared.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * W3 事件 Handler 接口（05 §5.1 Handler 注册表）——放 shared、零 Spring 依赖：
 * 各业务域实现本接口，由 app 装配层 EventConsumer 按注册表路由调用。
 *
 * <p>注册语义：{@link #types()} 返回本 Handler 订阅的事件 type 集合；
 * <b>空集合 = 通配</b>（接收所有 type，供日志/审计类 Handler 使用）。</p>
 *
 * <p>幂等红线：消费者侧 processed_event 去重先行（ON CONFLICT DO NOTHING 判定归属），
 * 同一事件在消费组内只会驱动 Handler 一次；Handler 抛异常 → 不 XACK，PEL 待重投。</p>
 */
public interface W3EventHandler {

    /** 订阅的事件 type 集合（如 "requirement.submitted"）；空集合=通配所有 type */
    Set<String> types();

    /** 处理事件载荷（outbox payload jsonb 解析出的 JSON 对象） */
    void handle(JsonNode payload);
}
