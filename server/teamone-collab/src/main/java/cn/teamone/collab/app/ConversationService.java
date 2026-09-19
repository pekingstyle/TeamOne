package cn.teamone.collab.app;

import cn.teamone.collab.domain.Conversation;
import cn.teamone.collab.domain.ConversationMember;
import cn.teamone.collab.domain.Message;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.collab.repo.MessageRepository;
import cn.teamone.collab.repo.UserConversationCursorRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 会话应用服务（05 §6.2 话题自动化 + M2-INC-2 T-2/T-3：dm/group 写端点、成员管理、未读游标）。
 *
 * <p><b>幂等红线（红线 1）</b>：autoTopic 靠 (target_type,target_id) 部分唯一索引
 * （先查后插+并发兜底捕获）与成员复合主键去重，重复事件零副作用；
 * dm 防裂靠 dedup_key 唯一约束（先查后插+并发兜底，命中即返回既有会话+existing=true，
 * 红线 5：幂等返回既有禁二次插入）；归档靠 archived_at 判空（已归档跳过）。</p>
 *
 * <p><b>延迟归档</b>：Valkey ZSET sched:topic-archive（score=到期 epoch 毫秒，
 * member=conversationId）；ZADD 进缓冲期（默认 24h），缓冲期内对象恢复非关闭态
 * → cancelArchive ZREM 免费撤销（红线 9）；到期由 {@link ArchiveSweeper} 落库归档。
 * Valkey 不可用 → 调度侧异常沿事件消费 PEL 重投，扫描器侧降级 WARN（红线 5，不崩）。</p>
 *
 * <p><b>成员变更缓存失效</b>：addMembers/removeMember 同事务 outbox(conversation.members_changed)，
 * app 层 {@code MembersChangedHandler} 消费后清理 WS sub 鉴权缓存并强制重校验本地订阅表
 * （05 §4.4：成员变更事件触发节点本地订阅表清理）。</p>
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 延迟归档 ZSET（06 §3 W3：sched:topic-archive；score=epochMillis, member=convId） */
    public static final String ARCHIVE_ZSET = "sched:topic-archive";
    /** 未读缓存 Hash 前缀（INC-2 T-3：unread:{uid}，field=convId, value=unread） */
    public static final String UNREAD_KEY_PREFIX = "unread:";
    /** 成员变更事件类型（05 §4.4 节点订阅表清理触发器；§5.1 目录） */
    public static final String EVENT_MEMBERS_CHANGED = "conversation.members_changed";

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final UserConversationCursorRepository cursors;
    private final AppUserRepository users;
    private final cn.teamone.platform.infra.OutboxWriter outbox;
    private final StringRedisTemplate redis;

    public ConversationService(ConversationRepository conversations,
                               ConversationMemberRepository members,
                               MessageRepository messages,
                               UserConversationCursorRepository cursors,
                               AppUserRepository users,
                               cn.teamone.platform.infra.OutboxWriter outbox,
                               @Qualifier("stringRedisTemplate") StringRedisTemplate redis) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.cursors = cursors;
        this.users = users;
        this.outbox = outbox;
        this.redis = redis;
    }

    // ==================== 自动建题 ====================

    /**
     * 自动建题（workitem.created 致命缺陷 / requirement.submitted 消费入口）：
     * 不存在则建 conversation(type=topic, auto_created=true) + 成员=stakeholderUserIds
     * + 系统消息（kind=system，同步更新 last_message_*）；已存在则只补拉缺失成员（无新系统消息）。
     * 全程幂等：重复事件无副作用。
     *
     * @param systemSenderId 系统消息 sender（payload 里 actorId/reporterId 皆可，
     *                       选系统感更强者——调用方传 reporterId 优先；须为真实用户，FK 约束）
     * @return 会话 id（新建或已存在）
     */
    @Transactional
    public UUID autoTopic(String targetType, UUID targetId, String title,
                          List<UUID> stakeholderUserIds, String systemText, UUID systemSenderId) {
        Optional<Conversation> existing = conversations.findByTargetTypeAndTargetId(targetType, targetId);
        if (existing.isPresent()) {
            addMissingMembers(existing.get().getId(), stakeholderUserIds);
            return existing.get().getId();
        }

        Conversation conv = new Conversation();
        conv.setType(Conversation.TYPE_TOPIC);
        conv.setName(title);
        conv.setTargetType(targetType);
        conv.setTargetId(targetId);
        conv.setAutoCreated(true);
        try {
            conversations.saveAndFlush(conv);
        } catch (DataIntegrityViolationException ex) {
            // 并发窗口兜底：另一实例/重投先建了话题（唯一索引 uq_conversation_topic 胜出）
            // → 收敛为补拉成员（幂等，红线 1）
            log.info("[collab] topic created concurrently, fallback to backfill: {}#{}", targetType, targetId);
            Conversation winner = conversations.findByTargetTypeAndTargetId(targetType, targetId)
                    .orElseThrow(() -> ex);
            addMissingMembers(winner.getId(), stakeholderUserIds);
            return winner.getId();
        }

        addMissingMembers(conv.getId(), stakeholderUserIds);
        appendSystemMessage(conv, systemSenderId, systemText);
        log.info("[collab] topic auto-created type={} target={}/{} members={} by={}",
                conv.getId(), targetType, targetId,
                stakeholderUserIds == null ? 0 : stakeholderUserIds.size(), systemSenderId);
        return conv.getId();
    }

    /** 已有会话补拉缺失成员（LinkedHashSet 去重保序；主键冲突幂等） */
    private void addMissingMembers(UUID conversationId, List<UUID> stakeholderUserIds) {
        if (stakeholderUserIds == null || stakeholderUserIds.isEmpty()) {
            return;
        }
        Set<UUID> current = new LinkedHashSet<>(members.findUserIdsByConversationId(conversationId));
        for (UUID userId : new LinkedHashSet<>(stakeholderUserIds)) {
            if (userId != null && !current.contains(userId)) {
                members.save(new ConversationMember(conversationId, userId, ConversationMember.ROLE_MEMBER));
            }
        }
    }

    /** 系统消息直插（不入 MessageService 成员/归档校验——建题时 sender 必为干系人且会话必新） */
    private void appendSystemMessage(Conversation conv, UUID senderId, String systemText) {
        Message msg = new Message();
        msg.setConversationId(conv.getId());
        msg.setSenderId(senderId);
        msg.setKind(Message.KIND_SYSTEM);
        msg.setBody(systemText);
        messages.saveAndFlush(msg);
        conv.setLastMessageId(msg.getId());
        conv.setLastMessageAt(msg.getCreatedAt());
        conversations.save(conv);
    }

    // ==================== dm / group 写端点（INC-2 T-2） ====================

    /**
     * 建私聊（POST /conversations {type:'dm', peerId}）。
     *
     * <p><b>dm 防裂唯一路径（红线 5）</b>：dedup_key='dm:{字典序小id}:{大id}'，
     * 先查后插；唯一约束冲突（并发窗口）收敛为返回既有会话 + existing=true，
     * 禁二次插入。成员恒为创建者+peer 两人（禁三人 dm）；dm 无 owner 语义（双方皆 member）。</p>
     *
     * @return 会话视图 + existing（命中既有=true）
     */
    @Transactional
    public Map<String, Object> createDm(UUID creatorId, UUID peerId) {
        if (peerId == null) {
            throw new BusinessException(ErrorCode.PLT_4000, "dm 需要 peerId");
        }
        if (peerId.equals(creatorId)) {
            throw new BusinessException(ErrorCode.PLT_4000, "不能与自己也建私聊");
        }
        requireUser(peerId, "peer 用户不存在");

        String low = creatorId.toString().compareTo(peerId.toString()) < 0
                ? creatorId.toString() : peerId.toString();
        String high = low.equals(creatorId.toString()) ? peerId.toString() : creatorId.toString();
        String dedupKey = "dm:" + low + ":" + high;

        Optional<Conversation> existing = conversations.findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            log.info("[collab] dm dedup hit: conv={} creator={} peer={}",
                    existing.get().getId(), creatorId, peerId);
            Map<String, Object> res = viewOf(existing.get(), true);
            res.put("peerUserId", peerId.toString());
            users.findById(peerId).ifPresent(pu -> {
                String displayName = pu.getDisplayName();
                if (displayName == null || displayName.isBlank()) displayName = pu.getUsername();
                res.put("name", displayName);
            });
            return res;
        }

        Conversation conv = new Conversation();
        conv.setType(Conversation.TYPE_DM);
        conv.setDedupKey(dedupKey);
        try {
            conversations.saveAndFlush(conv);
        } catch (DataIntegrityViolationException ex) {
            // 并发兜底：另一请求先建了同 pair dm（dedup_key 唯一约束胜出）→ 返回既有（红线 5）
            Conversation winner = conversations.findByDedupKey(dedupKey).orElseThrow(() -> ex);
            log.info("[collab] dm dedup hit (constraint): conv={}", winner.getId());
            Map<String, Object> res = viewOf(winner, true);
            res.put("peerUserId", peerId.toString());
            users.findById(peerId).ifPresent(pu -> {
                String displayName = pu.getDisplayName();
                if (displayName == null || displayName.isBlank()) displayName = pu.getUsername();
                res.put("name", displayName);
            });
            return res;
        }
        members.save(new ConversationMember(conv.getId(), creatorId, ConversationMember.ROLE_MEMBER));
        members.save(new ConversationMember(conv.getId(), peerId, ConversationMember.ROLE_MEMBER));
        log.info("[collab] dm created: conv={} creator={} peer={}", conv.getId(), creatorId, peerId);
        Map<String, Object> res = viewOf(conv, false);
        res.put("peerUserId", peerId.toString());
        users.findById(peerId).ifPresent(pu -> {
            String displayName = pu.getDisplayName();
            if (displayName == null || displayName.isBlank()) displayName = pu.getUsername();
            res.put("name", displayName);
        });
        return res;
    }

    /**
     * 建群（POST /conversations {type:'group', name, memberIds[]}）。
     * dedup_key=null（群允许同名并存）；creator=owner（role='owner'）；
     * memberIds 去重、剔除 creator；name 必填（PLT_4000）。
     */
    @Transactional
    public Map<String, Object> createGroup(UUID creatorId, String name, List<UUID> memberIds) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "群名称必填");
        }
        Conversation conv = new Conversation();
        conv.setType(Conversation.TYPE_GROUP);
        conv.setName(name.trim());
        conversations.saveAndFlush(conv);

        members.save(new ConversationMember(conv.getId(), creatorId, ConversationMember.ROLE_OWNER));
        int added = 0;
        for (UUID userId : new LinkedHashSet<>(memberIds == null ? List.<UUID>of() : memberIds)) {
            if (userId == null || userId.equals(creatorId)) {
                continue; // 去重 + 剔除 creator（creator 已是 owner）
            }
            requireUser(userId, "群成员不存在: " + userId);
            members.save(new ConversationMember(conv.getId(), userId, ConversationMember.ROLE_MEMBER));
            added++;
        }
        log.info("[collab] group created: conv={} owner={} members={}", conv.getId(), creatorId, added);
        return viewOf(conv, false);
    }

    /**
     * 加人（POST /{id}/members {userIds[]}）：仅 group 且 owner（dm/topic PLT_4000，
     * 非 owner PLT_4030，非成员 COL_4210）。返回本次实际新增的成员 id 清单。
     * 成功后同事务 outbox(conversation.members_changed) → WS 节点清 sub 鉴权缓存。
     */
    @Transactional
    public List<UUID> addMembers(UUID operatorId, UUID conversationId, List<UUID> userIds) {
        Conversation conv = requireGroup(conversationId);
        requireRole(operatorId, conversationId, ConversationMember.ROLE_OWNER);
        if (userIds == null || userIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "userIds 必填");
        }

        Set<UUID> current = new LinkedHashSet<>(members.findUserIdsByConversationId(conversationId));
        List<UUID> added = new ArrayList<>();
        for (UUID userId : new LinkedHashSet<>(userIds)) {
            if (userId == null || current.contains(userId)) {
                continue; // 主键天然幂等：已在群内跳过
            }
            requireUser(userId, "被加用户不存在: " + userId);
            members.save(new ConversationMember(conversationId, userId, ConversationMember.ROLE_MEMBER));
            added.add(userId);
        }
        if (!added.isEmpty()) {
            publishMembersChanged(conv, operatorId);
        }
        log.info("[collab] members added: conv={} by={} added={}", conversationId, operatorId, added.size());
        return added;
    }

    /**
     * 退群/移除（DELETE /{id}/members/{uid}）：仅 group（本人退群或 owner 移除；
     * dm/topic PLT_4000）。简化规则：owner 不可退群（群主转移 M3，PLT_4000）。
     * 成功后同事务 outbox(conversation.members_changed) → WS 节点清 sub 鉴权缓存+重校验订阅表。
     */
    @Transactional
    public void removeMember(UUID operatorId, UUID conversationId, UUID targetUserId) {
        Conversation conv = requireGroup(conversationId);
        ConversationMember target = members.findById(new ConversationMember.Pk(conversationId, targetUserId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "目标不是会话成员: " + targetUserId));

        if (ConversationMember.ROLE_OWNER.equals(target.getRole())) {
            // 简化：owner 退群=群无主（转最早成员规则挂 M3），一律拒绝
            throw new BusinessException(ErrorCode.PLT_4000, "群主不可退群/被移除（转让规则后补）");
        }
        if (!operatorId.equals(targetUserId)) {
            requireRole(operatorId, conversationId, ConversationMember.ROLE_OWNER); // 非 owner 移除他人 → PLT_4030
        }

        members.deleteById(new ConversationMember.Pk(conversationId, targetUserId));
        publishMembersChanged(conv, operatorId);
        log.info("[collab] member removed: conv={} target={} by={}", conversationId, targetUserId, operatorId);
    }

    // ==================== 未读游标（INC-2 T-3 / R-10 帧修正） ====================

    /** read 推进结果：生效游标（GREATEST 后）+ 未读数（WS ack / HTTP 响应共用） */
    public record ReadResult(long lastReadMessageId, long unread) {}

    /**
     * read 帧落点（WS handleRead 与 HTTP POST /{id}/read 同走本方法）：游标推进（只前进不回退）+ 未读重算。
     *
     * <p>校验：成员（COL_4210）+ 显式 messageId ≤ 会话 last_message_id（越界 PLT_4000，红线 2）；
     * upsert 幂等（ON CONFLICT GREATEST，重复帧/乱序帧零副作用）；未读真相=SQL 差值，
     * Valkey unread:{uid} Hash 仅缓存加速——失败 WARN 降级不回滚（红线 2/5）。</p>
     *
     * <p><b>R-10b：999999999 哨兵废除</b>——lastReadMessageId 传 null（前端省略字段）语义
     * =「读到最新」，取 conversation.last_message_id 为目标游标；空会话（无消息）取 0。</p>
     *
     * @param lastReadMessageId 目标游标；null=读到最新
     * @return 生效游标 + 推进后该会话的未读数
     */
    @Transactional
    public ReadResult advanceRead(UUID userId, UUID conversationId, Long lastReadMessageId) {
        Conversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "会话不存在: " + conversationId));
        if (!members.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new BusinessException(ErrorCode.COL_4210,
                    "非会话成员", List.of("conversationId=" + conversationId));
        }
        Long lastMessageId = conv.getLastMessageId();
        long target;
        if (lastReadMessageId == null) {
            target = lastMessageId == null ? 0L : lastMessageId; // 缺省=读到最新
        } else {
            if (lastReadMessageId < 0) {
                throw new BusinessException(ErrorCode.PLT_4000, "lastReadMessageId 需 ≥ 0");
            }
            if (lastMessageId != null && lastReadMessageId > lastMessageId) {
                // 越界拒绝（红线 2）：不落游标，防伪造大游标把未读打穿
                throw new BusinessException(ErrorCode.PLT_4000,
                        "read 越界: lastReadMessageId > 会话 last_message_id",
                        List.of("lastReadMessageId=" + lastReadMessageId,
                                "lastMessageId=" + lastMessageId));
            }
            target = lastReadMessageId;
        }
        cursors.upsertAdvance(userId, conversationId, target);
        Long effective = cursors.effectiveLastRead(userId, conversationId);
        long unread = lastMessageId == null || effective == null
                ? 0 : Math.max(0, lastMessageId - effective);

        try {
            redis.opsForHash().put(UNREAD_KEY_PREFIX + userId, conversationId.toString(),
                    Long.toString(unread));
        } catch (RuntimeException ex) {
            log.warn("[collab] unread cache write failed (degraded, truth=SQL): conv={} {}",
                    conversationId, ex.toString());
        }
        long effectiveCursor = effective == null ? target : effective;
        log.debug("[collab] cursor advanced: user={} conv={} lastRead={} unread={}",
                userId, conversationId, effectiveCursor, unread);
        return new ReadResult(effectiveCursor, unread);
    }

    /** 兼容旧签名（显式游标；既有单测沿用）：返回推进后未读数 */
    @Transactional
    public long advanceReadCursor(UUID userId, UUID conversationId, long lastReadMessageId) {
        return advanceRead(userId, conversationId, lastReadMessageId).unread();
    }

    // ==================== 内部 ====================

    /** group 限定（dm/topic 成员管理一律 PLT_4000） */
    private Conversation requireGroup(UUID conversationId) {
        Conversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "会话不存在: " + conversationId));
        if (!Conversation.TYPE_GROUP.equals(conv.getType())) {
            throw new BusinessException(ErrorCode.PLT_4000,
                    "仅群会话支持成员管理（type=" + conv.getType() + "）");
        }
        return conv;
    }

    /** 成员行 + 角色断言（非成员 COL_4210；角色不符 PLT_4030） */
    private void requireRole(UUID userId, UUID conversationId, String expectedRole) {
        ConversationMember me = members.findById(new ConversationMember.Pk(conversationId, userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.COL_4210,
                        "非会话成员", List.of("conversationId=" + conversationId)));
        if (!expectedRole.equals(me.getRole())) {
            throw new BusinessException(ErrorCode.PLT_4030,
                    "需要群主权限", List.of("role=" + me.getRole()));
        }
    }

    private void requireUser(UUID userId, String message) {
        if (!users.existsById(userId)) {
            throw new BusinessException(ErrorCode.PLT_4040, message);
        }
    }

    /** 成员变更事件（同事务 outbox；app 层 Handler 消费后清 WS 鉴权缓存+重校验订阅表） */
    private void publishMembersChanged(Conversation conv, UUID operatorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conv.getId());
        payload.put("type", conv.getType());
        payload.put("operatorId", operatorId);
        outbox.append("conversation", conv.getId(), EVENT_MEMBERS_CHANGED, payload, operatorId);
    }

    /** 会话创建视图（含 existing 标记，dm 幂等返回的契约字段） */
    private static Map<String, Object> viewOf(Conversation c, boolean existing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("type", c.getType());
        m.put("name", c.getName());
        m.put("autoCreated", c.isAutoCreated());
        m.put("archived", c.getArchivedAt() != null);
        m.put("createdAt", c.getCreatedAt());
        m.put("existing", existing);
        return m;
    }


    /**
     * 调度延迟归档：ZADD sched:topic-archive {now+delay} {convId|reason}。
     * 已归档的会话直接跳过（幂等）；重复 ZADD 同 member 仅刷新 score（天然幂等）。
     * member 携带归档原因（'|' 分隔，UUID 不含该字符；无原因段=旧口径 target_closed）。
     */
    public void scheduleArchive(UUID conversationId, int delayMinutes) {
        scheduleArchive(conversationId, delayMinutes, null);
    }

    /** 带原因重载（M2-INC-1 W2：sprint_ended 口径，05 §5.1 sprint.completed 行） */
    public void scheduleArchive(UUID conversationId, int delayMinutes, String archivedReason) {
        Conversation conv = conversations.findById(conversationId).orElse(null);
        if (conv == null || conv.getArchivedAt() != null) {
            return; // 已归档/不存在：跳过（幂等）
        }
        double dueAt = (double) (System.currentTimeMillis() + Duration.ofMinutes(delayMinutes).toMillis());
        String member = archivedReason == null || archivedReason.isBlank()
                ? conversationId.toString()
                : conversationId + "|" + archivedReason;
        redis.opsForZSet().add(ARCHIVE_ZSET, member, dueAt);
        log.info("[collab-archive] scheduled conv={} due={}M reason={}",
                conversationId, delayMinutes, archivedReason == null ? "target_closed" : archivedReason);
    }

    /** 撤销延迟归档（红线 9：缓冲期内 target 恢复非关闭态）；无键时 ZREM 为 no-op */
    public void cancelArchive(UUID conversationId) {
        redis.opsForZSet().remove(ARCHIVE_ZSET, conversationId.toString());
    }

    /**
     * 扫描器到期落库：archived_at=now, archived_reason=archivedReason。
     * 幂等：不存在或已归档跳过。供 {@link ArchiveSweeper} 调用。
     */
    @Transactional
    public boolean markArchived(UUID conversationId, String archivedReason) {
        Conversation conv = conversations.findById(conversationId).orElse(null);
        if (conv == null || conv.getArchivedAt() != null) {
            return false; // 已归档/不存在：幂等跳过
        }
        conv.setArchivedAt(Instant.now());
        conv.setArchivedReason(archivedReason);
        conversations.save(conv);
        return true;
    }

    // ==================== 查询辅助（WS/Handler 用） ====================

    @Transactional(readOnly = true)
    public Conversation requireTopic(String targetType, UUID targetId) {
        return conversations.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "话题不存在: " + targetType + "#" + targetId));
    }

    /** ZSET 到期成员（score∈[0, now]），扫描器消费；Valkey 不可用由调用方降级 */
    public Set<String> dueArchiveCandidates(double nowMillis) {
        return redis.opsForZSet().rangeByScore(ARCHIVE_ZSET, 0, nowMillis);
    }

    public void removeArchiveCandidate(String member) {
        redis.opsForZSet().remove(ARCHIVE_ZSET, member);
    }

    public Optional<Conversation> findById(UUID conversationId) {
        return conversations.findById(conversationId);
    }

    /** DataAccessException 透传标记（sweeper/Handler 侧按需降级） */
    public boolean isValkeyDown(RuntimeException ex) {
        while (ex != null) {
            if (ex instanceof DataAccessException) return true;
            ex = (RuntimeException) ex.getCause();
        }
        return false;
    }
}
