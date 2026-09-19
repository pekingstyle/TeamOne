package cn.teamone.app.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.teamone.collab.app.ImFastFanout;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.collab.repo.ConversationRepository;
import cn.teamone.shared.event.W3EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WS 扇出消费者（W3-③b，权威目录 §5.1）：
 *
 * <ul>
 *   <li>message.created → PUBLISH fanout {ch:"conv:{cid}", frame:event{kind,message payload}}
 *       （帧给订阅匹配者，含发送者自身）；另对会话其他成员逐个 PUBLISH user:{uid} 帧
 *       {kind:'unread',payload:{conversationId,unreadCount}}（M2-W4 P1-3 未读实时推送——
 *       未选会话的端不订阅 conv 频道，徽标靠 user 频道失效重查；offline 成员无本地 session，
 *       pushToChannel 自然 no-op，无需在线名单）；</li>
 *   <li>defect.blocked_changed → PUBLISH fanout {ch:"gate:{releaseId}"，
 *       frame:event{kind:defect.blocked_changed,payload}}（V-4 门禁频道验收）；</li>
 *   <li>conflict.detected（M2-INC-1 W2 增订）→ PUBLISH fanout {ch:"user:{userId}"，
 *       frame:event{kind:conflict.detected,payload{userId,redCount}}}——红色冲突 5 分钟内
 *       推送当事人（02 FR-v2-04），user:{uid} 仅本人可订（§4.4）。</li>
 * </ul>
 *
 * <p>解耦红线：本 Handler 只做 PUBLISH——各 WS 节点的 listener 收到后推各自本地
 * session（与 Handler 执行解耦，EventConsumer 事务外）。PUBLISH 失败（Valkey 不可用）
 * → 异常上抛 → EventConsumer 不 ACK 留 PEL 重投（至少一次；订阅端按 ch 幂等渲染）。
 * collab 只读仓注入：app 组合根依赖 collab（R4 反向禁止不涉及），仅读成员/未读，零写。</p>
 *
 * <p>L3 直推分工（IM 延迟诊断批）：message.created 的在线推送已由 collab
 * {@code ImFastFanout} 在发送事务 afterCommit 直推（05 §推拉结合：WS 在线推送=L3 加速器，
 * 在线用户不等消费轮询）；本 Handler 对带直推标记的消息跳过重复扇出，其余消息
 * （直推失败降级/历史事件重放）照常补推——L2 链路整体退化为可靠兜底通道。</p>
 */
@Component
@ConditionalOnProperty(name = "teamone.event.enabled", havingValue = "true", matchIfMissing = true)
public class FanoutHandler implements W3EventHandler {

    private static final Logger log = LoggerFactory.getLogger(FanoutHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final TeamoneProps props;
    private final ConversationMemberRepository convMembers;
    private final ConversationRepository conversations;

    public FanoutHandler(@Qualifier("stringRedisTemplate") StringRedisTemplate redis,
                         TeamoneProps props,
                         ConversationMemberRepository convMembers,
                         ConversationRepository conversations) {
        this.redis = redis;
        this.props = props;
        this.convMembers = convMembers;
        this.conversations = conversations;
    }

    @Override
    public Set<String> types() {
        return Set.of("message.created", "defect.blocked_changed", "conflict.detected");
    }

    @Override
    public void handle(JsonNode payload) {
        String ch;
        String kind;
        if (payload.hasNonNull("msgId") && payload.hasNonNull("conversationId")) {
            ch = "conv:" + payload.get("conversationId").asText(); // message.created
            kind = "message.created";
        } else if (payload.hasNonNull("userId") && payload.hasNonNull("redCount")) {
            ch = "user:" + payload.get("userId").asText();         // conflict.detected（W2 红色冲突推送）
            kind = "conflict.detected";
        } else if (payload.hasNonNull("releaseId")) {
            ch = "gate:" + payload.get("releaseId").asText();      // defect.blocked_changed
            kind = "defect.blocked_changed";
        } else {
            log.warn("[ws-fanout] unknown fanout payload shape, dropped");
            return;
        }

        // L3 直推去重（IM 延迟诊断批）：发送事务 afterCommit 已直推的 message.created
        // （collab ImFastFanout 落 teamone:im:pushed:{msgId} 短 TTL 标记）→ 跳过重复扇出，
        // 事件照常走完（落 processed_event + ACK）——L2 链路退化为落库类副作用的兜底通道。
        // 检查异常（Valkey 抖动）按未推处理走原链路：最坏双推一次，订阅端按 msgId 幂等渲染。
        if ("message.created".equals(kind) && alreadyPushedDirectly(payload.path("msgId").asLong(0))) {
            log.debug("[ws-fanout] skipped, already direct-pushed (L3): conv={} msgId={}",
                    payload.get("conversationId").asText(), payload.path("msgId").asLong());
            return;
        }

        publish(ch, kind, payload);

        // message.created 附加：会话其他成员的未读实时帧（M2-W4 P1-3）
        if ("message.created".equals(kind)) {
            fanoutUnread(payload.get("conversationId").asText(), payload.get("senderId").asText(null));
            // @提醒 notify 帧兜底（V16 R-10e）：L3 直推失败（无 pushed 标记走到这里）时，
            // 按 payload.mentions 逐个补推 user:{uid} notify 帧；@提醒 notification 行
            // 已由 MessageService 在发送事务内落库（先落库后推送），本 Handler 只补帧不写行。
            fanoutNotifyFallback(payload);
        }
    }

    /** 直推标记检查（键源=collab ImFastFanout.PUSHED_KEY_PREFIX，app→collab 方向合法）；查询失败视为未推 */
    private boolean alreadyPushedDirectly(long msgId) {
        if (msgId <= 0) {
            return false;
        }
        try {
            Boolean hit = redis.hasKey(ImFastFanout.PUSHED_KEY_PREFIX + msgId);
            return Boolean.TRUE.equals(hit);
        } catch (Exception ex) {
            log.debug("[ws-fanout] pushed-marker check failed, fallback to chain publish: {}", ex.toString());
            return false;
        }
    }

    /** S→C event 帧（§4.2 信封）：{v,id,type:event,ts,payload:{kind,payload}} 包 fanout 信封 */
    private void publish(String ch, String kind, JsonNode payload) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("v", 1);
        frame.put("id", UUID.randomUUID().toString());
        frame.put("type", "event");
        frame.put("ts", System.currentTimeMillis());
        ObjectNode body = frame.putObject("payload");
        body.put("kind", kind);
        body.set("payload", payload);

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("ch", ch);
        envelope.set("frame", frame);

        redis.convertAndSend(props.getWs().getFanoutChannel(), envelope.toString());
        log.debug("[ws-fanout] published: ch={} kind={}", ch, kind);
    }

    /**
     * 未读帧扇出（读侧真相复用会话清单的未读口径）：
     * 成员=collab.conversation_member 全集 − 发送者；unreadCount=0 不发（避免空推）。
     * 在线与否由各 WS 节点 pushToChannel 兜底：无 session 的成员 PUBLISH 到达后无人匹配即丢弃。
     */
    private void fanoutUnread(String conversationId, String senderId) {
        UUID cid;
        try {
            cid = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            return;
        }
        List<UUID> members = convMembers.findUserIdsByConversationId(cid);
        for (UUID uid : members) {
            if (uid.toString().equals(senderId)) {
                continue; // 发送者本人不计未读
            }
            long unread = unreadCountOf(uid, cid);
            if (unread <= 0) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", conversationId);
            payload.put("unreadCount", unread);
            publish("user:" + uid, "unread", MAPPER.valueToTree(payload));
        }
    }

    /**
     * 未读数 = 会话清单同源查询（>游标、非本人发送、未撤回），取该会话行的 unread_count
     */
    private long unreadCountOf(UUID userId, UUID conversationId) {
        for (Object[] row : conversations.findMineWithUnread(userId)) {
            if (conversationId.equals(row[0])) {
                return ((Number) row[10]).longValue(); // 第 11 列 = unread_count（见 ConversationRepository 投影）
            }
        }
        return 0;
    }

    /**
     * @提醒 notify 帧补推（V16 R-10e）：payload.mentions 非空 → 逐成员 PUBLISH
     * user:{uid} kind='notify' 帧；载荷与 L3 直推同源（{@link ImFastFanout#buildNotifyPayload}，
     * 单一事实源防帧形状漂移）。失败上抛 → EventConsumer 不 ACK 留 PEL 重投（至少一次）。
     */
    private void fanoutNotifyFallback(JsonNode payload) {
        JsonNode mentions = payload.get("mentions");
        if (mentions == null || !mentions.isArray() || mentions.isEmpty()) {
            return; // 无 @提醒（V16 之前的消息/普通消息）：零开销直通
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> messagePayload =
                MAPPER.convertValue(payload, java.util.Map.class);
        Map<String, Object> notify = ImFastFanout.buildNotifyPayload(messagePayload);
        for (JsonNode uid : mentions) {
            publish("user:" + uid.asText(), "notify", MAPPER.valueToTree(notify));
        }
        log.debug("[ws-fanout] mention notify frames published: conv={} mentions={}",
                payload.path("conversationId").asText(), mentions.size());
    }
}
