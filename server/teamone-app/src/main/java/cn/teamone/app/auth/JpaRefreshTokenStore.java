package cn.teamone.app.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 刷新令牌 · 表存储实现（infra.refresh_token，M0 既有行为原样封装）。
 * 由 {@link AuthStoreConfig} 按配置装配，本类不做 Spring 注解扫描。
 *
 * @author Ivan Yang, 2026-09-11
 */
public class JpaRefreshTokenStore implements RefreshTokenStore {

    private final RefreshTokenRepository repo;

    public JpaRefreshTokenStore(RefreshTokenRepository repo) {
        this.repo = repo;
    }

    @Override
    public void store(String tokenHash, UUID userId, Instant expiresAt) {
        RefreshToken entity = new RefreshToken();
        entity.setTokenHash(tokenHash);
        entity.setUserId(userId);
        entity.setExpiresAt(expiresAt);
        repo.save(entity);
    }

    @Override
    public Optional<UUID> findValidUserId(String tokenHash) {
        return repo.findByTokenHash(tokenHash)
                .filter(RefreshToken::isValid)
                .map(RefreshToken::getUserId);
    }

    @Override
    public void revoke(String tokenHash) {
        repo.findByTokenHash(tokenHash).ifPresent(t -> {
            t.setRevokedAt(Instant.now());
            repo.save(t);
        });
    }
}
