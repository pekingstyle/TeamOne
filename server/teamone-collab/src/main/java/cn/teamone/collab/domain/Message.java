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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 消息（collab.message，按月 RANGE 分区主表，V4 迁移）。
 *
 * <p>id 为 bigint identity（DB 生成——红线：@GeneratedValue(IDENTITY)，插入即取回
 * 全局单调值，兼做会话内排序）。DB 主键为 (id, created_at) 复合（分区键要求），
 * Hibernate 仅管理 id 列；created_at 由应用侧赋值（默认 now）保证主键完整。</p>
 *
 * <p>幂等：client_msg_id 无跨分区唯一约束（分区表限制，V5 注释定稿），
 * 走「事务内先查后插」回放判定（{@code MessageService#send}）。</p>
 */
@Entity
@Table(name = "message", schema = "collab")
public class Message {

    // ---------- kind（CHECK: text/file/image/card/system） ----------
    public static final String KIND_TEXT = "text";
    public static final String KIND_FILE = "file";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_CARD = "card";
    public static final String KIND_SYSTEM = "system";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // DB identity 生成，插入即回填
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(nullable = false, columnDefinition = "text")
    private String kind = KIND_TEXT;

    /** kind=text 的内容；card 时为 jsonb 字符串 */
    @Column
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> attachments = new ArrayList<>();

    @Column(name = "ref_type")
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    /** WS 发帧幂等键（V5：部分索引 idx_message_client_msg_id 供回放查询） */
    @Column(name = "client_msg_id")
    private UUID clientMsgId;

    /**
     * 被 @ 提醒的成员 id 清单（V16，R-10e）：发送帧可选携带，服务端过滤为「会话成员 − 发送者」
     * 后落库；非空时同事务写 collab.notification(kind='im.mention') 并在提交后推 WS notify 帧。
     * PG uuid[] 数组列（分区父表 V16 直接 ALTER），空数组=无提醒。
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "mentions", nullable = false, columnDefinition = "uuid[]")
    private List<UUID> mentions = new ArrayList<>();

    /**
     * 撤回时间（V9；NULL=未撤回）。撤回=脱敏非删除（红线 3）：DB 原文保留，
     * 出参一律经 {@link #toPayload()} 脱敏；不加 withdrawn_by 列（撤回仅限 sender 本人，
     * sender_id 即操作者，system 灰条的 sender_id 亦为操作者）。
     */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID v) { this.conversationId = v; }
    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID v) { this.senderId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public List<Map<String, Object>> getAttachments() { return attachments; }
    public void setAttachments(List<Map<String, Object>> v) { this.attachments = v; }
    public String getRefType() { return refType; }
    public void setRefType(String v) { this.refType = v; }
    public UUID getRefId() { return refId; }
    public void setRefId(UUID v) { this.refId = v; }
    public UUID getClientMsgId() { return clientMsgId; }
    public void setClientMsgId(UUID v) { this.clientMsgId = v; }
    public List<UUID> getMentions() { return mentions; }
    public void setMentions(List<UUID> v) {
        this.mentions = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(Instant v) { this.withdrawnAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    public boolean isWithdrawn() { return withdrawnAt != null; }

    /**
     * 消息投影（outbox message.created 事件与 WS/REST 出参同源）。
     * <b>撤回脱敏（红线 3）</b>：withdrawn=true、body=null、attachments=[]——
     * 原文只在 DB 保留，任何出参（GET messages / WS 帧重放）不得泄露。
     */
    public Map<String, Object> toPayload() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("msgId", id);
        m.put("conversationId", conversationId);
        m.put("senderId", senderId);
        m.put("kind", kind);
        m.put("body", isWithdrawn() ? null : body);
        m.put("attachments", isWithdrawn() ? List.of() : attachments);
        m.put("refType", refType);
        m.put("refId", refId);
        m.put("clientMsgId", Objects.toString(clientMsgId, null));
        // V16 @提醒：mentions 随 payload 全链路透传（outbox→FanoutHandler L2 兜底据此补推 notify 帧）
        m.put("mentions", mentions.stream().map(UUID::toString).toList());
        m.put("createdAt", createdAt.toString());
        m.put("withdrawn", isWithdrawn());
        if (isWithdrawn()) {
            m.put("withdrawnAt", withdrawnAt.toString());
        }
        return m;
    }
}
