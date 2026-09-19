package cn.teamone.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** R-11 改密作废全部刷新令牌：该用户全部令牌行（含已过期/已吊销，逐行幂等置 revoked） */
    List<RefreshToken> findByUserId(UUID userId);
}
