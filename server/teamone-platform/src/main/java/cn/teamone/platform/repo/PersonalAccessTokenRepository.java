package cn.teamone.platform.repo;

import cn.teamone.platform.domain.PersonalAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 个人访问令牌仓库接口（表 {@code platform.personal_access_token}）。
 */
public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, UUID> {

    /**
     * 根据令牌的 SHA-256 哈希密文查询令牌记录（命中唯一索引 idx_pat_hash）。
     *
     * @param tokenHash 64位小写十六进制哈希
     * @return 令牌可选对象
     */
    Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

    /**
     * 查询指定用户拥有的全部令牌（按创建时间倒序）。
     *
     * @param userId 用户 ID
     * @return 令牌列表
     */
    List<PersonalAccessToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * 根据用户 ID 与令牌 ID 查询特定令牌（防越权操作）。
     *
     * @param id     令牌唯一标识 UUID
     * @param userId 所属用户 ID
     * @return 令牌可选对象
     */
    Optional<PersonalAccessToken> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 更新令牌最近使用时间。
     *
     * @param id 令牌 ID
     * @param now 当前时间戳
     * @return 影响行数
     */
    @Modifying
    @Query("UPDATE PersonalAccessToken t SET t.lastUsedAt = :now WHERE t.id = :id")
    int updateLastUsedAt(@Param("id") UUID id, @Param("now") Instant now);
}
