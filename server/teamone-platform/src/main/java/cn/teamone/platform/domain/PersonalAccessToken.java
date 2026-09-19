package cn.teamone.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 个人访问令牌（Personal Access Token - PAT）实体。
 *
 * <p>映射表 {@code platform.personal_access_token}（Flyway V14 迁移）。
 * 为终端 Git CLI 客户端与自动化脚本提供安全的长期鉴权凭据。</p>
 *
 * <p>安全设计规范：
 * <ul>
 *   <li><b>单向哈希</b>：数据库绝对不保存明文令牌，仅保存其 SHA-256 哈希值（{@code token_hash}）；</li>
 *   <li><b>脱敏展示</b>：{@code token_prefix} 记录类似 {@code t1_pat_a1b2c3} 的前缀用于界面可读辨识；</li>
 *   <li><b>作用域隔离</b>：{@code scopes} 支持细粒度权限控制（如 {@code repo:read,repo:write,api}）；</li>
 *   <li><b>活跃追踪</b>：记录 {@code last_used_at} 供安全审计与僵尸令牌清理。</li>
 * </ul>
 * </p>
 */
@Entity
@Table(name = "personal_access_token", schema = "platform")
public class PersonalAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 令牌所属用户的唯一标识 */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 令牌用途描述名称（如 "VSCode 插件", "本地开发机"） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 令牌 SHA-256 密文（64位小写十六进制字符串，具有唯一约束） */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** 令牌安全脱敏前缀（如 t1_pat_a1b2c3，便于用户在列表中区分） */
    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    /** 权限作用域范围（逗号分隔，默认 all） */
    @Column(nullable = false, length = 255)
    private String scopes = "all";

    /** 过期绝对时间戳（为 null 表示永不过期） */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** 最近一次被使用鉴权的时间戳 */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public PersonalAccessToken() {}

    public PersonalAccessToken(UUID userId, String name, String tokenHash,
                               String tokenPrefix, String scopes, Instant expiresAt) {
        this.userId = userId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.scopes = scopes != null ? scopes : "all";
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    /** 判定该令牌是否已经过期 */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
