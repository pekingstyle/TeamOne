package cn.teamone.collab.api;

import cn.teamone.collab.domain.Notification;
import cn.teamone.collab.repo.NotificationRepository;
import cn.teamone.platform.domain.AppUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 站内通知收件箱（05 §3.3 /api/v1/notifications；登录态，只查/改本人数据）。
 *
 * <p>M1 口径：通知只落库不经 WS——前端轮询本端点。unread=true（默认）只看未读
 * （V5 部分索引 idx_notification_unread 命中）；PUT read 按 ids 或 all 推进已读
 * （只前进不回退：read_at IS NULL 守卫，且仅限本人行）。</p>
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public record ReadReq(List<UUID> ids, Boolean all) {}

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AppUser me,
                                    @RequestParam(defaultValue = "true") boolean unread,
                                    @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        Pageable page = PageRequest.of(0, safeLimit);
        var items = (unread
                ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(me.getId(), page)
                : notifications.findByUserIdOrderByCreatedAtDesc(me.getId(), page)).stream()
                .map(NotificationController::view)
                .toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("unreadCount", notifications.countByUserIdAndReadAtIsNull(me.getId()));
        res.put("items", items);
        return res;
    }

    @PutMapping("/read")
    @Transactional
    public Map<String, Object> markRead(@AuthenticationPrincipal AppUser me,
                                        @RequestBody ReadReq req) {
        int updated;
        if (Boolean.TRUE.equals(req.all())) {
            updated = notifications.markAllRead(me.getId());
        } else {
            List<UUID> ids = req.ids() == null ? new ArrayList<>() : req.ids();
            if (ids.isEmpty()) {
                return Map.of("updated", 0);
            }
            updated = notifications.markRead(me.getId(), ids);
        }
        return Map.of("updated", updated);
    }

    private static Map<String, Object> view(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("kind", n.getKind());
        m.put("payload", n.getPayload());
        m.put("readAt", n.getReadAt());
        m.put("createdAt", n.getCreatedAt());
        return m;
    }
}
