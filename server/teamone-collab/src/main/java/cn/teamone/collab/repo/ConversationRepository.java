package cn.teamone.collab.repo;

import cn.teamone.collab.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话仓库（collab.conversation）。 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** 对象化话题唯一查询（DB 部分唯一索引 uq_conversation_topic 同口径） */
    Optional<Conversation> findByTargetTypeAndTargetId(String targetType, UUID targetId);

    /** dm 防裂唯一路径（INC-2 红线 5）：dedup_key='dm:{小id}:{大id}'，unique 索引兜底 */
    Optional<Conversation> findByDedupKey(String dedupKey);

    /**
     * 会话清单 + 未读数准确查询：
     * 未读数 = 属于该会话、消息ID大于用户最后已读游标、且排除当前用户本人发送的消息总数（剔除已撤回消息）。
     * 修复原差值算法中因 BIGSERIAL 全局自增导致的新会话虚高未读（如 12 个未读）以及本人发送被计入未读（如 8 个未读）的缺陷。
     */
    @Query(value = """
            SELECT c.id, c.type, c.name, c.target_type, c.target_id, c.auto_created,
                   c.archived_at, c.archived_reason, c.last_message_id, c.last_message_at,
                   COALESCE((
                       SELECT COUNT(msg.id)
                       FROM collab.message msg
                       WHERE msg.conversation_id = c.id
                         AND msg.id > COALESCE(cur.last_read_message_id, 0)
                         AND msg.sender_id != :userId
                         AND msg.withdrawn_at IS NULL
                   ), 0) AS unread_count,
                   c.created_at
            FROM collab.conversation c
            JOIN collab.conversation_member m
              ON m.conversation_id = c.id AND m.user_id = :userId
            LEFT JOIN collab.user_conversation_cursor cur
              ON cur.conversation_id = c.id AND cur.user_id = :userId
            """, nativeQuery = true)
    List<Object[]> findMineWithUnread(@Param("userId") UUID userId);

    /**
     * 侧栏 IM 总未读聚合（R-10d，GET /conversations/unread-summary）：
     * 对 {@link #findMineWithUnread} 同一相关子查询 SUM 一层——与 GET /conversations
     * 各行 unread_count 求和恒等（同 SQL 同口径，绝读侧一致性）。
     * 归档会话一并计入（与无参 GET /conversations 行集合对齐，求和口径不分叉）。
     */
    @Query(value = """
            SELECT COALESCE(SUM((
                       SELECT COUNT(msg.id)
                       FROM collab.message msg
                       WHERE msg.conversation_id = c.id
                         AND msg.id > COALESCE(cur.last_read_message_id, 0)
                         AND msg.sender_id != :userId
                         AND msg.withdrawn_at IS NULL
                   )), 0)
            FROM collab.conversation c
            JOIN collab.conversation_member m
              ON m.conversation_id = c.id AND m.user_id = :userId
            LEFT JOIN collab.user_conversation_cursor cur
              ON cur.conversation_id = c.id AND cur.user_id = :userId
            """, nativeQuery = true)
    long sumUnread(@Param("userId") UUID userId);
}
