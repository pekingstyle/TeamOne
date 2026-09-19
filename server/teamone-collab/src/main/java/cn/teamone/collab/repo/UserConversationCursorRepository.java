package cn.teamone.collab.repo;

import cn.teamone.collab.domain.UserConversationCursor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * 已读游标仓库（collab.user_conversation_cursor，V9）。
 *
 * <p>upsert 幂等（INC-2 红线 2）：{@code ON CONFLICT (user_id,conversation_id) DO UPDATE
 * SET last_read_message_id = GREATEST(...)}——只前进不回退（§4.3 read 语义），
 * 重复 read 帧/乱序帧重放零副作用。</p>
 */
public interface UserConversationCursorRepository extends JpaRepository<UserConversationCursor, UserConversationCursor.Pk> {

    /** upsert 推进游标（GREATEST 只前进；read_at=now()）；返回受影响行数恒为 1 */
    @Modifying
    @Query(value = """
            INSERT INTO collab.user_conversation_cursor
              (user_id, conversation_id, last_read_message_id, read_at)
            VALUES (:userId, :conversationId, :lastReadMessageId, now())
            ON CONFLICT (user_id, conversation_id) DO UPDATE
              SET last_read_message_id = GREATEST(user_conversation_cursor.last_read_message_id,
                                                  EXCLUDED.last_read_message_id),
                  read_at = now()
            """, nativeQuery = true)
    int upsertAdvance(@Param("userId") UUID userId,
                      @Param("conversationId") UUID conversationId,
                      @Param("lastReadMessageId") long lastReadMessageId);

    /** upsert 后取实际生效值（GREATEST 结果，供未读重算；游标只前进故 ≤ last_message_id） */
    @Query(value = """
            SELECT last_read_message_id FROM collab.user_conversation_cursor
            WHERE user_id = :userId AND conversation_id = :conversationId
            """, nativeQuery = true)
    Long effectiveLastRead(@Param("userId") UUID userId,
                           @Param("conversationId") UUID conversationId);

    /**
     * 查询指定会话全部成员的已读游标（已读回执明细下钻，Direction 4 Phase 4）。
     *
     * <p>返回该会话中所有曾打开过会话（推进过游标）的成员记录，
     * 结合 conversation_member 全量成员可区分已读与从未打开会话的未读成员。</p>
     */
    List<UserConversationCursor> findByConversationId(UUID conversationId);
}
