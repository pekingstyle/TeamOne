package cn.teamone.platform.dto;

import cn.teamone.platform.domain.PersonalAccessToken;

import java.time.Instant;
import java.util.UUID;

/**
 * 个人访问令牌（PAT）数据传输契约。
 */
public class PatDto {

    /**
     * 创建个人访问令牌请求。
     *
     * @param name 令牌描述名称（如 "本地开发机"）
     * @param scopes 权限作用域（如 "all" 或 "repo:read,repo:write"）
     * @param expiresDays 有效天数（如 30、90；为 null 或 ≤0 表示永不过期）
     */
    public record CreatePatRequest(
            String name,
            String scopes,
            Integer expiresDays
    ) {}

    /**
     * 创建令牌成功响应（仅此一次返回明文完整令牌）。
     *
     * @param id 令牌 ID
     * @param name 令牌名称
     * @param rawToken 完整明文令牌（仅展示一次，如 t1_pat_xxxxxxxx）
     * @param tokenPrefix 脱敏前缀
     * @param scopes 权限作用域
     * @param expiresAt 过期时间戳
     * @param createdAt 创建时间戳
     */
    public record CreatePatResponse(
            UUID id,
            String name,
            String rawToken,
            String tokenPrefix,
            String scopes,
            Instant expiresAt,
            Instant createdAt
    ) {}

    /**
     * 令牌概要列表项（脱敏视图，不含明文或密文哈希）。
     *
     * @param id 令牌 ID
     * @param name 令牌名称
     * @param tokenPrefix 脱敏前缀
     * @param scopes 权限作用域
     * @param expiresAt 过期时间戳
     * @param lastUsedAt 最近使用时间戳
     * @param createdAt 创建时间戳
     * @param expired 是否已过期
     */
    public record PatSummary(
            UUID id,
            String name,
            String tokenPrefix,
            String scopes,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant createdAt,
            boolean expired
    ) {
        public static PatSummary fromEntity(PersonalAccessToken t) {
            return new PatSummary(
                    t.getId(),
                    t.getName(),
                    t.getTokenPrefix(),
                    t.getScopes(),
                    t.getExpiresAt(),
                    t.getLastUsedAt(),
                    t.getCreatedAt(),
                    t.isExpired()
            );
        }
    }
}
