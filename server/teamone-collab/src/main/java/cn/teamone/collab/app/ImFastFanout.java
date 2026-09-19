package cn.teamone.collab.app;

import cn.teamone.collab.domain.Message;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * IM 消息 L3 直推加速器（05 §推拉结合纪律：WS 在线推送是加速器（L3），DB+游标拉取是唯一真相）。
 *
 * <p><b>动机（IM 延迟诊断批）</b>：修复前 message.created 的 WS 扇出完全依赖 L2 事件链
 * （Outbox → Relay 500ms 轮询 → Stream → EventConsumer → PUBLISH），在线用户平均白等
 * ~300ms（最坏 ~960ms）才见到新消息/未读角标——05 §6.2 发消息处理器明文「提交后 →
 * WS 扇出 conv:{cid}（L3）」未落地。本类把扇出提到事务 afterCommit 直推：
 * 在线用户零轮询延迟；L2 事件链保留为可靠性与离线通知兜底（缩水为幂等重放）。</p>
 *
 * <p><b>防重复</b>：直推成功后写短 TTL 标记 {@code teamone:im:pushed:{msgId}}
 * （SETNX EX 60）；app 侧 FanoutHandler 消费 message.created 前检查该标记，已推则跳过
 * （事件照常 ACK——其职责退化为落库类副作用兜底）。先推后标记：若直推 PUBLISH 失败
 * 则不落标记，事件链照常补推（至少一次）；标记检查失败（Valkey 抖动）按未推处理，
 * 最坏双推一次——订阅端按 msgId 幂等渲染，无害。</p>
 *
 * <p><b>帧形状与 {@code FanoutHandler} 完全一致</b>（{ch, frame:{v,id,type:event,ts,
 * payload:{kind,payload}}} 信封；沿 message.withdrawn L3 直推先例，ConversationController
 * publishWithdrawn），前端零改动兼容。unsub/offline 的成员 PUBLISH 到达后无匹配者即丢弃
 * （各 WS 节点 pushToChannel 兜底），无需在线名单。</p>
 *
 * <p><b>安全不回退</b>：直推只是把「既有帧」提前发到既有 fanout 频道，WS 节点收到后仍走
 * 本地 channelSubs 匹配——该订阅表只含已通过 §4.4 频道鉴权（conv 成员/user 本人）的会话，
 * 直推不产生任何新频道、不绕过成员校验。全部异常仅 WARN 降级（红线 5）：
 * 事务已提交不回滚，事件链兜底最终一致。</p>
 *
 * <p>模块方向：本类在 collab（MessageService 同域），仅依赖 collab 仓 + spring-data-redis
 * （ConversationController 先例），不触碰 app——R4/R7 合规。</p>
 */
@Component
public class ImFastFanout {

    private static final Logger log = LoggerFactory.getLogger(ImFastFanout.class);

    /**
     * 直推去重标记键前缀（与 app 侧 FanoutHandler 共识；app 可依赖 collab，由其引用本常量）。
     * 短 TTL 60s：只需盖住 L2 事件链的补推窗口（秒级），过期自清零残留。
     */
    public static final String PUSHED_KEY_PREFIX = "teamone:im:pushed:";
    private static final Duration PUSHED_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final ConversationMemberRepository convMembers;
    private final ConversationRepository conversations;
    private final String fanoutChannel;
    private final boolean enabled;

    public ImFastFanout(@Qualifier("stringRedisTemplate") StringRedisTemplate redis,
                        ObjectMapper om,
                        ConversationMemberRepository convMembers,
                        ConversationRepository conversations,
                        // collab 不可依赖 app（R4），频道名/开关以同键 @Value 绑定（TeamoneProps 为 app 侧同源声明）
                        @Value("${teamone.ws.fanout-channel:teamone:ws:fanout}") String fanoutChannel,
                        @Value("${teamone.im.fast-fanout:true}") boolean enabled) {
        this.redis = redis;
        this.om = om;
        this.convMembers = convMembers;
        this.conversations = conversations;
        this.fanoutChannel = fanoutChannel;
        this.enabled = enabled;
    }

    /**
     * send 事务内调用：注册 afterCommit 回调，提交即直推（提交前绝不发帧——
     * 红线 3「先落库后推送」：收到帧的另一端无论如何回查都看得到已提交的行）。
     * 无事务同步（单测直调等）→ 静默跳过，交由 L2 事件链兜底。
     *
     * @param senderName 发送者展示名（V16 @提醒帧/通知摘要渲染用；MessageService 已查出，避免重复回查）
     */
    public void afterCommit(Message msg, String senderName) {
        if (!enabled) {
            return; // 基线对照开关：fast-fanout=false 退化回纯 L2 事件链（IT 测基线用）
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushNow(msg, senderName);
            }
        });
    }

    /** 直推 + 落标记；任何失败仅 WARN（红线 5）——事务已提交，事件链兜底 */
    private void pushNow(Message msg, String senderName) {
        try {
            Map<String, Object> payload = msg.toPayload();
            payload.put("senderName", senderName == null ? "" : senderName);
            publish("conv:" + msg.getConversationId(), "message.created", payload);
            fanoutUnread(msg.getConversationId().toString(), msg.getSenderId());
            fanoutNotify(msg, senderName);
            // 直推成功 → SETNX 短 TTL 标记（FanoutHandler 据此跳过 L2 补推）
            redis.opsForValue().setIfAbsent(PUSHED_KEY_PREFIX + msg.getId(), "1", PUSHED_TTL);
            log.debug("[im-fast-fanout] direct push done: conv={} msg={}",
                    msg.getConversationId(), msg.getId());
        } catch (Exception ex) {
            // 未落标记 → L2 事件链将照常补推（at-least-once）
            log.warn("[im-fast-fanout] direct push failed, degraded to event chain: conv={} msg={}: {}",
                    msg.getConversationId(), msg.getId(), ex.toString());
        }
    }

    /**
     * @提醒 notify 帧扇出（V16 R-10e）：mentions 非空时对每个被@成员 PUBLISH
     * user:{uid} 帧 kind='notify'（含会话/消息/摘要/mentions 标记）。发送者本人已被
     * MessageService.resolveMentions 剔除，此处不再重复过滤。
     */
    private void fanoutNotify(Message msg, String senderName) throws Exception {
        if (msg.getMentions() == null || msg.getMentions().isEmpty()) {
            return;
        }
        Map<String, Object> payload = msg.toPayload();
        payload.put("senderName", senderName == null ? "" : senderName);
        Map<String, Object> notify = buildNotifyPayload(payload);
        for (UUID uid : msg.getMentions()) {
            publish("user:" + uid, "notify", notify);
        }
    }

    /**
     * notify 帧载荷单一事实源（L3 直推 / MessageService 落库 / FanoutHandler L2 兜底共用）：
     * kind + 会话 id + 消息 id + 发送者 + 消息摘要（body 截断 60 字）+ mentions 标记 + 时间。
     */
    public static Map<String, Object> buildNotifyPayload(Map<String, Object> messagePayload) {
        Object body = messagePayload.get("body");
        String preview = body == null ? "" : String.valueOf(body);
        if (preview.length() > 60) {
            preview = preview.substring(0, 60);
        }
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("kind", "im.mention");
        notify.put("conversationId", Objects.toString(messagePayload.get("conversationId"), null));
        notify.put("msgId", messagePayload.get("msgId"));
        notify.put("senderId", Objects.toString(messagePayload.get("senderId"), null));
        notify.put("senderName", Objects.toString(messagePayload.get("senderName"), ""));
        notify.put("preview", preview);
        notify.put("mentions", messagePayload.get("mentions"));
        notify.put("createdAt", Objects.toString(messagePayload.get("createdAt"), null));
        return notify;
    }

    /**
     * 已读游标 read 帧扇出（V16 R-10 多端一致）：read 推进提交后 PUBLISH user:{uid}
     * kind='read' 帧——同用户其余端据 payload.unread 就地清零角标，无需整表重拉。
     * 由 WS handleRead / HTTP POST read 两个入口在 advanceRead 返回（事务已提交）后调用。
     * 失败仅 WARN（红线 5）：其余端经会话清单查询失效兜底最终一致。
     */
    public void pushReadFrame(UUID userId, UUID conversationId, long lastReadMessageId, long unread) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", conversationId.toString());
            payload.put("lastReadMessageId", lastReadMessageId);
            payload.put("unread", unread);
            publish("user:" + userId, "read", payload);
            log.debug("[im-fast-fanout] read frame published: user={} conv={} unread={}",
                    userId, conversationId, unread);
        } catch (Exception ex) {
            log.warn("[im-fast-fanout] read frame publish failed (degraded): user={} conv={}: {}",
                    userId, conversationId, ex.toString());
        }
    }

    /** S→C event 帧（§4.2 信封，与 FanoutHandler.publish 逐字段一致）；序列化失败由调用方统一降级 */
    private void publish(String ch, String kind, Object payload) throws Exception {
        ObjectNode frame = om.createObjectNode();
        frame.put("v", 1);
        frame.put("id", UUID.randomUUID().toString());
        frame.put("type", "event");
        frame.put("ts", System.currentTimeMillis());
        ObjectNode body = frame.putObject("payload");
        body.put("kind", kind);
        body.set("payload", om.valueToTree(payload));

        ObjectNode envelope = om.createObjectNode();
        envelope.put("ch", ch);
        envelope.set("frame", frame);

        redis.convertAndSend(fanoutChannel, om.writeValueAsString(envelope));
    }

    /**
     * 未读帧扇出（读侧真相，与 FanoutHandler.fanoutUnread 同口径）：
     * 成员=conversation_member 全集 − 发送者；unreadCount=0 不发。
     * 查询是会话清单同源 SQL（>游标、非本人发送、未撤回），绝读侧一致性。
     */
    private void fanoutUnread(String conversationId, UUID senderId) throws Exception {
        UUID cid;
        try {
            cid = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            return;
        }
        List<UUID> members = convMembers.findUserIdsByConversationId(cid);
        for (UUID uid : members) {
            if (uid.equals(senderId)) {
                continue; // 发送者本人不计未读
            }
            long unread = unreadCountOf(uid, cid);
            if (unread <= 0) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", conversationId);
            payload.put("unreadCount", unread);
            publish("user:" + uid, "unread", payload);
        }
    }

    /** 未读数 = 会话清单同源查询（findMineWithUnread 第 11 列 unread_count） */
    private long unreadCountOf(UUID userId, UUID conversationId) {
        for (Object[] row : conversations.findMineWithUnread(userId)) {
            if (conversationId.equals(row[0])) {
                return ((Number) row[10]).longValue();
            }
        }
        return 0;
    }
}
