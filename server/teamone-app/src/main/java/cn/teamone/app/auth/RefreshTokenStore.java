package cn.teamone.app.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 刷新令牌存储抽象（M1-W1，架构设计 §1.5 认证横切）。
 *
 * <p>现状：表存储（infra.refresh_token，JPA）；Valkey 接入后切 {@code rt:{hash}} 键存储，
 * 通过 {@code teamone.auth.refresh-store=jpa|redis} 切换——切存储只动实现，不动签发/旋转/吊销语义。</p>
 *
 * <p>语义约定：hash 为令牌 SHA-256（明文永不落库）；{@link #findValidUserId} 只返回有效
 * （未过期、未吊销）令牌；旋转 = 调用方先 {@link #revoke} 旧 hash 再 {@link #store} 新令牌。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
public interface RefreshTokenStore {

    /** 存储新签发的刷新令牌（hash→userId，含过期时间） */
    void store(String tokenHash, UUID userId, Instant expiresAt);

    /** 查询有效令牌的归属用户；无效（过期/吊销/不存在）返回 empty */
    Optional<UUID> findValidUserId(String tokenHash);

    /** 吊销（登出/旋转/踢人下线的原子操作；对不存在的 hash 静默幂等） */
    void revoke(String tokenHash);
}
