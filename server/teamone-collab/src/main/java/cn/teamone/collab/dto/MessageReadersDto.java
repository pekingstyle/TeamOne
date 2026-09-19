package cn.teamone.collab.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 消息已读/未读成员统计及明细 DTO（Direction 4 Phase 4 · IM 读回执能力）。
 *
 * <p>核心业务语义与计算规则：
 * <ul>
 *   <li>真相来源：基于 {@code collab.user_conversation_cursor} 的 {@code last_read_message_id} 游标；</li>
 *   <li>已读判定：会话成员的游标满足 {@code last_read_message_id >= messageId} 即判定为已读；</li>
 *   <li>未读判定：会话成员游标小于目标消息 ID 或尚无任何游标记录（从未打开会话）；</li>
 *   <li>发送者归属：消息发送者本人天然获悉自己所发内容，归入 sender 单独展示；</li>
 *   <li>明细下钻：分别提供 readers（已读人员列表含已读时间戳）与 unreaders（未读人员列表）。</li>
 * </ul>
 * </p>
 *
 * @param conversationId   会话全局唯一 ID
 * @param messageId        目标消息自增 ID
 * @param senderId         消息发送者用户 ID
 * @param totalMembers     会话全部成员数（包含发送者本人）
 * @param totalRecipients  接收方总人数（排除发送者本人）
 * @param readCount        接收方已读人数
 * @param unreadCount      接收方未读人数
 * @param allRead          接收方是否已全员读毕（unreadCount == 0 且 totalRecipients > 0）
 * @param sender           消息发送者基本资料
 * @param readers          已读成员列表（按 readAt 已读时间升序排列）
 * @param unreaders        未读成员列表（按 displayName 字典序排列）
 */
public record MessageReadersDto(
        UUID conversationId,
        long messageId,
        UUID senderId,
        int totalMembers,
        int totalRecipients,
        int readCount,
        int unreadCount,
        boolean allRead,
        MemberBrief sender,
        List<MemberBrief> readers,
        List<MemberBrief> unreaders
) {
    /**
     * 会话成员简报（已读/未读均使用同一结构，readAt 区分状态）。
     *
     * @param userId      用户唯一标识 UUID
     * @param username    登录账号名
     * @param displayName 显示昵称/真实姓名
     * @param readAt      用户推进游标覆盖该消息的时间戳（未读成员为 null）
     */
    public record MemberBrief(
            UUID userId,
            String username,
            String displayName,
            Instant readAt
    ) {}
}
