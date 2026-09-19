package cn.teamone.collab.app;

import cn.teamone.collab.domain.Conversation;
import cn.teamone.collab.domain.ConversationMember;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.collab.repo.MessageRepository;
import cn.teamone.collab.repo.UserConversationCursorRepository;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationService 写路径纯逻辑单测（M2-INC-2 T-2/T-3）：
 * dm dedup_key 防裂（红线 5）/ group owner 规则 / 成员管理权限 / read 游标越界（红线 2）。
 * 仓库与 OutboxWriter 全 mock；真库行为由起服后的手测覆盖。
 */
class ConversationServiceTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID PEER = UUID.randomUUID();
    private static final UUID CID = UUID.randomUUID();

    private ConversationRepository conversations;
    private ConversationMemberRepository members;
    private MessageRepository messages;
    private UserConversationCursorRepository cursors;
    private AppUserRepository users;
    private OutboxWriter outbox;
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
    private StringRedisTemplate redis;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        members = mock(ConversationMemberRepository.class);
        messages = mock(MessageRepository.class);
        cursors = mock(UserConversationCursorRepository.class);
        users = mock(AppUserRepository.class);
        outbox = mock(OutboxWriter.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new ConversationService(conversations, members, messages, cursors,
                users, outbox, redis);
    }

    /** 实体 id 由 DB 生成（仅 getter）：测试以反射注入（MessageServiceTest 同口径） */
    private static Conversation withId(Conversation c, UUID id) {
        try {
            java.lang.reflect.Field f = Conversation.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    private Conversation group() {
        Conversation c = new Conversation();
        c.setType(Conversation.TYPE_GROUP);
        c.setName("群");
        return withId(c, CID);
    }

    // ==================== dm 防裂（红线 5） ====================

    @Test
    void createDm_dedupKeyIsCanonicalPair() {
        when(users.existsById(PEER)).thenReturn(true);
        when(conversations.findByDedupKey(anyString())).thenReturn(Optional.empty());

        service.createDm(ME, PEER);

        String expected = "dm:" + (ME.toString().compareTo(PEER.toString()) < 0
                ? ME + ":" + PEER : PEER + ":" + ME);
        ArgumentCaptor<Conversation> cap = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).saveAndFlush(cap.capture());
        assertEquals(expected, cap.getValue().getDedupKey());
        assertEquals(Conversation.TYPE_DM, cap.getValue().getType());
    }

    @Test
    void createDm_reversedPair_hitsExisting_withExistingTrue() {
        // 顺序无关：ME 建 (ME,PEER) 后 PEER 反向建 → 同 dedup_key 命中既有，零插入
        when(users.existsById(ME)).thenReturn(true);
        Conversation existing = withId(new Conversation(), CID);
        existing.setType(Conversation.TYPE_DM);
        String key = "dm:" + (ME.toString().compareTo(PEER.toString()) < 0
                ? ME + ":" + PEER : PEER + ":" + ME);
        existing.setDedupKey(key);
        when(conversations.findByDedupKey(key)).thenReturn(Optional.of(existing));

        var result = service.createDm(PEER, ME);

        assertEquals(CID, result.get("id"));
        assertEquals(Boolean.TRUE, result.get("existing"));
        verify(conversations, never()).saveAndFlush(any(Conversation.class));
    }

    @Test
    void createDm_selfOrThreeWay_rejected() {
        BusinessException self = assertThrows(BusinessException.class, () -> service.createDm(ME, ME));
        assertEquals(ErrorCode.PLT_4000, self.errorCode());
        BusinessException noPeer = assertThrows(BusinessException.class, () -> service.createDm(ME, null));
        assertEquals(ErrorCode.PLT_4000, noPeer.errorCode());
    }

    // ==================== group 写端点 ====================

    @Test
    void createGroup_nameRequired_creatorOwner_dedupMemberIds() {
        when(users.existsById(PEER)).thenReturn(true);
        var result = service.createGroup(ME, " 项目组 ", List.of(PEER, PEER, ME));

        assertFalse((Boolean) result.get("existing"));
        ArgumentCaptor<Conversation> cap = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).saveAndFlush(cap.capture());
        assertEquals("项目组", cap.getValue().getName());
        assertNull(cap.getValue().getDedupKey());
        // creator=owner；重复/自身剔除后仅 PEER 一条 member
        ArgumentCaptor<ConversationMember> memCap = ArgumentCaptor.forClass(ConversationMember.class);
        verify(members, org.mockito.Mockito.times(2)).save(memCap.capture());
        assertEquals(ConversationMember.ROLE_OWNER, memCap.getAllValues().get(0).getRole());
        assertEquals(ConversationMember.ROLE_MEMBER, memCap.getAllValues().get(1).getRole());
        assertEquals(PEER, memCap.getAllValues().get(1).getUserId());
    }

    @Test
    void createGroup_blankName_throwsPLT4000() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createGroup(ME, "  ", List.of()));
        assertEquals(ErrorCode.PLT_4000, ex.errorCode());
    }

    @Test
    void addMembers_dmConversation_throwsPLT4000_nonOwner_throwsPLT4030() {
        // topic 同理走 requireGroup 拒绝；这里断言 dm 拒绝
        Conversation dm = withId(new Conversation(), CID);
        dm.setType(Conversation.TYPE_DM);
        when(conversations.findById(CID)).thenReturn(Optional.of(dm));
        BusinessException typeErr = assertThrows(BusinessException.class,
                () -> service.addMembers(ME, CID, List.of(PEER)));
        assertEquals(ErrorCode.PLT_4000, typeErr.errorCode());

        // group 但非 owner → PLT_4030
        when(conversations.findById(CID)).thenReturn(Optional.of(group()));
        when(members.findById(new ConversationMember.Pk(CID, ME)))
                .thenReturn(Optional.of(new ConversationMember(CID, ME, ConversationMember.ROLE_MEMBER)));
        BusinessException roleErr = assertThrows(BusinessException.class,
                () -> service.addMembers(ME, CID, List.of(PEER)));
        assertEquals(ErrorCode.PLT_4030, roleErr.errorCode());
    }

    @Test
    void removeMember_ownerQuit_rejected_missingTarget_throwsPLT4040() {
        when(conversations.findById(CID)).thenReturn(Optional.of(group()));
        when(members.findById(new ConversationMember.Pk(CID, ME)))
                .thenReturn(Optional.of(new ConversationMember(CID, ME, ConversationMember.ROLE_OWNER)));

        BusinessException ownerQuit = assertThrows(BusinessException.class,
                () -> service.removeMember(ME, CID, ME));
        assertEquals(ErrorCode.PLT_4000, ownerQuit.errorCode()); // 简化：owner 不可退

        BusinessException noTarget = assertThrows(BusinessException.class,
                () -> service.removeMember(ME, CID, PEER));
        assertEquals(ErrorCode.PLT_4040, noTarget.errorCode());
    }

    @Test
    void removeMember_byOwner_publishesMembersChanged() {
        when(conversations.findById(CID)).thenReturn(Optional.of(group()));
        when(members.findById(new ConversationMember.Pk(CID, PEER)))
                .thenReturn(Optional.of(new ConversationMember(CID, PEER, ConversationMember.ROLE_MEMBER)));
        when(members.findById(new ConversationMember.Pk(CID, ME)))
                .thenReturn(Optional.of(new ConversationMember(CID, ME, ConversationMember.ROLE_OWNER)));

        service.removeMember(ME, CID, PEER);

        verify(members).deleteById(new ConversationMember.Pk(CID, PEER));
        verify(outbox).append(eq("conversation"), eq(CID),
                eq("conversation.members_changed"), any(), eq(ME));
    }

    // ==================== read 游标（红线 2） ====================

    @Test
    void advanceReadCursor_outOfBounds_throwsPLT4000() {
        Conversation conv = withId(new Conversation(), CID);
        conv.setType(Conversation.TYPE_DM);
        conv.setLastMessageId(100L);
        when(conversations.findById(CID)).thenReturn(Optional.of(conv));
        when(members.existsByConversationIdAndUserId(CID, ME)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.advanceReadCursor(ME, CID, 101L));
        assertEquals(ErrorCode.PLT_4000, ex.errorCode());
        verify(cursors, never()).upsertAdvance(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void advanceReadCursor_happyPath_upsertsAndReturnsUnread() {
        Conversation conv = withId(new Conversation(), CID);
        conv.setType(Conversation.TYPE_DM);
        conv.setLastMessageId(100L);
        when(conversations.findById(CID)).thenReturn(Optional.of(conv));
        when(members.existsByConversationIdAndUserId(CID, ME)).thenReturn(true);
        when(cursors.effectiveLastRead(ME, CID)).thenReturn(97L);

        long unread = service.advanceReadCursor(ME, CID, 97L);

        assertEquals(3, unread);
        verify(cursors).upsertAdvance(ME, CID, 97L);
        verify(hashOps).put(eq("unread:" + ME), eq(CID.toString()), eq("3"));
    }

    @Test
    void advanceReadCursor_valkeyDown_degradesWarnNotThrow() {
        Conversation conv = withId(new Conversation(), CID);
        conv.setType(Conversation.TYPE_DM);
        conv.setLastMessageId(5L);
        when(conversations.findById(CID)).thenReturn(Optional.of(conv));
        when(members.existsByConversationIdAndUserId(CID, ME)).thenReturn(true);
        when(cursors.effectiveLastRead(ME, CID)).thenReturn(5L);
        when(redis.opsForHash()).thenThrow(new IllegalStateException("valkey down"));

        long unread = service.advanceReadCursor(ME, CID, 5L); // 不抛：缓存降级
        assertEquals(0, unread);
    }
}
