package cn.teamone.app.ws;

import cn.teamone.app.auth.JwtService;
import cn.teamone.shared.api.ErrorCode;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WS 网关（M0：echo 骨架）。协议（架构设计 §4）：
 * 首帧必须 auth{token} → ready；ping → pong；msg{clientMsgId,body} → ack + event(echo)。
 * 未认证前任何其他帧 → err(T1-PLT-4010) 并关闭连接。
 */
@Component
public class TeamOneWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TeamOneWsHandler.class);

    private final ObjectMapper om;
    private final JwtService jwt;
    private final Map<WebSocketSession, UUID> authed = new ConcurrentHashMap<>();

    public TeamOneWsHandler(ObjectMapper om, JwtService jwt) {
        this.om = om;
        this.jwt = jwt;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode frame;
        try {
            frame = om.readTree(message.getPayload());
        } catch (Exception e) {
            sendErrAndClose(session, ErrorCode.PLT_4000, "帧不是合法 JSON");
            return;
        }
        String type = frame.path("type").asText("");
        UUID userId = authed.get(session);

        if (userId == null) {
            if ("auth".equals(type)) {
                jwt.verifyAccess(frame.path("payload").path("token").asText("")).ifPresentOrElse(
                        uid -> {
                            authed.put(session, uid);
                            send(session, Map.of("type", "ready", "payload", Map.of("userId", uid.toString())));
                        },
                        () -> sendErrAndClose(session, ErrorCode.PLT_4010, "令牌无效"));
            } else {
                sendErrAndClose(session, ErrorCode.PLT_4010, "连接未认证（首帧必须为 auth）");
            }
            return;
        }

        switch (type) {
            case "ping" -> send(session, Map.of("type", "pong"));
            case "msg" -> {
                String clientMsgId = frame.path("payload").path("clientMsgId").asText("");
                send(session, Map.of("type", "ack", "payload",
                        Map.of("clientMsgId", clientMsgId, "msgId", 0, "echo", true)));
                send(session, Map.of("type", "event", "payload",
                        Map.of("kind", "echo", "from", userId.toString(),
                                "body", frame.path("payload").path("body").asText("")),
                        "id", UUID.randomUUID().toString()));
            }
            case "sub", "unsub", "read" -> send(session, Map.of("type", "ack",
                    "payload", Map.of("of", type))); // M0 占位：M1 接频道鉴权与游标
            default -> sendErrAndClose(session, ErrorCode.PLT_4000, "未知信令类型: " + type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        authed.remove(session);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("ws connected: {}", session.getId());
    }

    private void send(WebSocketSession session, Map<?, ?> frame) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(om.writeValueAsString(frame)));
            }
        } catch (IOException e) {
            log.warn("ws send failed: {}", e.getMessage());
        }
    }

    private void sendErrAndClose(WebSocketSession session, ErrorCode ec, String message) {
        send(session, Map.of("type", "err", "payload", Map.of("code", ec.code(), "message", message)));
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
        }
    }
}
