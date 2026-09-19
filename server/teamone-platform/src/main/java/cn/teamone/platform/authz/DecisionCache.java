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

    // ==================== 仓库判定缓存（⑥i-A1 M-a · A7①，docs/v2/13 §4.4 键版本方案） ====================
    // 键设计（总监裁决口径）：
    //   * 版本键  acl:repo:{repoId}:ver  —— Long，读时懒建 = 1；
    //   * 决策键  acl:dec:{repoId}:{ver}:{userId}:{action} —— 值 "1"/"0"，TTL 沿 acl-ttl-seconds 兜底。
    // 该仓库任何 repo_member 变更 → bumpRepoVersion（INCR ver + DEL 该仓判定键）→ 旧版本键因
    // 版本号进键而整体惰性失效（O(1)，无成员枚举、无漏驱逐）；TTL 5min 物理过期兜底。
    // 缓存只是加速器：实现方任何 Valkey 异常必须静默降级（返回 empty / 忽略写），授权主流程直查 DB。

    /** 读仓库判定版本号；键缺失时懒建为 1（setIfAbsent）；缓存不可用返回 empty（调用方降级直查 DB） */
    Optional<Long> repoVersion(UUID repoId);

    /** 读带版本的仓库判定决策；miss 或缓存不可用返回 empty */
    Optional<Boolean> getRepoDecision(UUID repoId, long version, UUID userId, String action);

    /** 写带版本的仓库判定决策；实现方自行容错（写失败静默忽略） */
    void putRepoDecision(UUID repoId, long version, UUID userId, String action, boolean allowed);

    /** 成员变更时调用：INCR 版本号 + DEL 该仓全部判定键（旧版本键因版本进键同时失去可命中性） */
    void bumpRepoVersion(UUID repoId);
}
