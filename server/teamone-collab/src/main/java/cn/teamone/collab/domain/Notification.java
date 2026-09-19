package cn.teamone.collab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 站内通知（collab.notification，V5 迁移）。
 *
 * <p>M1 口径：通知只落库不经 WS（HTTP 轮询收件箱）；kind 取约定值
 * requirement.review（评审邀请）/ release.gate（门禁解除）；payload 为事件投影，
 * 渲染所需事实全部随行（collab 禁 import prd——红线 2，一切取数靠 payload）。</p>
 */
@Entity
@Table(name = "notification", schema = "collab")
public class Notification {

    /** 评审邀请（requirement.submitted → reviewerIds 逐条） */
    public static final String KIND_REQUIREMENT_REVIEW = "requirement.review";
    /** 门禁解除（defect.blocked_changed 且 blocked=false → productOwnerId） */
    public static final String KIND_RELEASE_GATE = "release.gate";
    /** IM @提醒（V16 R-10e：消息 mentions 非空 → 被成员逐条，payload 含会话/消息/摘要事实） */
    public static final String KIND_IM_MENTION = "im.mention";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = Map.of();

    /** NULL=未读（部分索引 idx_notification_unread 只覆盖未读行） */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID v) { this.userId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> v) { this.payload = v; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant v) { this.readAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
