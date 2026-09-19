package cn.teamone.collab.app;

import cn.teamone.collab.domain.Conversation;
import cn.teamone.collab.domain.ConversationMember;
import cn.teamone.collab.domain.Message;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.collab.repo.MessageRepository;
import cn.teamone.collab.repo.UserConversationCursorRepository;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话读侧纯逻辑单测（W3-④，MessageServiceTest 同风格）：清单过滤排序 /
 * hasMore 截断 / 非成员拒读。仓库全 mock（无 DB）；真库行为由手测覆盖。
 */
class ConversationQueryServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CID = UUID.randomUUID();

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

    private Conversation conversation(String type, boolean archived, Instant lastMessageAt) {
        Conversation c = new Conversation();
        // 实体 id/createdAt 由 DB 生成（仅 getter）：测试以 name 区分会话
        c.setType(type);
        c.setName(type + "-" + (archived ? "archived" : "active") + "-"
                + (lastMessageAt == null ? "nomsg" : lastMessageAt.getEpochSecond()));
        c.setTargetType("topic".equals(type) ? "defect" : null);
        c.setAutoCreated(true);
        if (archived) {
            c.setArchivedAt(Instant.now());
            c.setArchivedReason("target_closed");
        }
        c.setLastMessageAt(lastMessageAt);
        return c;
    }

    // ==================== 清单：type/archived 过滤 + lastMessageAt DESC nullsLast + unreadCount ====================

    /** findMineWithUnread 行投影 mock 助手（列序与仓库 SQL 对齐） */
    private static Object[] convRow(String type, String name, boolean archived,
                                    Instant lastMessageAt, long unread) {
        return new Object[] {UUID.randomUUID(), type, name,
                "topic".equals(type) ? "defect" : null, null, true,
                archived ? java.sql.Timestamp.from(Instant.now()) : null,
                archived ? "target_closed" : null,
                lastMessageAt == null ? null : 42L, lastMessageAt, unread,
                java.sql.Timestamp.from(Instant.now())};
    }

    @Test
    void listMine_filtersByTypeAndArchived_sortsByLastMessageAtDesc() {
        when(conversations.findMineWithUnread(USER)).thenReturn(List.of(
                convRow("topic", "topic-archived", true, Instant.parse("2026-09-11T10:00:00Z"), 3),
                convRow("topic", "topic-nomsg", false, null, 0),
                convRow("topic", "topic-active", false, Instant.parse("2026-09-11T12:00:00Z"), 2),
                convRow("dm", "dm-active", false, Instant.parse("2026-09-11T13:00:00Z"), 1)));
        when(members.countByConversationIdIn(anyCollection())).thenReturn(List.of());

        List<Map<String, Object>> items = service.listMine(USER, "topic", false);

        // type=topic 且未归档：干掉 archived 与 dm；lastMessageAt DESC、无消息排尾
        assertEquals(2, items.size());
        assertEquals("topic-active", items.get(0).get("name"));
        assertEquals("topic-nomsg", items.get(1).get("name"));
        assertFalse((Boolean) items.get(0).get("archived"));
        // INC-2 T-3：未读真相=SQL 差值投影（unread_count 直出）
        assertEquals(2, ((Number) items.get(0).get("unreadCount")).intValue());
    }

    @Test
    void listMine_archivedTrue_returnsOnlyArchived() {
        when(conversations.findMineWithUnread(USER)).thenReturn(List.of(
                convRow("topic", "topic-archived", true, null, 0),
                convRow("topic", "topic-active", false, null, 0)));
        when(members.countByConversationIdIn(anyCollection())).thenReturn(List.of());

        List<Map<String, Object>> items = service.listMine(USER, null, true);

        assertEquals(1, items.size());
        assertTrue((Boolean) items.get(0).get("archived"));
        assertEquals("target_closed", items.get(0).get("archivedReason"));
    }

    @Test
    void listMine_invalidType_throwsPLT4000() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listMine(USER, "channel2", null));
        assertEquals(ErrorCode.PLT_4000, ex.errorCode());
    }

    // ==================== 历史消息：hasMore 截断 / limit 上限 ====================

    @Test
    void messages_truncatesToLimitAndFlagsHasMore() {
        when(conversations.findById(CID)).thenReturn(java.util.Optional.of(conversation("topic", false, null)));
        when(members.existsByConversationIdAndUserId(CID, USER)).thenReturn(true);
        List<Message> fetched = new java.util.ArrayList<>();
        for (long id = 1; id <= 51; id++) {
            Message m = new Message();
            m.setConversationId(CID);
            m.setSenderId(USER);
            m.setKind("text");
            m.setBody("m" + id);
            fetched.add(m);
        }
        when(messages.findByConversationIdAndIdGreaterThanOrderByIdAsc(eq(CID), eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(fetched));

        Map<String, Object> res = service.messages(USER, CID, null, null, 50);

        assertEquals(50, ((List<?>) res.get("items")).size());
        assertTrue((Boolean) res.get("hasMore"));
        // limit+1 判 hasMore 的分页尺寸
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(messages).findByConversationIdAndIdGreaterThanOrderByIdAsc(
                eq(CID), eq(0L), page.capture());
        assertEquals(51, ((PageRequest) page.getValue()).getPageSize());
    }

    @Test
    void messages_underLimit_hasMoreFalse() {
        when(conversations.findById(CID)).thenReturn(java.util.Optional.of(conversation("topic", false, null)));
        when(members.existsByConversationIdAndUserId(CID, USER)).thenReturn(true);
        Message only = new Message();
        only.setConversationId(CID);
        only.setSenderId(USER);
        only.setKind("text");
        only.setBody("hello");
        when(messages.findByConversationIdAndIdGreaterThanOrderByIdAsc(eq(CID), eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(only)));

        Map<String, Object> res = service.messages(USER, CID, null, null, 50);

        assertEquals(1, ((List<?>) res.get("items")).size());
        assertFalse((Boolean) res.get("hasMore"));
    }

    // ==================== before 反向游标（M2-INC-1 W1 清账）：DESC 降序 / 互斥 ====================

    @Test
    void messages_beforeCursor_returnsDescPageWithHasMore() {
        when(conversations.findById(CID)).thenReturn(java.util.Optional.of(conversation("topic", false, null)));
        when(members.existsByConversationIdAndUserId(CID, USER)).thenReturn(true);
        List<Message> fetched = new java.util.ArrayList<>();
        // limit+1 口径：仓库返回 51 行（id 100..50）才有 hasMore=true；W1 原用例只造 50 行
        // （50>50 恒 false），断言与口径矛盾——按服务真实语义补足第 51 行
        for (long id = 100; id >= 50; id--) { // 仓库名已承诺 DESC：id 从大到小返回
            Message m = new Message();
            m.setConversationId(CID);
            m.setSenderId(USER);
            m.setKind("text");
            m.setBody("m" + id);
            fetched.add(m);
        }
        when(messages.findByConversationIdAndIdLessThanOrderByIdDesc(eq(CID), eq(101L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(fetched));

        Map<String, Object> res = service.messages(USER, CID, null, 101L, 50);

        assertEquals(50, ((List<?>) res.get("items")).size());
        assertTrue((Boolean) res.get("hasMore"));
        // limit+1 判 hasMore 的分页尺寸（反向游标同规则）
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(messages).findByConversationIdAndIdLessThanOrderByIdDesc(
                eq(CID), eq(101L), page.capture());
        assertEquals(51, ((PageRequest) page.getValue()).getPageSize());
    }

    @Test
    void messages_afterAndTogether_throwsPLT4000() {
        when(conversations.findById(CID)).thenReturn(java.util.Optional.of(conversation("topic", false, null)));
        when(members.existsByConversationIdAndUserId(CID, USER)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.messages(USER, CID, 1L, 100L, 50));

        assertEquals(ErrorCode.PLT_4000, ex.errorCode());
        org.mockito.Mockito.verifyNoInteractions(messages);
    }

    @Test
    void clampLimit_capsAt200_andFloorsAt1() {
        assertEquals(200, ConversationQueryService.clampLimit(500));
        assertEquals(1, ConversationQueryService.clampLimit(0));
        assertEquals(50, ConversationQueryService.clampLimit(50));
    }

    // ==================== 非成员拒读（COL_4210） ====================

    @Test
    void messages_nonMember_throwsCOL4210() {
        when(conversations.findById(CID)).thenReturn(java.util.Optional.of(conversation("topic", false, null)));
        when(members.existsByConversationIdAndUserId(CID, USER)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.messages(USER, CID, null, null, 50));

        assertEquals(ErrorCode.COL_4210, ex.errorCode());
        org.mockito.Mockito.verifyNoInteractions(messages);
    }

    @Test
    void detail_missingConversation_throwsPLT4040() {
        when(conversations.findById(CID)).thenReturn(java.util.Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.detail(USER, CID));

        assertEquals(ErrorCode.PLT_4040, ex.errorCode());
    }

    private static ConversationMember member(UUID conversationId) {
        return new ConversationMember(conversationId, USER, ConversationMember.ROLE_MEMBER);
    }
}
