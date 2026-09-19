package cn.teamone.collab.app;

import cn.teamone.collab.domain.Conversation;
import cn.teamone.collab.domain.Message;
import cn.teamone.collab.domain.Notification;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.collab.repo.MessageRepository;
import cn.teamone.collab.repo.NotificationRepository;
import cn.teamone.platform.domain.FileObject;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.FileObjectRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 消息发送/撤回应用服务（05 §6.2 发消息处理器：WS msg 帧与 REST 等价的事务）。
 *
 * <p>单事务完成：①会话存在+未归档（archived→{@link ErrorCode#COL_4203}）
 * ②sender 是成员（否则 {@link ErrorCode#COL_4210}）③clientMsgId 幂等回放
 * （分区表无跨分区唯一约束——V5 定稿走「事务内先查后插」，命中即返回原消息零写入）
 * ④attachments.fileId 全部 ready 校验（INC-2 红线 4：未 ready 不得被消息引用）
 * ⑤INSERT message ⑥UPDATE conversation.last_message_* ⑦outbox(message.created)
 * ⑧注册事务 afterCommit L3 直推（{@link ImFastFanout}，提交即推 WS 帧——在线用户
 * 零轮询延迟；L2 事件链退化为兜底，先落库后推送铁律不变）。</p>
 *
 * <p><b>撤回（M2-INC-2 T-4，S-6 拍板：软删+system 消息）</b>：仅 sender 本人（PLT_4030）、
 * system 消息不可撤回（PLT_4000）、时间窗 teamone.im.withdraw-window-hours（超窗
 * {@link ErrorCode#COL_4209}）；撤回=置 withdrawn_at + 插灰条 system 消息，DB 原文保留，
 * 出参经 {@code Message.toPayload()} 一律脱敏（红线 3）。重复撤回幂等返回原消息。</p>
 *
 * <p>红线 3：send/withdraw 返回（=事务提交）后调用方才回 WS ack/扇出——「先落库后确认」。</p>
 */
@Service
public class MessageService {

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final FileObjectRepository files;
    private final AppUserRepository users;
    private final OutboxWriter outbox;
    private final ImFastFanout fastFanout;
    private final NotificationRepository notifications;

    public MessageService(ConversationRepository conversations,
                          ConversationMemberRepository members,
                          MessageRepository messages,
                          FileObjectRepository files,
                          AppUserRepository users,
                          OutboxWriter outbox,
                          ImFastFanout fastFanout,
                          NotificationRepository notifications) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.files = files;
        this.users = users;
        this.outbox = outbox;
        this.fastFanout = fastFanout;
        this.notifications = notifications;
    }

    /**
     * 发送消息（幂等，kind 限 DB CHECK 五值：text/file/image/card/system）。
     *
     * @param clientMsgId WS 发帧幂等键；null 时不做回放（服务端代生成，仅落库）
     * @param attachments WS 帧附件数组 [{fileId,name,size,mime,thumbUrl}]；null/空=无附件
     * @param mentions    被 @ 成员 id 清单（V16 R-10e，可选）；服务端过滤为「会话成员 − 发送者」
     * @return 落库消息（首次发送）或命中回放的原消息（重复帧，无新行）
     */
    @Transactional
    public Message send(UUID conversationId, UUID senderId, UUID clientMsgId,
                        String kind, String body, List<Map<String, Object>> attachments,
                        List<UUID> mentions, String refType, UUID refId) {
        var conv = conversations.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "会话不存在: " + conversationId));
        if (conv.getArchivedAt() != null) {
            throw new BusinessException(ErrorCode.COL_4203,
                    "话题已归档，禁止写入", java.util.List.of(
                    "conversationId=" + conversationId,
                    "archivedReason=" + conv.getArchivedReason()));
        }
        if (!members.existsByConversationIdAndUserId(conversationId, senderId)) {
            throw new BusinessException(ErrorCode.COL_4210,
                    "非会话成员，禁止发言", java.util.List.of("conversationId=" + conversationId));
        }

        // 幂等回放：同 (conversation_id, client_msg_id) 已存在 → 返回原消息（红线 1）
        if (clientMsgId != null) {
            var replayed = messages.findFirstByConversationIdAndClientMsgIdOrderByIdDesc(
                    conversationId, clientMsgId);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }

        List<UUID> mentioned = resolveMentions(mentions, conversationId, senderId);
        Message msg = new Message();
        msg.setConversationId(conversationId);
        msg.setSenderId(senderId);
        msg.setKind(kind == null || kind.isBlank() ? Message.KIND_TEXT : kind);
        msg.setBody(body);
        msg.setAttachments(requireReadyAttachments(attachments));
        msg.setMentions(mentioned);
        msg.setRefType(refType);
        msg.setRefId(refId);
        msg.setClientMsgId(clientMsgId);
        messages.saveAndFlush(msg); // IDENTITY：插入即取回全局单调 id

        conv.setLastMessageId(msg.getId());
        conv.setLastMessageAt(msg.getCreatedAt());
        conversations.save(conv);

        // L2 广播（权威目录 §5.1：message.created → collab WS conv:{cid} 扇出；
        // payload 即消息实体（§4.3），FanoutHandler 据此组帧）。附 senderName 供
        // @提醒帧/通知渲染（不回查 DB）；前端按未知字段忽略，向后兼容。
        String senderName = displayName(senderId);
        Map<String, Object> payload = msg.toPayload();
        payload.put("senderName", senderName);
        outbox.append("message", conversationId, "message.created", payload, senderId);

        // @提醒 notification 事务内落库（R-10e 铁律：先落库后推送——提交前通知行已在，
        // 推送失败/宕机都不丢；重复推送由前端/收件箱幂等渲染消化）
        if (!mentioned.isEmpty()) {
            Map<String, Object> notify = ImFastFanout.buildNotifyPayload(payload);
            for (UUID uid : mentioned) {
                Notification n = new Notification();
                n.setUserId(uid);
                n.setKind(Notification.KIND_IM_MENTION);
                n.setPayload(new LinkedHashMap<>(notify));
                notifications.save(n);
            }
        }

        // L3 直推（05 §6.2「提交后 → WS 扇出 conv:{cid}（L3）」；IM 延迟诊断批落地）：
        // 注册事务 afterCommit 回调，提交即直推 message 帧 + unread 帧 + @提醒 notify 帧，
        // 在线用户零轮询延迟；L2 事件链保留为兜底（FanoutHandler 按直推标记去重）。
        // 失败仅 WARN 不回滚（红线 5）。
        fastFanout.afterCommit(msg, senderName);
        return msg;
    }

    /** 兼容旧签名（无 mentions；M1/M2 调用方与单测沿用） */
    @Transactional
    public Message send(UUID conversationId, UUID senderId, UUID clientMsgId,
                        String kind, String body, List<Map<String, Object>> attachments,
                        String refType, UUID refId) {
        return send(conversationId, senderId, clientMsgId, kind, body, attachments,
                null, refType, refId);
    }

    /** 兼容旧签名（无附件；M1 调用方/单测沿用） */
    @Transactional
    public Message send(UUID conversationId, UUID senderId, UUID clientMsgId,
                        String kind, String body, String refType, UUID refId) {
        return send(conversationId, senderId, clientMsgId, kind, body, null, refType, refId);
    }

    /**
     * 撤回（POST /{cid}/messages/{msgId}/withdraw 同一事务语义）：
     * 仅 sender 本人 → system 拒绝 → 时间窗（hours≤0 视为不限，测试口径）→
     * 置 withdrawn_at → 插灰条 system 消息（"xxx 撤回了一条消息"，sender=操作者，
     * last_message_* 推进到灰条行）→ 返回；WS message.withdrawn 扇出由调用方在
     * 事务提交后 L3 直接推（沿 gate 推送先例）。
     *
     * @param withdrawWindowHours 可撤回时间窗（小时；≤0=不限，测试可配 0）
     * @return 被撤回的消息（withdrawn_at 已置；再次调用幂等返回原消息）
     */
    @Transactional
    public Message withdraw(UUID conversationId, long messageId, UUID operatorId,
                            long withdrawWindowHours) {
        Message msg = messages.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "消息不存在: " + messageId));
        if (!msg.getConversationId().equals(conversationId)) {
            throw new BusinessException(ErrorCode.PLT_4040, "消息不属于该会话");
        }
        if (Message.KIND_SYSTEM.equals(msg.getKind())) {
            throw new BusinessException(ErrorCode.PLT_4000, "system 消息不可撤回");
        }
        if (!operatorId.equals(msg.getSenderId())) {
            throw new BusinessException(ErrorCode.PLT_4030, "仅发送者本人可撤回消息");
        }
        if (msg.isWithdrawn()) {
            return msg; // 幂等：已撤回直接返回，不重复插灰条（红线 1）
        }
        if (withdrawWindowHours > 0) {
            Instant deadline = msg.getCreatedAt().plus(Duration.ofHours(withdrawWindowHours));
            if (Instant.now().isAfter(deadline)) {
                throw new BusinessException(ErrorCode.COL_4209, "消息已超出可撤回时间窗",
                        List.of("createdAt=" + msg.getCreatedAt(),
                                "windowHours=" + withdrawWindowHours));
            }
        }

        msg.setWithdrawnAt(Instant.now());
        messages.saveAndFlush(msg); // 原文保留，出参脱敏在 toPayload（红线 3）

        appendSystemMessage(conversationId, operatorId,
                displayName(operatorId) + " 撤回了一条消息");
        return msg;
    }

    // ==================== 内部 ====================

    /**
     * @提醒名单规整（V16 R-10e）：去重保序、剔除发送者本人与非会话成员
     * （客户端成员快照可能过期，静默丢弃而非报错）；null/空 → 空名单零副作用。
     */
    private List<UUID> resolveMentions(List<UUID> mentions, UUID conversationId, UUID senderId) {
        if (mentions == null || mentions.isEmpty()) {
            return new ArrayList<>();
        }
        Set<UUID> memberIds = new java.util.LinkedHashSet<>(
                members.findUserIdsByConversationId(conversationId));
        List<UUID> out = new ArrayList<>();
        for (UUID uid : new LinkedHashSet<>(mentions)) {
            if (uid == null || uid.equals(senderId) || !memberIds.contains(uid)) {
                continue;
            }
            out.add(uid);
        }
        return out;
    }


    /**
     * 附件引用校验（INC-2 红线 4）：attachments.fileId 必须全部存在且 status=ready。
     * 未 ready（uploading/伪造 id）→ PLT_4000。fileId 兼容 JSON 里的字符串形态。
     */
    private List<Map<String, Object>> requireReadyAttachments(List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new ArrayList<>();
        }
        List<UUID> fileIds = new ArrayList<>();
        for (Map<String, Object> att : attachments) {
            Object raw = att == null ? null : att.get("fileId");
            if (raw == null) {
                continue;
            }
            try {
                UUID fileId = raw instanceof UUID u ? u : UUID.fromString(raw.toString());
                fileIds.add(fileId);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.PLT_4000, "附件 fileId 非法: " + raw);
            }
        }
        if (fileIds.isEmpty()) {
            return new ArrayList<>(attachments);
        }
        Map<UUID, FileObject> ready = new LinkedHashMap<>();
        for (FileObject f : files.findAllByIdIn(fileIds)) {
            if (FileObject.STATUS_READY.equals(f.getStatus())) {
                ready.put(f.getId(), f);
            }
        }
        for (UUID fileId : fileIds) {
            if (!ready.containsKey(fileId)) {
                throw new BusinessException(ErrorCode.PLT_4000,
                        "附件文件未就绪或不存在: " + fileId);
            }
        }
        return new ArrayList<>(attachments);
    }

    /** 灰条系统消息直插 + last_message_* 推进（withdraw 事务内；不入 send 校验链） */
    private void appendSystemMessage(UUID conversationId, UUID senderId, String systemText) {
        Message sys = new Message();
        sys.setConversationId(conversationId);
        sys.setSenderId(senderId);
        sys.setKind(Message.KIND_SYSTEM);
        sys.setBody(systemText);
        messages.saveAndFlush(sys);

        Conversation conv = conversations.findById(conversationId).orElse(null);
        if (conv != null) {
            conv.setLastMessageId(sys.getId());
            conv.setLastMessageAt(sys.getCreatedAt());
            conversations.save(conv);
        }
    }

    /** 操作者展示名（灰条文案用；查不到降级 username/id 短码） */
    private String displayName(UUID userId) {
        return users.findById(userId)
                .map(u -> {
                    String name = u.getDisplayName();
                    return name == null || name.isBlank() ? u.getUsername() : name;
                })
                .orElseGet(() -> userId.toString().substring(0, 8));
    }
}
