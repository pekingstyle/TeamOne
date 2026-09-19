package cn.teamone.collab.app;

import cn.teamone.collab.domain.Conversation;
import cn.teamone.collab.domain.Message;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.collab.repo.MessageRepository;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MessageService 纯逻辑单测（W3-③b 红线 6）：幂等回放 / 成员校验 / 归档拒写；
 * INC-2 增补：附件 ready 校验（红线 4）与撤回（脱敏+时间窗 COL_4209，T-4）。
 * 仓库与 OutboxWriter 全 mock（无 DB 依赖）；真库行为由起服后的手测覆盖（见周报）。
 */
class MessageServiceTest {

    private static final UUID CID = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID CLIENT_MSG_ID = UUID.randomUUID();

    private ConversationRepository conversations;
    private ConversationMemberRepository members;
    private MessageRepository messages;
    private cn.teamone.platform.repo.FileObjectRepository files;
    private cn.teamone.platform.repo.AppUserRepository users;
    private OutboxWriter outbox;
    private ImFastFanout fastFanout;
    private cn.teamone.collab.repo.NotificationRepository notifications;
    private MessageService service;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        members = mock(ConversationMemberRepository.class);
        messages = mock(MessageRepository.class);
        files = mock(cn.teamone.platform.repo.FileObjectRepository.class);
        users = mock(cn.teamone.platform.repo.AppUserRepository.class);
        outbox = mock(OutboxWriter.class);
        fastFanout = mock(ImFastFanout.class); // L3 直推（IM 延迟诊断批）：单测全 mock，真链路由 IT 覆盖
        notifications = mock(cn.teamone.collab.repo.NotificationRepository.class); // V16 @提醒落库
        service = new MessageService(conversations, members, messages, files, users,
                outbox, fastFanout, notifications);
    }

    private Conversation conversation(String archivedReason) {
        Conversation c = new Conversation();
        c.setType(Conversation.TYPE_TOPIC);
        if (archivedReason != null) {
            c.setArchivedAt(Instant.now());
            c.setArchivedReason(archivedReason);
        }
        return c;
    }

    // ==================== 幂等：clientMsgId 回放 ====================

    @Test
    void send_happyPath_persistsUpdatesConversationAndAppendsOutbox() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(true);
        when(messages.findFirstByConversationIdAndClientMsgIdOrderByIdDesc(CID, CLIENT_MSG_ID))
                .thenReturn(Optional.empty());
        when(messages.saveAndFlush(any())).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            java.lang.reflect.Field f = Message.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, 42L); // 模拟 DB identity 回填
            return m;
        });

        Message sent = service.send(CID, SENDER, CLIENT_MSG_ID, "text", "hello", null, null);

        assertEquals(42L, sent.getId().longValue());
        // 会话 last_message_* 同事务冗余更新
        ArgumentCaptor<Conversation> convCap = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(convCap.capture());
        assertEquals(42L, convCap.getValue().getLastMessageId().longValue());
        assertEquals(sent.getCreatedAt(), convCap.getValue().getLastMessageAt());
        // outbox(message.created) 同事务落库（红线 3）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payloadCap =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(outbox).append(eq("message"), eq(CID), eq("message.created"), payloadCap.capture(), eq(SENDER));
        assertEquals(42, ((Number) payloadCap.getValue().get("msgId")).intValue());
        assertEquals("hello", payloadCap.getValue().get("body"));
    }

    @Test
    void send_duplicateClientMsgId_replaysOriginalWithoutNewRow() {
        Message original = new Message();
        original.setConversationId(CID);
        original.setSenderId(SENDER);
        original.setClientMsgId(CLIENT_MSG_ID);
        original.setBody("first");

        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(true);
        when(messages.findFirstByConversationIdAndClientMsgIdOrderByIdDesc(CID, CLIENT_MSG_ID))
                .thenReturn(Optional.of(original));

        Message replayed = service.send(CID, SENDER, CLIENT_MSG_ID, "text", "second-attempt", null, null);

        // 回放返回原消息：同一实体、原 body——重复帧零写入（红线 1）
        assertSame(original, replayed);
        assertEquals("first", replayed.getBody());
        verify(messages, never()).saveAndFlush(any(Message.class));
        verify(conversations, never()).save(any(Conversation.class));
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), any(), any(UUID.class));
    }

    @Test
    void send_differentClientMsgIds_bothPersist() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(true);
        when(messages.findFirstByConversationIdAndClientMsgIdOrderByIdDesc(eq(CID), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(messages.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Message m1 = service.send(CID, SENDER, id1, "text", "a", null, null);
        Message m2 = service.send(CID, SENDER, id2, "text", "b", null, null);

        assertNotEquals(m1.getClientMsgId(), m2.getClientMsgId());
        verify(messages, times(2)).saveAndFlush(any(Message.class));
        verify(outbox, times(2)).append(anyString(), any(UUID.class), anyString(), any(), any(UUID.class));
    }

    // ==================== 成员校验（COL_4210） ====================

    @Test
    void send_nonMember_throwsCOL4210_withoutWrites() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(CID, SENDER, CLIENT_MSG_ID, "text", "hi", null, null));

        assertEquals(ErrorCode.COL_4210, ex.errorCode());
        verify(messages, never()).saveAndFlush(any(Message.class));
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), any(), any(UUID.class));
    }

    // ==================== 归档拒写（COL_4203） ====================

    @Test
    void send_archivedConversation_throwsCOL4203_withoutWrites() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation("target_closed")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(CID, SENDER, CLIENT_MSG_ID, "text", "hi", null, null));

        assertEquals(ErrorCode.COL_4203, ex.errorCode());
        assertTrue(ex.details().contains("archivedReason=target_closed"));
        verify(members, never()).existsByConversationIdAndUserId(any(), any());
        verify(messages, never()).saveAndFlush(any(Message.class));
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), any(), any(UUID.class));
    }

    // ==================== 会话不存在（PLT_4040） ====================

    @Test
    void send_missingConversation_throwsPLT4040() {
        when(conversations.findById(CID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(CID, SENDER, CLIENT_MSG_ID, "text", "hi", null, null));

        assertEquals(ErrorCode.PLT_4040, ex.errorCode());
    }

    // ==================== INC-2 T-5：附件 ready 校验（红线 4） ====================

    @Test
    void send_attachmentNotReady_throwsPLT4000_withoutWrites() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(true);
        UUID fileId = UUID.randomUUID();
        var uploading = new cn.teamone.platform.domain.FileObject();
        uploading.setId(fileId);
        uploading.setStatus(cn.teamone.platform.domain.FileObject.STATUS_UPLOADING);
        when(files.findAllByIdIn(any())).thenReturn(java.util.List.of(uploading));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(CID, SENDER, CLIENT_MSG_ID, "file", null,
                        java.util.List.of(java.util.Map.of("fileId", fileId.toString())), null, null));

        assertEquals(ErrorCode.PLT_4000, ex.errorCode());
        verify(messages, never()).saveAndFlush(any(Message.class));
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), any(), any(UUID.class));
    }

    @Test
    void send_attachmentReady_persistsWithAttachments() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(true);
        when(messages.findFirstByConversationIdAndClientMsgIdOrderByIdDesc(eq(CID), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(messages.saveAndFlush(any())).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            java.lang.reflect.Field f = Message.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, 7L);
            return m;
        });
        UUID fileId = UUID.randomUUID();
        var ready = new cn.teamone.platform.domain.FileObject();
        ready.setId(fileId);
        ready.setStatus(cn.teamone.platform.domain.FileObject.STATUS_READY);
        when(files.findAllByIdIn(any())).thenReturn(java.util.List.of(ready));

        Message sent = service.send(CID, SENDER, CLIENT_MSG_ID, "file", null,
                java.util.List.of(java.util.Map.of("fileId", fileId.toString())), null, null);

        assertEquals(java.util.List.of(java.util.Map.of("fileId", fileId.toString())),
                sent.getAttachments());
    }

    // ==================== V16 R-10e：@提醒（mentions 过滤 + notification 事务内落库） ====================

    @Test
    void send_mentions_filteredToMembers_andNotificationsWrittenInTransaction() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(true);
        when(messages.findFirstByConversationIdAndClientMsgIdOrderByIdDesc(eq(CID), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(messages.saveAndFlush(any())).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            java.lang.reflect.Field f = Message.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, 11L);
            return m;
        });
        UUID peer = UUID.randomUUID();          // 会话成员：应被提醒
        UUID stranger = UUID.randomUUID();      // 非成员：静默剔除
        when(members.findUserIdsByConversationId(CID)).thenReturn(java.util.List.of(SENDER, peer));
        when(users.findById(SENDER)).thenReturn(Optional.empty()); // displayName 降级短码

        Message sent = service.send(CID, SENDER, CLIENT_MSG_ID, "text", "@peer 看下这个",
                null, new java.util.ArrayList<>(java.util.Arrays.asList(peer, peer, stranger, SENDER, null)),
                null, null);

        // 实体 mentions：去重 + 剔除发送者/非成员/null
        assertEquals(java.util.List.of(peer), sent.getMentions());
        // outbox payload 携带 mentions 透传（L2 兜底据此补推 notify 帧）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payloadCap =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(outbox).append(eq("message"), eq(CID), eq("message.created"), payloadCap.capture(), eq(SENDER));
        assertEquals(java.util.List.of(peer.toString()), payloadCap.getValue().get("mentions"));
        // notification 事务内逐成员落库（先落库后推送）
        ArgumentCaptor<cn.teamone.collab.domain.Notification> notifCap =
                ArgumentCaptor.forClass(cn.teamone.collab.domain.Notification.class);
        verify(notifications).save(notifCap.capture());
        assertEquals(cn.teamone.collab.domain.Notification.KIND_IM_MENTION,
                notifCap.getValue().getKind());
        assertEquals(peer, notifCap.getValue().getUserId());
        assertEquals(CID.toString(), notifCap.getValue().getPayload().get("conversationId"));
    }

    @Test
    void send_withoutMentions_noNotificationWrites() {
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(members.existsByConversationIdAndUserId(CID, SENDER)).thenReturn(true);
        when(messages.findFirstByConversationIdAndClientMsgIdOrderByIdDesc(eq(CID), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(messages.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.send(CID, SENDER, CLIENT_MSG_ID, "text", "plain", null, null);

        verify(notifications, never()).save(any());
        verify(members, never()).findUserIdsByConversationId(any()); // 空 mentions 不触发成员查询
    }

    // ==================== INC-2 T-4：撤回（脱敏+时间窗） ====================

    private Message withdrawable(String kind) {
        Message m = new Message();
        m.setConversationId(CID);
        m.setSenderId(SENDER);
        m.setKind(kind);
        m.setBody("secret-body");
        m.setCreatedAt(Instant.now());
        return m;
    }

    @Test
    void withdraw_bySender_masksPayloadButKeepsRow() {
        Message msg = withdrawable("text");
        when(messages.findById(9L)).thenReturn(Optional.of(msg));
        when(messages.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        var me = new cn.teamone.platform.domain.AppUser();
        me.setDisplayName("张三");
        when(users.findById(SENDER)).thenReturn(java.util.Optional.of(me));

        Message out = service.withdraw(CID, 9L, SENDER, 24);

        assertTrue(out.isWithdrawn());                 // DB 行保留（原文不出参）
        // 脱敏契约：withdrawn=true / body=null / attachments=[]（红线 3）
        assertEquals(Boolean.TRUE, out.toPayload().get("withdrawn"));
        assertNull(out.toPayload().get("body"));
        assertEquals(java.util.List.of(), out.toPayload().get("attachments"));
        // 灰条 system 消息插入（saveAndFlush 第二次调用）
        verify(messages, times(2)).saveAndFlush(any(Message.class));
    }

    @Test
    void withdraw_byOther_throwsPLT4030() {
        when(messages.findById(9L)).thenReturn(Optional.of(withdrawable("text")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.withdraw(CID, 9L, UUID.randomUUID(), 24));

        assertEquals(ErrorCode.PLT_4030, ex.errorCode());
        verify(messages, never()).saveAndFlush(any(Message.class));
    }

    @Test
    void withdraw_systemMessage_throwsPLT4000() {
        when(messages.findById(9L)).thenReturn(Optional.of(withdrawable("system")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.withdraw(CID, 9L, SENDER, 24));

        assertEquals(ErrorCode.PLT_4000, ex.errorCode());
    }

    @Test
    void withdraw_outOfWindow_throwsCOL4209() {
        Message old = withdrawable("text");
        old.setCreatedAt(Instant.now().minus(Duration.ofHours(25)));
        when(messages.findById(9L)).thenReturn(Optional.of(old));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.withdraw(CID, 9L, SENDER, 24));

        assertEquals(ErrorCode.COL_4209, ex.errorCode());
        verify(messages, never()).saveAndFlush(any(Message.class));
    }

    @Test
    void withdraw_zeroWindowMeansUnlimited_andTwiceIsIdempotent() {
        Message old = withdrawable("text");
        old.setCreatedAt(Instant.now().minus(Duration.ofHours(1000)));
        when(messages.findById(9L)).thenReturn(Optional.of(old));
        when(messages.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversations.findById(CID)).thenReturn(Optional.of(conversation(null)));
        when(users.findById(SENDER)).thenReturn(java.util.Optional.empty());

        Message out = service.withdraw(CID, 9L, SENDER, 0); // ≤0=不限窗（测试口径）
        Message again = service.withdraw(CID, 9L, SENDER, 0);

        assertSame(out, again);
        assertTrue(out.isWithdrawn());
        // 幂等：第二次不重复插灰条（仍只有撤回 + 首条灰条两次写）
        verify(messages, times(2)).saveAndFlush(any(Message.class));
    }
}
