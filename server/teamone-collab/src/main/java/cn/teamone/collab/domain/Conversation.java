package cn.teamone.collab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话（05 架构文档 §2.4 collab.conversation，V4 迁移落地）。
 *
 * <p>type='topic' 的对象化话题：target_type+target_id 唯一（部分索引
 * uq_conversation_topic 保证一个对象一个话题，③b 自动建题幂等的兜底）；
 * archived_at 非空即已归档（延迟归档的幂等判据）。</p>
 */
@Entity
@Table(name = "conversation", schema = "collab")
public class Conversation {

    // ---------- type（CHECK: dm/group/topic/channel） ----------
    public static final String TYPE_DM = "dm";
    public static final String TYPE_GROUP = "group";
    public static final String TYPE_TOPIC = "topic";
    public static final String TYPE_CHANNEL = "channel";

    // ---------- archived_reason（CHECK 无 DB 约束，约定值） ----------
    /** 对象关闭类事件的延迟归档（V5 扫描器唯一写入值） */
    public static final String ARCHIVE_REASON_TARGET_CLOSED = "target_closed";
    /** 迭代完成（M2-INC-1 W2：sprint.completed → ArchiveHandler，05 §5.1 sprint_ended 口径） */
    public static final String ARCHIVE_REASON_SPRINT_ENDED = "sprint_ended";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String type;

    /** dm 为 NULL（成员即名字）；topic 建题时存对象标题 */
    @Column
    private String name;

    /** dm 专用：'dm:{小id}:{大id}'，防裂会话 */
    @Column(name = "dedup_key", unique = true)
    private String dedupKey;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "auto_created", nullable = false)
    private boolean autoCreated = false;

    @Column(name = "pinned_message_id")
    private Long pinnedMessageId;

    /** NULL=未归档（归档幂等判据，红线 1） */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_reason")
    private String archivedReason;

    @Column(name = "last_message_id")
    private Long lastMessageId;

    /** 会话列表排序（发消息同事务冗余更新） */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String v) { this.dedupKey = v; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String v) { this.targetType = v; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID v) { this.targetId = v; }
    public boolean isAutoCreated() { return autoCreated; }
    public void setAutoCreated(boolean v) { this.autoCreated = v; }
    public Long getPinnedMessageId() { return pinnedMessageId; }
    public void setPinnedMessageId(Long v) { this.pinnedMessageId = v; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant v) { this.archivedAt = v; }
    public String getArchivedReason() { return archivedReason; }
    public void setArchivedReason(String v) { this.archivedReason = v; }
    public Long getLastMessageId() { return lastMessageId; }
    public void setLastMessageId(Long v) { this.lastMessageId = v; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant v) { this.lastMessageAt = v; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
