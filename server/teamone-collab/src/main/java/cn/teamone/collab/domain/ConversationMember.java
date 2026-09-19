package cn.teamone.collab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话成员（collab.conversation_member，复合主键 conversation_id+user_id）。
 *
 * <p>WS conv 频道订阅鉴权与 msg 发送成员校验的真相源（05 §4.4）；主键天然去重，
 * autoTopic 补拉缺失成员重复执行无副作用（红线 1）。</p>
 */
@Entity
@Table(name = "conversation_member", schema = "collab")
@IdClass(ConversationMember.Pk.class)
public class ConversationMember {

    // ---------- role（CHECK: owner/member） ----------
    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_MEMBER = "member";

    @Id
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String role = ROLE_MEMBER;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ConversationMember() {}

    public ConversationMember(UUID conversationId, UUID userId, String role) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
    }

    public UUID getConversationId() { return conversationId; }
    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getCreatedAt() { return createdAt; }

    /** 复合主键 */
    public static class Pk implements Serializable {
        private UUID conversationId;
        private UUID userId;

        public Pk() {}
        public Pk(UUID conversationId, UUID userId) {
            this.conversationId = conversationId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(conversationId, pk.conversationId)
                    && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conversationId, userId);
        }
    }
}
