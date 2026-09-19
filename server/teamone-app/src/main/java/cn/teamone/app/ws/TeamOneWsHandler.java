package cn.teamone.app.ws;

import cn.teamone.app.event.TeamoneProps;
import cn.teamone.collab.app.ConversationService;
import cn.teamone.collab.app.ImFastFanout;
import cn.teamone.collab.app.MessageService;
import cn.teamone.collab.repo.ConversationMemberRepository;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.app.auth.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WS 网关（W3-③b 升级版，协议=架构设计 §4；信令格式与 M0 保持一致）：
 *
 * <ul>
 *   <li><b>auth</b>：首帧 auth{token} → ready（未认证 10s 断开语义同 M0：非 auth 帧即断）；</li>
 *   <li><b>sub/unsub</b>：频道鉴权（§4.4）后加入本地路由——conv:{id}=会话成员（COL_4210）、
 *       user:{id}=仅本人（COL_4210）、gate:{releaseId}=product edit（PLT_4030，productId 在
 *       app 层查 release——红线 2）、未知前缀=PLT_4000；鉴权结果缓存 5min（简单 Map+TTL）；</li>
 *   <li><b>msg</b>：{@link MessageService#send} 事务落库+outbox 后才回 ack（红线 3），
 *       M0 echo 分支已删除；归档会话→COL_4203 err 帧（不关连接）、非成员→COL_4210；
 *       attachments 数组透传（ready 校验在服务层，INC-2 红线 4）；</li>
 *   <li><b>read</b>：游标推进（只前进）+未读重算 → ack{unread}（INC-2 T-3，
 *       越界 PLT_4000 / 非成员 COL_4210）；未知类型→err。</li>
 * </ul>
 *
 * <p>成员变更联动：{@link #onMembersChanged}（MembersChangedHandler 消费
 * conversation.members_changed）清 sub 鉴权缓存并重校验本地订阅表（§4.4）。</p>
 *
 * <p>扇出模型（红线 5/解耦）：本节点经 Valkey SUBSCRIBE teamone:ws:fanout（专用连接，
 * 见 {@link WsFanoutConfig}）收到 {ch,frame} 帧后只推本地订阅匹配者（含发送者自身）；
 * FanoutHandler 只做 PUBLISH，推送失败仅 WARN 不崩。</p>
 */
@Component
public class TeamOneWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TeamOneWsHandler.class);

    private static final String CH_CONV = "conv:";
    private static final String CH_USER = "user:";
    private static final String CH_GATE = "gate:";
    /** sub 鉴权决策缓存 TTL（§4.4：5min，ACL 决策同源兜底） */
    private static final long ACL_TTL_MS = Duration.ofMinutes(5).toMillis();

    private final ObjectMapper om;
    private final JwtService jwt;
    private final MessageService messages;
    private final ConversationService conversations;
    private final ImFastFanout fastFanout;
    private final ConversationMemberRepository convMembers;
    private final ReleaseRepository releases;
    private final PermissionService permissions;
    private final TeamoneProps props;

    /** 认证路由：session → userId */
    private final Map<WebSocketSession, UUID> authed = new ConcurrentHashMap<>();
    /** 用户路由（task 骨干要求：ready 后本地注册 Map<UUID,Set<WebSocketSession>>） */
    private final Map<UUID, Set<WebSocketSession>> routes = new ConcurrentHashMap<>();
    /** 频道路由：ch → 本节点订阅者（conv:/user:/gate: 统一） */
    private final Map<String, Set<WebSocketSession>> channelSubs = new ConcurrentHashMap<>();
    /** sub 鉴权决策缓存：key=ch|uid → decision+expiresAt（负缓存同样生效） */
    private final Map<String, AclDecision> aclCache = new ConcurrentHashMap<>();

    private record AclDecision(boolean allowed, ErrorCode errCode, String errMsg, long expiresAt) {
        static AclDecision allow() { return new AclDecision(true, null, null, 0); }
        static AclDecision deny(ErrorCode code, String msg) { return new AclDecision(false, code, msg, 0); }
        AclDecision withTtl(long ttlMs) {
            return new AclDecision(allowed, errCode, errMsg, System.currentTimeMillis() + ttlMs);
        }
    }

    public TeamOneWsHandler(ObjectMapper om, JwtService jwt, MessageService messages,
                            ConversationService conversations, ImFastFanout fastFanout,
                            ConversationMemberRepository convMembers, ReleaseRepository releases,
                            PermissionService permissions, TeamoneProps props) {
        this.om = om;
        this.jwt = jwt;
        this.messages = messages;
        this.conversations = conversations;
        this.fastFanout = fastFanout;
        this.convMembers = convMembers;
        this.releases = releases;
        this.permissions = permissions;
        this.props = props;
    }

    // ==================== 协议处理 ====================

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode frame;
        try {
            frame = om.readTree(message.getPayload());
        } catch (Exception e) {
            sendErrAndClose(session, ErrorCode.PLT_4000, "帧不是合法 JSON", null);
            return;
        }
        String type = frame.path("type").asText("");
        UUID userId = authed.get(session);

        if (userId == null) {
            if ("auth".equals(type)) {
                jwt.verifyAccess(frame.path("payload").path("token").asText("")).ifPresentOrElse(
                        uid -> {
                            authed.put(session, uid);
                            routes.computeIfAbsent(uid, k -> ConcurrentHashMap.newKeySet()).add(session);
                            send(session, Map.of("type", "ready", "payload",
                                    Map.of("userId", uid.toString(),
                                           "serverTime", System.currentTimeMillis())));
                            log.debug("ws authed: session={} user={}", session.getId(), uid);
                        },
                        () -> sendErrAndClose(session, ErrorCode.PLT_4010, "令牌无效", null));
            } else {
                sendErrAndClose(session, ErrorCode.PLT_4010, "连接未认证（首帧必须为 auth）", null);
            }
            return;
        }

        switch (type) {
            case "ping" -> send(session, Map.of("type", "pong"));
            case "sub" -> handleSub(session, frame, userId);
            case "unsub" -> handleUnsub(session, frame);
            case "msg" -> handleMsg(session, frame, userId);
            case "read" -> handleRead(session, frame, userId);
            default -> sendErr(session, ErrorCode.PLT_4000, "未知信令类型: " + type,
                    frame.path("id").asText(null));
        }
    }

    // ==================== sub / unsub ====================

    private void handleSub(WebSocketSession session, JsonNode frame, UUID userId) {
        String ch = frame.path("payload").path("ch").asText("");
        AclDecision decision = authorize(ch, userId);
        if (!decision.allowed()) {
            // 拒绝回 err 帧不关连接（§4.3：err{code,refId,message}）
            sendErr(session, decision.errCode(),
                    decision.errMsg() + " (" + ch + ")", frame.path("id").asText(null));
            return;
        }
        channelSubs.computeIfAbsent(ch, k -> ConcurrentHashMap.newKeySet()).add(session);
        send(session, Map.of("type", "ack", "payload", Map.of("of", "sub", "ch", ch)));
    }

    private void handleUnsub(WebSocketSession session, JsonNode frame) {
        String ch = frame.path("payload").path("ch").asText("");
        Set<WebSocketSession> subs = channelSubs.get(ch);
        if (subs != null) {
            subs.remove(session);
            if (subs.isEmpty()) {
                channelSubs.remove(ch, subs);
            }
        }
        send(session, Map.of("type", "ack", "payload", Map.of("of", "unsub", "ch", ch)));
    }

    /** 频道订阅鉴权（§4.4，默认拒绝；结果缓存 5min） */
    private AclDecision authorize(String ch, UUID userId) {
        String cacheKey = ch + "|" + userId;
        AclDecision cached = aclCache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached;
        }
        AclDecision decision = computeAuthorize(ch, userId).withTtl(ACL_TTL_MS);
        aclCache.put(cacheKey, decision);
        return decision;
    }

    private AclDecision computeAuthorize(String ch, UUID userId) {
        if (ch.startsWith(CH_CONV)) {
            UUID convId = parseUuid(ch.substring(CH_CONV.length()));
            if (convId == null) {
                return AclDecision.deny(ErrorCode.PLT_4000, "频道 id 非法");
            }
            return convMembers.existsByConversationIdAndUserId(convId, userId)
                    ? AclDecision.allow()
                    : AclDecision.deny(ErrorCode.COL_4210, ErrorCode.COL_4210.defaultMessage());
        }
        if (ch.startsWith(CH_USER)) {
            UUID target = parseUuid(ch.substring(CH_USER.length()));
            if (target == null) {
                return AclDecision.deny(ErrorCode.PLT_4000, "频道 id 非法");
            }
            return target.equals(userId)
                    ? AclDecision.allow()
                    : AclDecision.deny(ErrorCode.COL_4210, ErrorCode.COL_4210.defaultMessage());
        }
        if (ch.startsWith(CH_GATE)) {
            UUID releaseId = parseUuid(ch.substring(CH_GATE.length()));
            if (releaseId == null) {
                return AclDecision.deny(ErrorCode.PLT_4000, "频道 id 非法");
            }
            // gate 鉴权取 productId 在 app 层做（红线 2：collab 禁 import prd，WSHandler 在 app 包）
            var release = releases.findById(releaseId).orElse(null);
            if (release == null) {
                return AclDecision.deny(ErrorCode.PLT_4040, ErrorCode.PLT_4040.defaultMessage());
            }
            return permissions.check(userId, "product", release.getProductId(), "edit")
                    ? AclDecision.allow()
                    : AclDecision.deny(ErrorCode.PLT_4030, ErrorCode.PLT_4030.defaultMessage());
        }
        return AclDecision.deny(ErrorCode.PLT_4000, "未知频道前缀（支持 conv:/user:/gate:）");
    }

    // ==================== msg（事务落库 + outbox 才 ack） ====================

    private void handleMsg(WebSocketSession session, JsonNode frame, UUID userId) {
        JsonNode p = frame.path("payload");
        String ch = frame.hasNonNull("ch") ? frame.get("ch").asText() : p.path("ch").asText("");
        if (!ch.startsWith(CH_CONV)) {
            sendErr(session, ErrorCode.PLT_4000, "msg 仅支持 " + CH_CONV + "* 频道",
                    frame.path("id").asText(null));
            return;
        }
        UUID conversationId = parseUuid(ch.substring(CH_CONV.length()));
        if (conversationId == null) {
            sendErr(session, ErrorCode.PLT_4000, "频道 id 非法", frame.path("id").asText(null));
            return;
        }
        UUID clientMsgId = parseUuid(p.path("clientMsgId").asText(null));
        // @提醒成员清单（V16 R-10e，可选）：[{uuid}] 字符串数组；非法形状 → PLT_4000
        java.util.List<UUID> mentions = null;
        if (p.hasNonNull("mentions")) {
            try {
                mentions = om.<java.util.List<UUID>>convertValue(
                        p.get("mentions"),
                        om.getTypeFactory().constructCollectionType(java.util.List.class, UUID.class));
            } catch (IllegalArgumentException ex) {
                sendErr(session, ErrorCode.PLT_4000, "mentions 需为用户 id 数组",
                        frame.path("id").asText(null));
                return;
            }
        }
        try {
            // attachments 数组（INC-2 T-5）：[{fileId,...}]，ready 校验在服务层（红线 4）
            var attachments = p.hasNonNull("attachments")
                    ? om.<java.util.List<java.util.Map<String, Object>>>convertValue(
                        p.get("attachments"),
                        om.getTypeFactory().constructParametricType(
                                java.util.List.class,
                                om.getTypeFactory().constructParametricType(
                                        java.util.Map.class, String.class, Object.class)))
                    : null;
            var msg = messages.send(conversationId, userId, clientMsgId,
                    p.path("kind").asText("text"),
                    p.hasNonNull("body") ? p.get("body").asText() : null,
                    attachments,
                    mentions,
                    p.hasNonNull("refType") ? p.get("refType").asText() : null,
                    parseUuid(p.path("refId").asText(null)));
            // 红线 3：send() 返回即事务已提交（落库+outbox 原子）→ 此刻才 ack
            send(session, Map.of("type", "ack", "payload",
                    Map.of("clientMsgId", msg.getClientMsgId() == null ? "" : msg.getClientMsgId().toString(),
                           "msgId", msg.getId(),
                           "conversationId", conversationId.toString())));
        } catch (BusinessException ex) {
            // 归档 COL_4203 / 非成员 COL_4210 / 载荷或附件 PLT_4000 → err 帧不关连接
            sendErr(session, ex.errorCode(), ex.getMessage(), frame.path("id").asText(null));
        } catch (RuntimeException ex) {
            // MF-4：并发发送触发 Conversation @Version 乐观锁等未预期异常不能断连——
            // 回 err 帧（5xxx 用目录 SRV_5000；审查口径的 PLT_5000 目录未注册，红线 4 只引目录），
            // message 含 clientMsgId 供客户端定位重发
            log.warn("ws msg unexpected failure, conv={} clientMsgId={}: {}",
                    conversationId, clientMsgId, ex.toString());
            sendErr(session, ErrorCode.SRV_5000,
                    "消息处理失败，请重发（clientMsgId=" + clientMsgId + "）",
                    frame.path("id").asText(null));
        }
    }

    // ==================== read（INC-2 T-3 / R-10 帧修正：游标推进 + 未读重算 + 多端 read 帧） ====================

    /**
     * read 帧统一 schema（R-10a 定稿）：<code>{v:1, id, type:"read", ch:"conv:{id}", lastReadMessageId?}</code>
     * ——ch 顶层（payload.ch 兼容旧客户端）；lastReadMessageId 顶层（payload 兼容），
     * <b>缺省=读到最新</b>（R-10b：999999999 哨兵废除，服务端取会话 last_message_id）。
     * 成员/越界校验（服务层）→ 游标 upsert（GREATEST 只前进，幂等）→
     * ack{of:read, ch, lastReadMessageId, unread, refId}；随后向 user:{uid} 扇出 read 帧
     * （多端一致：同用户其余端就地清零角标）。越界/非成员 → err 帧（PLT_4000/COL_4210）不关连接。
     */
    private void handleRead(WebSocketSession session, JsonNode frame, UUID userId) {
        String ch = frame.hasNonNull("ch") ? frame.get("ch").asText()
                : frame.path("payload").path("ch").asText("");
        if (!ch.startsWith(CH_CONV)) {
            sendErr(session, ErrorCode.PLT_4000, "read 仅支持 " + CH_CONV + "* 频道",
                    frame.path("id").asText(null));
            return;
        }
        UUID conversationId = parseUuid(ch.substring(CH_CONV.length()));
        if (conversationId == null) {
            sendErr(session, ErrorCode.PLT_4000, "频道 id 非法", frame.path("id").asText(null));
            return;
        }
        String refId = frame.path("id").asText(null);
        try {
            Long lastReadMessageId = parseLastReadMessageId(frame);
            var result = conversations.advanceRead(userId, conversationId, lastReadMessageId);
            // ack 带 refId（请求帧 id）：前端 waiter 按其路由，err/超时可区分归属
            Map<String, Object> ack = new java.util.LinkedHashMap<>();
            ack.put("of", "read");
            ack.put("ch", ch);
            ack.put("lastReadMessageId", result.lastReadMessageId());
            ack.put("unread", result.unread());
            if (refId != null) {
                ack.put("refId", refId);
            }
            send(session, Map.of("type", "ack", "payload", ack));
            // 多端一致（QA 矩阵）：advanceRead 返回即事务已提交 → 向本用户全部端扇出 read 帧
            fastFanout.pushReadFrame(userId, conversationId, result.lastReadMessageId(), result.unread());
        } catch (BusinessException ex) {
            sendErr(session, ex.errorCode(), ex.getMessage(), refId);
        } catch (RuntimeException ex) {
            log.warn("ws read unexpected failure, conv={} user={}: {}",
                    conversationId, userId, ex.toString());
            sendErr(session, ErrorCode.SRV_5000, "已读处理失败，请重试", refId);
        }
    }

    /**
     * 解析 read 帧游标：顶层 lastReadMessageId 优先、payload 同名字段兼容旧客户端；
     * 字段缺省/null → null（读到最新）；非数字 → PLT_4000。
     */
    private Long parseLastReadMessageId(JsonNode frame) {
        JsonNode top = frame.get("lastReadMessageId");
        JsonNode nested = frame.path("payload").get("lastReadMessageId");
        JsonNode v = top != null && !top.isNull() ? top : nested;
        if (v == null || v.isNull()) {
            return null; // 缺省语义=读到最新（R-10b）
        }
        if (!v.isNumber()) {
            throw new BusinessException(ErrorCode.PLT_4000, "lastReadMessageId 需为整数");
        }
        return v.asLong();
    }

    // ==================== 扇出（WsFanoutConfig listener 回调） ====================

    /**
     * 推送 fanout 帧给本节点内订阅了 ch 的会话（含发送者自身）。
     * 线程模型：Valkey listener 线程调用；发送失败仅 WARN（红线 5，不崩不重试——
     * 订阅者自会经 REST 离线补偿）。
     */
    public void pushToChannel(String ch, String frameJson) {
        Set<WebSocketSession> subs = channelSubs.get(ch);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        for (WebSocketSession session : subs) {
            sendRaw(session, frameJson);
        }
        log.debug("ws fanout pushed: ch={} localSubs={}", ch, subs.size());
    }

    // ==================== 成员变更：鉴权缓存失效 + 订阅表重校验（05 §4.4） ====================

    /**
     * 会话成员变更后的本地清理（MembersChangedHandler 消费 conversation.members_changed 触发）：
     * ①清该会话全部 sub 鉴权缓存（含负缓存——新成员被 4210 缓存挡 5min 的窗口即此消除）；
     * ②强制重校验本地订阅表：被移出者即使已订阅也即刻失效（§4.4「成员变更触发节点本地订阅表清理」）。
     */
    public void onMembersChanged(UUID conversationId) {
        String prefix = CH_CONV + conversationId + "|";
        aclCache.keySet().removeIf(key -> key.startsWith(prefix));
        enforceConvSubscriptions(conversationId);
        log.debug("ws members changed: conv={} authz cache cleared, subs re-validated", conversationId);
    }

    /** 本地 conv:{id} 订阅者逐个重查成员资格，非成员摘除（发送侧仅 WARN，收不到即生效） */
    private void enforceConvSubscriptions(UUID conversationId) {
        Set<WebSocketSession> subs = channelSubs.get(CH_CONV + conversationId);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        for (WebSocketSession session : new java.util.ArrayList<>(subs)) {
            UUID userId = authed.get(session);
            if (userId != null
                    && !convMembers.existsByConversationIdAndUserId(conversationId, userId)) {
                subs.remove(session);
                log.debug("ws sub revoked (non-member): conv={} user={}", conversationId, userId);
            }
        }
        if (subs.isEmpty()) {
            channelSubs.remove(CH_CONV + conversationId, subs);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("ws connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = authed.remove(session);
        if (userId != null) {
            Set<WebSocketSession> route = routes.get(userId);
            if (route != null) {
                route.remove(session);
                if (route.isEmpty()) {
                    routes.remove(userId, route);
                }
            }
        }
        channelSubs.values().forEach(subs -> subs.remove(session));
        channelSubs.values().removeIf(Set::isEmpty);
        log.debug("ws closed: session={} user={}", session.getId(), userId);
    }

    // ==================== 发送原语 ====================

    private void send(WebSocketSession session, Map<?, ?> frame) {
        try {
            sendRaw(session, om.writeValueAsString(frame));
        } catch (IOException e) {
            log.warn("ws serialize failed: {}", e.getMessage());
        }
    }

    private void sendRaw(WebSocketSession session, String frameJson) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(frameJson));
            }
        } catch (IOException | IllegalStateException e) {
            // 红线 5：WS 推送失败仅 WARN（会话半关闭/并发发送越界），不崩不重试
            log.warn("ws send failed (session={}): {}", session.getId(), e.getMessage());
        }
    }

    private void sendErr(WebSocketSession session, ErrorCode ec, String message, String refId) {
        Map<String, Object> payload = refId == null
                ? Map.of("code", ec.code(), "message", message)
                : Map.of("code", ec.code(), "message", message, "refId", refId);
        send(session, Map.of("type", "err", "payload", payload));
    }

    private void sendErrAndClose(WebSocketSession session, ErrorCode ec, String message, String refId) {
        sendErr(session, ec, message, refId);
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
