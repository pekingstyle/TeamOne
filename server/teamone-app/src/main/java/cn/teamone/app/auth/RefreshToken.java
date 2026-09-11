package cn.teamone.app.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 刷新令牌（随机串，库内存 sha256 哈希，可吊销）。表 infra.refresh_token */
@Entity
@Table(name = "refresh_token", schema = "infra")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID v) { this.userId = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isValid() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
