package cn.teamone.collab.api;

import cn.teamone.collab.app.ConversationQueryService;
import cn.teamone.collab.app.ConversationService;
import cn.teamone.collab.app.ImFastFanout;
import cn.teamone.collab.app.MessageService;
import cn.teamone.collab.domain.Message;
import cn.teamone.collab.dto.MessageReadersDto;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话端点（05 §3.3 collab 表）：读侧在 {@link ConversationQueryService}；
 * M2-INC-2 T-2/T-4 写侧（建 dm/group、成员管理、撤回）在 {@link ConversationService}/
 * {@link MessageService}（@Transactional，Controller 薄）。
 *
 * <ul>
 *   <li><b>POST /conversations</b>：{type:dm,peerId}（dedup_key 防裂，existing=true 幂等返回
 *       既有）| {type:group,name,memberIds[]}（creator=owner）| topic 拒绝（自动建题专用）；</li>
 *   <li><b>POST /{id}/members · DELETE /{id}/members/{uid}</b>：仅 group（owner 加人；
 *       本人退群/owner 移除；owner 不可退）；成员变更 outbox 事件驱动 WS 鉴权缓存失效；</li>
 *   <li><b>POST /{id}/messages/{msgId}/withdraw</b>：撤回（软删+灰条，S-6）；事务提交后
 *       message.withdrawn L3 直接扇出（沿 gate 推送先例：PUBLISH fanout {ch,frame}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationQueryService queries;
    private final ConversationService conversations;
    private final MessageService messages;
    private final ImFastFanout fastFanout;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final long withdrawWindowHours;
    private final String fanoutChannel;

    public ConversationController(ConversationQueryService queries,
                                  ConversationService conversations,
                                  MessageService messages,
                                  ImFastFanout fastFanout,
                                  @Qualifier("stringRedisTemplate") StringRedisTemplate redis,
                                  ObjectMapper om,
                                  // collab 不可依赖 app（ArchUnit R4/R7），同键 @Value 绑定
                                  // teamone.im.withdraw-window-hours（TeamoneProps.Im 为 app 侧同源声明）
                                  @Value("${teamone.im.withdraw-window-hours:24}") long withdrawWindowHours,
                                  @Value("${teamone.ws.fanout-channel:teamone:ws:fanout}") String fanoutChannel) {
        this.queries = queries;
        this.conversations = conversations;
        this.messages = messages;
        this.fastFanout = fastFanout;
        this.redis = redis;
        this.om = om;
        this.withdrawWindowHours = withdrawWindowHours;
        this.fanoutChannel = fanoutChannel;
    }

    // ==================== 读侧（W3-④ 既有） ====================

    /** 会话清单：本人参与，type 归类过滤 + archived 折叠 + unreadCount（INC-2 一次 SQL），lastMessageAt DESC */
    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AppUser me,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) Boolean archived) {
        return Map.of("items", queries.listMine(me.getId(), type, archived));
    }

    /** 会话详情（ImPage 右栏干系人）：含 members 用户 id 清单；非成员 COL_4210 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@AuthenticationPrincipal AppUser me, @PathVariable UUID id) {
        return queries.detail(me.getId(), id);
    }

    /**
     * 历史消息（WS 离线补偿同接口）：after=messageId 游标升序、before=messageId 反向游标降序
     * （M2-INC-1 W1 清账，与 after 互斥；before=latest 表示尾窗最新一页）、limit 上限 200、hasMore 翻页。
     */
    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@AuthenticationPrincipal AppUser me,
                                        @PathVariable UUID id,
                                        @RequestParam(required = false) Long after,
                                        @RequestParam(required = false) String before,
                                        @RequestParam(defaultValue = "50") int limit) {
        return queries.messages(me.getId(), id, after, parseBefore(before), limit);
    }

    /**
     * 消息已读回执明细（Direction 4 Phase 4 · IM 读回执能力）。
     *
     * <p>查询群聊/话题中指定消息的已读与未读成员列表、已读比例与全员阅毕标识，
     * 供前端弹窗或浮窗展示「已读 X / 未读 Y」并下钻查看具体人员姓名与阅读时间。</p>
     *
     * @param me    当前登录用户（@AuthenticationPrincipal 注入）
     * @param id    会话 UUID
     * @param msgId 消息自增 ID
     * @return {@link MessageReadersDto} 包含 sender、readers、unreaders 及统计字段
     */
    @GetMapping("/{id}/messages/{msgId}/readers")
    public MessageReadersDto readers(@AuthenticationPrincipal AppUser me,
                                     @PathVariable UUID id,
                                     @PathVariable Long msgId) {
        return queries.getMessageReaders(me.getId(), id, msgId);
    }

    // ==================== 写侧（INC-2 T-2/T-4） ====================

    /** 侧栏 IM 总角标聚合（R-10d）：findMineWithUnread 同口径 SUM 一层，与 GET /conversations 求和恒等 */
    @GetMapping("/unread-summary")
    public Map<String, Object> unreadSummary(@AuthenticationPrincipal AppUser me) {
        return Map.of("total", queries.unreadSummary(me.getId()));
    }

    /** HTTP 已读请求体：lastReadMessageId 可选——缺省=读到最新（R-10b，哨兵 999999999 废除） */
    public record MarkReadReq(Long lastReadMessageId) {}

    /**
     * HTTP 已读端点（R-10a 补偿与协议测试通道）：与 WS handleRead 同走
     * {@link ConversationService#advanceRead}（游标只前进/越界拒绝/缺省读到最新同源）；
     * 权限=会话成员（服务层校验）。事务提交后向 user:{uid} 扇出 read 帧（多端一致）。
     */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@AuthenticationPrincipal AppUser me,
                                        @PathVariable UUID id,
                                        @RequestBody(required = false) MarkReadReq req) {
        ConversationService.ReadResult result =
                conversations.advanceRead(me.getId(), id, req == null ? null : req.lastReadMessageId());
        // advanceRead 返回即事务已提交（红线 3）→ 此刻才扇出 read 帧
        fastFanout.pushReadFrame(me.getId(), id, result.lastReadMessageId(), result.unread());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("conversationId", id.toString());
        res.put("lastReadMessageId", result.lastReadMessageId());
        res.put("unread", result.unread());
        return res;
    }

    /** 建会话请求：dm 用 peerId；group 用 name+memberIds；topic 无手工创建路径 */
    public record CreateConvReq(String type, UUID peerId, String name, List<UUID> memberIds) {}

    @PostMapping
    public Map<String, Object> create(@AuthenticationPrincipal AppUser me,
                                      @RequestBody CreateConvReq req) {
        String type = req.type() == null ? "" : req.type();
        return switch (type) {
            case "dm" -> conversations.createDm(me.getId(), req.peerId());
            case "group" -> conversations.createGroup(me.getId(), req.name(), req.memberIds());
            case "topic" -> throw new BusinessException(ErrorCode.PLT_4000,
                    "topic 会话由系统随对象自动建题，不支持手工创建");
            default -> throw new BusinessException(ErrorCode.PLT_4000,
                    "type 需为 dm|group（topic 为自动建题专用）");
        };
    }

    /** 加人请求体 */
    public record AddMembersReq(List<UUID> userIds) {}

    /** 成员管理：仅 group 且 owner（dm/topic PLT_4000） */
    @PostMapping("/{id}/members")
    public Map<String, Object> addMembers(@AuthenticationPrincipal AppUser me,
                                          @PathVariable UUID id,
                                          @RequestBody AddMembersReq req) {
        List<UUID> added = conversations.addMembers(me.getId(), id, req.userIds());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("added", added);
        return res;
    }

    /** 退群/移除：本人退群或 owner 移除；owner 不可退（简化规则，PLT_4000） */
    @DeleteMapping("/{id}/members/{uid}")
    public Map<String, Object> removeMember(@AuthenticationPrincipal AppUser me,
                                            @PathVariable UUID id,
                                            @PathVariable UUID uid) {
        conversations.removeMember(me.getId(), id, uid);
        return Map.of("removed", uid.toString());
    }

    /**
     * 撤回（INC-2 T-4）：仅 sender 本人；system 拒绝；超窗 COL_4209；撤回=脱敏非删除。
     * 事务提交后（service 返回即提交）L3 直接扇出 message.withdrawn；Valkey 不可用 WARN 降级。
     */
    @PostMapping("/{id}/messages/{msgId}/withdraw")
    public Map<String, Object> withdraw(@AuthenticationPrincipal AppUser me,
                                        @PathVariable UUID id,
                                        @PathVariable Long msgId) {
        Message msg = messages.withdraw(id, msgId, me.getId(), withdrawWindowHours);
        publishWithdrawn(msg, me.getId());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("msgId", msg.getId());
        res.put("conversationId", id);
        res.put("withdrawn", true);
        res.put("withdrawnAt", msg.getWithdrawnAt() == null ? null : msg.getWithdrawnAt().toString());
        return res;
    }

    /**
     * message.withdrawn L3 直接推（事务已提交；帧形状沿 FanoutHandler 的
     * {ch, frame:{v,id,type:event,ts,payload:{kind,payload}}} envelope）。
     * 失败仅 WARN（红线 5）——订阅端经 GET messages 离线补偿最终一致。
     */
    private void publishWithdrawn(Message msg, UUID operatorId) {
        try {
            ObjectNode frame = om.createObjectNode();
            frame.put("v", 1);
            frame.put("id", UUID.randomUUID().toString());
            frame.put("type", "event");
            frame.put("ts", System.currentTimeMillis());
            ObjectNode body = frame.putObject("payload");
            body.put("kind", "message.withdrawn");
            ObjectNode payload = body.putObject("payload");
            payload.put("conversationId", msg.getConversationId().toString());
            payload.put("msgId", msg.getId());
            payload.put("operatorId", operatorId.toString());
            if (msg.getWithdrawnAt() != null) {
                payload.put("withdrawnAt", msg.getWithdrawnAt().toString());
            }
            ObjectNode envelope = om.createObjectNode();
            envelope.put("ch", "conv:" + msg.getConversationId());
            envelope.set("frame", frame);
            redis.convertAndSend(fanoutChannel, om.writeValueAsString(envelope));
            log.debug("[collab] message.withdrawn published: conv={} msg={}",
                    msg.getConversationId(), msg.getId());
        } catch (Exception ex) {
            log.warn("[collab] message.withdrawn fanout failed (degraded): conv={} msg={} {}",
                    msg.getConversationId(), msg.getId(), ex.toString());
        }
    }

    /** before 游标解析：空=null（未传）；"latest"=Long.MAX_VALUE（尾窗）；其余必须为数字 */
    private static Long parseBefore(String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        if ("latest".equalsIgnoreCase(before.trim())) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(before.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "before 需为 messageId 或 latest: " + before);
        }
    }
}
