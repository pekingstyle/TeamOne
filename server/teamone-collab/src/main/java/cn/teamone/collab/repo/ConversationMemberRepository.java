package cn.teamone.collab.repo;

import cn.teamone.collab.domain.ConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 会话成员仓库（collab.conversation_member，复合主键天然去重）。 */
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMember.Pk> {

    /** WS conv 频道订阅鉴权 / msg 发送成员校验（真相查询，鉴权结果缓存 5min 在调用侧） */
    boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);

    List<ConversationMember> findByConversationId(UUID conversationId);

    /** 批量按会话 ID 查询成员（用于批量解析私聊对端用户，显示对端真实姓名与头像） */
    List<ConversationMember> findByConversationIdIn(Collection<UUID> conversationIds);

    /** 本人参与的会话 id 清单（会话列表读侧入口，05 §3.3 GET /conversations） */
    List<ConversationMember> findByUserId(UUID userId);

    /** 会话现有成员 id 清单（autoTopic 补拉缺失成员的差集基准） */
    @Query("select m.userId from ConversationMember m where m.conversationId = :conversationId")
    List<UUID> findUserIdsByConversationId(@Param("conversationId") UUID conversationId);

    /** 会话成员计数（会话清单 memberCount 投影，一次 group by） */
    @Query("select m.conversationId, count(m) from ConversationMember m " +
           "where m.conversationId in :conversationIds group by m.conversationId")
    List<Object[]> countByConversationIdIn(@Param("conversationIds") Collection<UUID> conversationIds);
}
