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
 * 已读游标（collab.user_conversation_cursor，V9 迁移，05 §2.4 定稿结构）。
 *
 * <p>未读真相 = conversation.last_message_id - last_read_message_id（GET /conversations
 * SQL 差值一次算出）；本表行由 read 帧/upsert 只前进不回退（GREATEST）维护，
 * Valkey unread:{uid} Hash 仅缓存加速（INC-2 红线 2）。</p>
 */
@Entity
@Table(name = "user_conversation_cursor", schema = "collab")
@IdClass(UserConversationCursor.Pk.class)
public class UserConversationCursor {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "last_read_message_id", nullable = false)
    private long lastReadMessageId = 0;

    @Column(name = "read_at", nullable = false)
    private Instant readAt = Instant.now();

    public UserConversationCursor() {}

    public UserConversationCursor(UUID userId, UUID conversationId, long lastReadMessageId) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.lastReadMessageId = lastReadMessageId;
    }

    public UUID getUserId() { return userId; }
    public UUID getConversationId() { return conversationId; }
    public long getLastReadMessageId() { return lastReadMessageId; }
    public void setLastReadMessageId(long v) { this.lastReadMessageId = v; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant v) { this.readAt = v; }

    /** 复合主键 */
    public static class Pk implements Serializable {
        private UUID userId;
        private UUID conversationId;

        public Pk() {}
        public Pk(UUID userId, UUID conversationId) {
            this.userId = userId;
            this.conversationId = conversationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(conversationId, pk.conversationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, conversationId);
        }
    }
}
