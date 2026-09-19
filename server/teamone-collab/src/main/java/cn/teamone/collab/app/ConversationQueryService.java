package cn.teamone.collab.app;

import cn.teamone.collab.domain.Conversation;
import cn.teamone.collab.domain.ConversationMember;
import cn.teamone.collab.domain.Message;
import cn.teamone.collab.domain.UserConversationCursor;
import cn.teamone.collab.dto.MessageReadersDto;
import cn.teamone.collab.dto.MessageReadersDto.MemberBrief;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.collab.repo.MessageRepository;
import cn.teamone.collab.repo.UserConversationCursorRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 会话读侧应用服务（05 §3.3 W3-④补齐）：会话清单 / 历史消息 / 会话详情。
 *
 * <p>薄读原则：取数走 repo（findByUserId→findAllById、messages 游标分页），
 * 过滤/排序/截断在服务层纯 Java 完成（M1 数据量级；可纯逻辑单测）。
 * 归档会话<b>可读不可写</b>——读端点不做归档拒绝（COL_4203 仅写侧，05 §3.2）。</p>
 */
@Service
public class ConversationQueryService {

    /** 历史消息单页上限（05 §3.3 limit 上限 200） */
    static final int MAX_MESSAGE_LIMIT = 200;
    static final int DEFAULT_MESSAGE_LIMIT = 50;

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    /** 已读游标仓库（Direction 4 Phase 4：已读回执明细查询） */
    private final UserConversationCursorRepository cursors;
    /** 用户资料仓库（已读回执需要展示 username/displayName） */
    private final AppUserRepository users;

    public ConversationQueryService(ConversationRepository conversations,
                                    ConversationMemberRepository members,
                                    MessageRepository messages,
                                    UserConversationCursorRepository cursors,
                                    AppUserRepository users) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.cursors = cursors;
        this.users = users;
    }

    // ==================== 会话清单 ====================

    /**
     * 本人参与的会话清单（INC-2 T-2/T-3：会话行 + 未读数一次 SQL）。
     *
     * <p>取数走 {@code findMineWithUnread}（JOIN 成员行 + LEFT JOIN 当前用户游标，
     * unread = last_message_id - COALESCE(cursor,0) 差值即真相）；type/archived 过滤、
     * 排序仍在服务层纯 Java（与既有口径一致）。</p>
     *
     * @param type     会话类型过滤（dm/group/topic/channel；非法值 PLT_4000；null=全部）
     * @param archived true=仅归档、false=仅未归档（archived_at IS NULL 语义）、null=全部
     * @return 按 lastMessageAt DESC（无消息会话排尾部，createdAt DESC 兜底）
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMine(UUID userId, String type, Boolean archived) {
        if (type != null && !type.isBlank()
                && !List.of(Conversation.TYPE_DM, Conversation.TYPE_GROUP,
                            Conversation.TYPE_TOPIC, Conversation.TYPE_CHANNEL).contains(type)) {
            throw new BusinessException(ErrorCode.PLT_4000, "type 需为 dm|group|topic|channel: " + type);
        }

        Map<UUID, Long> memberCounts = new HashMap<>();
        List<UUID> ids = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : conversations.findMineWithUnread(userId)) {
            UUID convId = (UUID) row[0];
            ids.add(convId);
            rows.add(rowView(row));
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        for (Object[] row : members.countByConversationIdIn(ids)) {
            memberCounts.put((UUID) row[0], (Long) row[1]);
        }

        // 查找 DM 私聊对端成员并回填真实姓名与头像，消除前端“私聊会话”模糊感
        List<UUID> dmConvIds = rows.stream()
                .filter(m -> Conversation.TYPE_DM.equals(m.get("type")))
                .map(m -> (UUID) m.get("id"))
                .toList();
        Map<UUID, UUID> dmPeerMap = new HashMap<>();
        if (!dmConvIds.isEmpty()) {
            for (ConversationMember cm : members.findByConversationIdIn(dmConvIds)) {
                if (!cm.getUserId().equals(userId)) {
                    dmPeerMap.put(cm.getConversationId(), cm.getUserId());
                }
            }
        }
        Map<UUID, AppUser> peerUsers = new HashMap<>();
        if (!dmPeerMap.isEmpty()) {
            for (AppUser u : users.findAllById(dmPeerMap.values())) {
                peerUsers.put(u.getId(), u);
            }
        }

        Predicate<Map<String, Object>> filter = m ->
                (type == null || type.isBlank() || type.equals(m.get("type")))
                && (archived == null
                    || (archived ? Boolean.TRUE.equals(m.get("archived"))
                                 : !Boolean.TRUE.equals(m.get("archived"))));
        return rows.stream()
                .filter(filter)
                .peek(m -> {
                    m.put("memberCount", memberCounts.getOrDefault((UUID) m.get("id"), 0L));
                    if (Conversation.TYPE_DM.equals(m.get("type"))) {
                        UUID peerId = dmPeerMap.get((UUID) m.get("id"));
                        if (peerId != null) {
                            m.put("peerUserId", peerId.toString());
                            AppUser pu = peerUsers.get(peerId);
                            if (pu != null) {
                                String displayName = pu.getDisplayName();
                                if (displayName == null || displayName.isBlank()) displayName = pu.getUsername();
                                m.put("name", displayName);
                            }
                        }
                    }
                })
                .sorted(Comparator
                        .comparing((Map<String, Object> m) -> (Instant) m.get("lastMessageAt"),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(m -> (Instant) m.get("createdAt"),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * 侧栏 IM 总未读聚合（R-10d）：sumUnread 单 SQL（findMineWithUnread 同口径 SUM 一层），
     * 与 GET /conversations 各行 unreadCount 求和恒等；真相=SQL，不走 Valkey 缓存
     * （unread:{uid} Hash 仅游标推进时增量写、可能残缺，聚合端回源查询更稳）。
     */
    @Transactional(readOnly = true)
    public long unreadSummary(UUID userId) {
        return conversations.sumUnread(userId);
    }

    /**
     * findMineWithUnread 行投影 → 会话视图（列序与仓库 SQL 对齐）。
     * 时间列归一化为 Instant（原生查询返回 OffsetDateTime，与实体视图 JSON 口径一致）。
     * unread 恒 ≥0（游标只前进不回退，正常无负值；防数据漂移取 max(0,n)）。
     */
    private static Map<String, Object> rowView(Object[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row[0]);
        m.put("type", row[1]);
        m.put("name", row[2]);
        m.put("targetType", row[3]);
        m.put("targetId", row[4]);
        m.put("autoCreated", Boolean.TRUE.equals(row[5]));
        m.put("archived", row[6] != null);
        m.put("archivedAt", toInstant(row[6]));
        m.put("archivedReason", row[7]);
        m.put("lastMessageId", row[8]);
        m.put("lastMessageAt", toInstant(row[9]));
        long unread = row[10] instanceof Number n ? n.longValue() : 0L;
        m.put("unreadCount", Math.max(0, unread));
        m.put("createdAt", toInstant(row[11]));
        return m;
    }

    /** 原生查询时间列 → Instant（PG 驱动返回 OffsetDateTime / 旧驱动 Timestamp） */
    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        throw new IllegalStateException("unexpected temporal column type: " + v.getClass());
    }

    // ==================== 历史消息 ====================

    /**
     * 历史消息拉取（WS 离线补偿同一接口，05 §3.3）：成员校验（COL_4210）。
     * 两个互斥游标（M2-INC-1 W1 清账：before 反向游标，ImPage「加载更多历史」前插）：
     * <ul>
     *   <li>{@code after=}：id &gt; after，ASC 升序返回（WS 离线补偿语义，不变）；</li>
     *   <li>{@code before=}：id &lt; before，DESC 降序返回（含 before="latest"=尾窗最新一页）；
     *       hasMore = 还有更早消息。</li>
     * </ul>
     * 取 limit+1 判 hasMore 后截断。归档会话可读。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> messages(UUID userId, UUID conversationId, Long after, Long before, int limit) {
        requireMember(userId, conversationId);
        if (after != null && before != null) {
            throw new BusinessException(ErrorCode.PLT_4000, "after 与 before 互斥，只能传其一");
        }
        int safeLimit = clampLimit(limit);
        if (before != null) {
            Pageable page = PageRequest.of(0, safeLimit + 1); // 多取一条判 hasMore
            List<Message> fetched = messages
                    .findByConversationIdAndIdLessThanOrderByIdDesc(conversationId, before, page)
                    .getContent();
            boolean hasMore = fetched.size() > safeLimit;
            List<Map<String, Object>> items = fetched.stream()
                    .limit(safeLimit)
                    .map(Message::toPayload)
                    .toList(); // DESC：最新在前（前端页内反转后前插）
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("items", items);
            res.put("hasMore", hasMore);
            return res;
        }
        long cursor = (after == null || after < 0) ? 0L : after;
        Pageable page = PageRequest.of(0, safeLimit + 1); // 多取一条判 hasMore
        List<Message> fetched = messages
                .findByConversationIdAndIdGreaterThanOrderByIdAsc(conversationId, cursor, page)
                .getContent();
        boolean hasMore = fetched.size() > safeLimit;
        List<Map<String, Object>> items = fetched.stream()
                .limit(safeLimit)
                .map(Message::toPayload)
                .toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", items);
        res.put("hasMore", hasMore);
        return res;
    }

    // ==================== 会话详情 ====================

    /** 会话详情（ImPage 右栏干系人）：含成员用户 id 清单；非成员 COL_4210。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID userId, UUID conversationId) {
        Conversation conv = requireMember(userId, conversationId);
        Map<String, Object> view = viewOf(conv, null);
        List<UUID> memberIds = members.findUserIdsByConversationId(conversationId);
        view.put("members", memberIds);
        // 若为私聊且未指定会话名，以对端成员姓名和头像作为显示名称
        if (Conversation.TYPE_DM.equals(conv.getType())) {
            UUID peerId = memberIds.stream().filter(id -> !id.equals(userId)).findFirst().orElse(null);
            if (peerId != null) {
                view.put("peerUserId", peerId.toString());
                users.findById(peerId).ifPresent(pu -> {
                    String displayName = pu.getDisplayName();
                    if (displayName == null || displayName.isBlank()) displayName = pu.getUsername();
                    view.put("name", displayName);
                });
            }
        }
        return view;
    }

    // ==================== 已读回执明细（Direction 4 Phase 4） ====================

    /**
     * 查询单条消息的已读/未读成员明细（群聊/话题读回执下钻）。
     *
     * <p>计算规则：
     * <ol>
     *   <li>成员校验：请求者必须是该会话成员（复用 {@link #requireMember}），非成员 COL_4210 拒绝；</li>
     *   <li>消息校验：目标消息必须存在于该会话中，否则 PLT_4040；</li>
     *   <li>全量成员取数：{@code findByConversationId} 取会话全部成员 userId 集合；</li>
     *   <li>全量游标取数：{@code cursors.findByConversationId} 取该会话所有推进过的游标记录；</li>
     *   <li>已读判定：{@code cursor.lastReadMessageId >= messageId} 且非消息发送者自身；</li>
     *   <li>未读判定：游标不存在（从未打开会话）或 {@code lastReadMessageId < messageId}；</li>
     *   <li>用户资料：批量 {@code findAllById} 拉取 displayName/username（M1 人数 ≤ 100）。</li>
     * </ol>
     * </p>
     *
     * @param userId         当前请求者 ID（鉴权+成员校验用）
     * @param conversationId 会话 ID
     * @param messageId      目标消息 ID
     * @return 已读回执 DTO（含发送者、已读列表、未读列表与统计数字）
     */
    @Transactional(readOnly = true)
    public MessageReadersDto getMessageReaders(UUID userId, UUID conversationId, long messageId) {
        // ① 成员校验（非成员 COL_4210，会话不存在 PLT_4040）
        requireMember(userId, conversationId);

        // ② 查找目标消息，确认消息所属会话（防止跨会话越权查询）
        Message msg = messages.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "消息不存在: " + messageId));
        if (!conversationId.equals(msg.getConversationId())) {
            throw new BusinessException(ErrorCode.PLT_4040,
                    "消息不属于该会话: msgId=" + messageId + " conv=" + conversationId);
        }
        UUID senderId = msg.getSenderId();

        // ③ 取会话全量成员 userId 集合
        List<ConversationMember> memberRows = members.findByConversationId(conversationId);
        List<UUID> allMemberIds = memberRows.stream().map(ConversationMember::getUserId).toList();

        // ④ 取该会话全部游标记录（仅推进过游标的成员才有行）
        Map<UUID, UserConversationCursor> cursorMap = new HashMap<>();
        for (UserConversationCursor c : cursors.findByConversationId(conversationId)) {
            cursorMap.put(c.getUserId(), c);
        }

        // ⑤ 批量拉取用户资料（M1 人数 ≤ 100，单次查询无压力）
        Map<UUID, AppUser> userMap = new HashMap<>();
        for (AppUser u : users.findAllById(allMemberIds)) {
            userMap.put(u.getId(), u);
        }

        // ⑥ 按 lastReadMessageId 分桶：sender / reader / unreader
        MemberBrief senderBrief = toBrief(senderId, userMap, null);
        List<MemberBrief> readerList = new ArrayList<>();
        List<MemberBrief> unreaderList = new ArrayList<>();

        for (UUID memberId : allMemberIds) {
            // 发送者自身不计入接收方统计
            if (memberId.equals(senderId)) continue;

            UserConversationCursor cursor = cursorMap.get(memberId);
            if (cursor != null && cursor.getLastReadMessageId() >= messageId) {
                // 已读：游标 >= 目标消息 ID
                readerList.add(toBrief(memberId, userMap, cursor.getReadAt()));
            } else {
                // 未读：无游标记录 或 游标 < 目标消息 ID
                unreaderList.add(toBrief(memberId, userMap, null));
            }
        }

        // ⑦ 已读列表按 readAt 升序，未读列表按 displayName 字典序
        readerList.sort(Comparator.comparing(MemberBrief::readAt, Comparator.nullsLast(Comparator.naturalOrder())));
        unreaderList.sort(Comparator.comparing(MemberBrief::displayName, Comparator.nullsLast(Comparator.naturalOrder())));

        int totalMembers = allMemberIds.size();
        int totalRecipients = totalMembers - 1; // 排除发送者
        int readCount = readerList.size();
        int unreadCount = unreaderList.size();
        boolean allRead = unreadCount == 0 && totalRecipients > 0;

        return new MessageReadersDto(
                conversationId, messageId, senderId,
                totalMembers, totalRecipients, readCount, unreadCount, allRead,
                senderBrief, List.copyOf(readerList), List.copyOf(unreaderList));
    }

    /**
     * 将用户 ID 转换为 {@link MemberBrief}（已读回执展示用）。
     *
     * @param uid     用户 ID
     * @param userMap 批量查询结果缓存（避免 N+1）
     * @param readAt  已读时间戳（未读成员传 null）
     * @return 成员简报
     */
    private static MemberBrief toBrief(UUID uid, Map<UUID, AppUser> userMap, Instant readAt) {
        AppUser u = userMap.get(uid);
        String username = u != null ? u.getUsername() : uid.toString();
        String displayName = u != null ? u.getDisplayName() : uid.toString();
        return new MemberBrief(uid, username, displayName, readAt);
    }

    // ==================== 内部 ====================

    /** 成员校验 + 会话存在性（4040 先于 4210；成员判定与 WS sub/msg 同源真相查询） */
    private Conversation requireMember(UUID userId, UUID conversationId) {
        Conversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "会话不存在: " + conversationId));
        if (!members.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new BusinessException(ErrorCode.COL_4210,
                    "非会话成员", List.of("conversationId=" + conversationId));
        }
        return conv;
    }

    static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_MESSAGE_LIMIT);
    }

    private static Map<String, Object> viewOf(Conversation c, Long memberCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("type", c.getType());
        m.put("name", c.getName());
        m.put("targetType", c.getTargetType());
        m.put("targetId", c.getTargetId());
        m.put("autoCreated", c.isAutoCreated());
        m.put("archived", c.getArchivedAt() != null);
        m.put("archivedAt", c.getArchivedAt());
        m.put("archivedReason", c.getArchivedReason());
        m.put("lastMessageId", c.getLastMessageId());
        m.put("lastMessageAt", c.getLastMessageAt());
        if (memberCount != null) {
            m.put("memberCount", memberCount);
        }
        return m;
    }
}
