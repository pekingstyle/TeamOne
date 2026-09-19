package cn.teamone.collab.app;

import cn.teamone.collab.domain.Conversation;
import cn.teamone.collab.domain.ConversationMember;
import cn.teamone.collab.domain.Message;
import cn.teamone.collab.domain.UserConversationCursor;
import cn.teamone.collab.dto.MessageReadersDto;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.collab.repo.MessageRepository;
import cn.teamone.collab.repo.UserConversationCursorRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 消息已读回执逻辑单测（Direction 4 Phase 4）。
 *
 * <p>覆盖已读回执的核心分支：
 * <ul>
 *   <li>全员未读（无游标记录）；</li>
 *   <li>部分已读（部分成员游标 ≥ msgId）；</li>
 *   <li>全员已读（所有接收方游标 ≥ msgId）；</li>
 *   <li>非成员查询拒绝（COL_4210）；</li>
 *   <li>消息不存在或不属于该会话（PLT_4040）。</li>
 * </ul>
 * </p>
 */
class ConversationReadReceiptTest {

    /** 会话 ID（测试用固定值） */
    private static final UUID CONV_ID = UUID.randomUUID();
    /** 消息发送者用户 ID */
    private static final UUID SENDER = UUID.randomUUID();
    /** 接收方 A 用户 ID */
    private static final UUID MEMBER_A = UUID.randomUUID();
    /** 接收方 B 用户 ID */
    private static final UUID MEMBER_B = UUID.randomUUID();
    /** 接收方 C 用户 ID */
    private static final UUID MEMBER_C = UUID.randomUUID();
    /** 目标消息 ID */
    private static final long MSG_ID = 100L;

    private ConversationRepository conversations;
    private ConversationMemberRepository members;
    private MessageRepository messages;
    private UserConversationCursorRepository cursors;
    private AppUserRepository users;
    private ConversationQueryService service;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        members = mock(ConversationMemberRepository.class);
        messages = mock(MessageRepository.class);
        cursors = mock(UserConversationCursorRepository.class);
        users = mock(AppUserRepository.class);
        service = new ConversationQueryService(conversations, members, messages, cursors, users);
    }

    // ==================== 辅助方法 ====================

    /** 创建一个标准群聊会话 */
    private Conversation groupConv() {
        Conversation c = new Conversation();
        c.setType("group");
        c.setName("测试群聊");
        return c;
    }

    /** 创建一个消息实体（sender=SENDER, conversationId=CONV_ID） */
    private Message message(long id, UUID conversationId, UUID senderId) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderId(senderId);
        m.setKind("text");
        m.setBody("hello");
        return m;
    }

    /** 创建一个 AppUser mock（预建，避免嵌套 stubbing） */
    private static AppUser appUser(UUID id, String username, String displayName) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getUsername()).thenReturn(username);
        when(u.getDisplayName()).thenReturn(displayName);
        return u;
    }

    /** 预建全部测试用户 mock（避免 when+thenReturn 内嵌套创建 mock） */
    private final List<AppUser> allUsers = List.of(
            appUser(SENDER, "sender", "发送者"),
            appUser(MEMBER_A, "alice", "Alice"),
            appUser(MEMBER_B, "bob", "Bob"),
            appUser(MEMBER_C, "charlie", "Charlie")
    );

    /** 构建标准测试桩：会话存在、请求者是成员、消息存在且属于该会话 */
    @SuppressWarnings("unchecked")
    private void stubBasicScenario() {
        // 会话存在
        when(conversations.findById(CONV_ID)).thenReturn(Optional.of(groupConv()));
        // 请求者（SENDER）是成员
        when(members.existsByConversationIdAndUserId(CONV_ID, SENDER)).thenReturn(true);
        // 目标消息存在且属于该会话
        when(messages.findById(MSG_ID)).thenReturn(Optional.of(message(MSG_ID, CONV_ID, SENDER)));
        // 会话成员列表（4 人群：sender + A + B + C）
        when(members.findByConversationId(CONV_ID)).thenReturn(List.of(
                new ConversationMember(CONV_ID, SENDER, "owner"),
                new ConversationMember(CONV_ID, MEMBER_A, "member"),
                new ConversationMember(CONV_ID, MEMBER_B, "member"),
                new ConversationMember(CONV_ID, MEMBER_C, "member")
        ));
        // 用户资料（使用预建 mock，避免嵌套 stubbing）
        when(users.findAllById(org.mockito.ArgumentMatchers.anyList())).thenReturn(allUsers);
    }

    // ==================== 正向测试 ====================

    /**
     * 全员未读：所有接收方均无游标记录（从未打开会话），
     * readCount=0, unreadCount=3, allRead=false。
     */
    @Test
    void testAllUnread() {
        stubBasicScenario();
        // 无任何游标记录
        when(cursors.findByConversationId(CONV_ID)).thenReturn(List.of());

        MessageReadersDto dto = service.getMessageReaders(SENDER, CONV_ID, MSG_ID);

        assertEquals(CONV_ID, dto.conversationId());
        assertEquals(MSG_ID, dto.messageId());
        assertEquals(SENDER, dto.senderId());
        assertEquals(4, dto.totalMembers());      // sender + 3 接收方
        assertEquals(3, dto.totalRecipients());    // 排除发送者
        assertEquals(0, dto.readCount());
        assertEquals(3, dto.unreadCount());
        assertFalse(dto.allRead());
        assertNotNull(dto.sender());
        assertEquals("发送者", dto.sender().displayName());
        assertEquals(3, dto.unreaders().size());
        assertEquals(0, dto.readers().size());
    }

    /**
     * 部分已读：A 已读（游标 ≥ msgId），B 和 C 未读，
     * readCount=1, unreadCount=2, allRead=false。
     */
    @Test
    void testPartialRead() {
        stubBasicScenario();
        // A 已读（游标=100 >= msgId=100），B 游标=50 < 100 未读，C 无游标
        when(cursors.findByConversationId(CONV_ID)).thenReturn(List.of(
                new UserConversationCursor(MEMBER_A, CONV_ID, 100),
                new UserConversationCursor(MEMBER_B, CONV_ID, 50)
        ));

        MessageReadersDto dto = service.getMessageReaders(SENDER, CONV_ID, MSG_ID);

        assertEquals(1, dto.readCount());
        assertEquals(2, dto.unreadCount());
        assertFalse(dto.allRead());
        assertEquals(1, dto.readers().size());
        assertEquals("Alice", dto.readers().get(0).displayName());
        assertNotNull(dto.readers().get(0).readAt());
        assertEquals(2, dto.unreaders().size());
    }

    /**
     * 全员已读：所有接收方游标均 ≥ msgId，
     * readCount=3, unreadCount=0, allRead=true。
     */
    @Test
    void testAllRead() {
        stubBasicScenario();
        // 三个接收方全部已读
        when(cursors.findByConversationId(CONV_ID)).thenReturn(List.of(
                new UserConversationCursor(MEMBER_A, CONV_ID, 200),
                new UserConversationCursor(MEMBER_B, CONV_ID, 150),
                new UserConversationCursor(MEMBER_C, CONV_ID, 100)  // 恰好 = msgId=100，判定已读
        ));

        MessageReadersDto dto = service.getMessageReaders(SENDER, CONV_ID, MSG_ID);

        assertEquals(3, dto.readCount());
        assertEquals(0, dto.unreadCount());
        assertTrue(dto.allRead());
        assertEquals(3, dto.readers().size());
        assertEquals(0, dto.unreaders().size());
    }

    /**
     * 发送者游标不影响统计：发送者也有游标但不计入接收方读/未读。
     */
    @Test
    void testSenderExcludedFromRecipients() {
        stubBasicScenario();
        // 发送者自己也有游标，但不应计入 reader/unreader
        when(cursors.findByConversationId(CONV_ID)).thenReturn(List.of(
                new UserConversationCursor(SENDER, CONV_ID, 200),
                new UserConversationCursor(MEMBER_A, CONV_ID, 100)
        ));

        MessageReadersDto dto = service.getMessageReaders(SENDER, CONV_ID, MSG_ID);

        assertEquals(3, dto.totalRecipients());   // 不含 sender
        assertEquals(1, dto.readCount());          // 只有 A
        assertEquals(2, dto.unreadCount());        // B + C 未读
        assertEquals("发送者", dto.sender().displayName());
    }

    // ==================== 异常测试 ====================

    /** 非成员查询已读回执应抛 COL_4210 */
    @Test
    void testNonMemberRejected() {
        UUID outsider = UUID.randomUUID();
        when(conversations.findById(CONV_ID)).thenReturn(Optional.of(groupConv()));
        when(members.existsByConversationIdAndUserId(CONV_ID, outsider)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMessageReaders(outsider, CONV_ID, MSG_ID));
        assertTrue(ex.getMessage().contains("非会话成员"));
    }

    /** 消息不存在应抛 PLT_4040 */
    @Test
    void testMessageNotFound() {
        when(conversations.findById(CONV_ID)).thenReturn(Optional.of(groupConv()));
        when(members.existsByConversationIdAndUserId(CONV_ID, SENDER)).thenReturn(true);
        when(messages.findById(999L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMessageReaders(SENDER, CONV_ID, 999L));
        assertTrue(ex.getMessage().contains("消息不存在"));
    }

    /** 消息属于其他会话应抛 PLT_4040 */
    @Test
    void testMessageBelongsToOtherConversation() {
        UUID otherConv = UUID.randomUUID();
        when(conversations.findById(CONV_ID)).thenReturn(Optional.of(groupConv()));
        when(members.existsByConversationIdAndUserId(CONV_ID, SENDER)).thenReturn(true);
        when(messages.findById(MSG_ID)).thenReturn(Optional.of(message(MSG_ID, otherConv, SENDER)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMessageReaders(SENDER, CONV_ID, MSG_ID));
        assertTrue(ex.getMessage().contains("消息不属于该会话"));
    }
}
