package cn.teamone.collab.repo;

import cn.teamone.collab.domain.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 消息仓库（collab.message 分区表；查询恒带 conversation_id 走 idx_message_conv）。 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** clientMsgId 幂等回放查询（V5 部分索引 idx_message_client_msg_id 命中；
     *  分区表不能建跨分区唯一约束——V5 注释定稿走「事务内先查后插」，取最大 id 为准） */
    Optional<Message> findFirstByConversationIdAndClientMsgIdOrderByIdDesc(
            UUID conversationId, UUID clientMsgId);

    /** 历史消息拉取（05 §3.3 /conversations/{id}/messages，WS 离线补偿同接口）：
     *  游标 after=messageId，按 id ASC；limit 由 Pageable 控制（调用方取 limit+1 判 hasMore） */
    Page<Message> findByConversationIdAndIdGreaterThanOrderByIdAsc(
            UUID conversationId, Long id, Pageable pageable);

    /** 历史消息反向游标（M2-INC-1 W1 清账：05 §3.3 after= 语义扩展 before=）：
     *  before=messageId，按 id DESC 取更早一页；limit 由 Pageable 控制（调用方取 limit+1 判 hasMore） */
    Page<Message> findByConversationIdAndIdLessThanOrderByIdDesc(
            UUID conversationId, Long id, Pageable pageable);
}
