package cn.teamone.collab.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.teamone.collab.domain.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 延迟归档扫描器（05 §6.2 / 06 §3 W3：每分钟扫描 sched:topic-archive）。
 *
 * <p>到期（score≤now）→ archived_at=now + archived_reason='target_closed' → 移出 ZSET。
 * 幂等：已归档会话直接跳过并照样移出（ZSET 残留自清理）；重复执行无副作用。</p>
 *
 * <p>Valkey 不可用（红线 5）：整轮降级 WARN + 退避（节流 30s），下轮调度重试，
 * 不崩溃循环、不耦合 health。</p>
 */
@Component
@ConditionalOnProperty(name = "teamone.event.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveSweeper {

    private static final Logger log = LoggerFactory.getLogger(ArchiveSweeper.class);
    private static final long WARN_INTERVAL_MS = 30_000;

    private final ConversationService conversations;

    private volatile long lastWarnAt = 0L;

    public ArchiveSweeper(ConversationService conversations) {
        this.conversations = conversations;
    }

    /** 60s 一轮（@EnableScheduling 已由 app EventBackboneConfig 开启） */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void sweep() {
        try {
            long now = System.currentTimeMillis();
            int archived = 0;
                for (String member : conversations.dueArchiveCandidates(now)) {
                    // member = convId 或 convId|reason（M2-INC-1 W2 起携带原因，UUID 不含 '|'）
                    int sep = member.indexOf('|');
                    String idPart = sep >= 0 ? member.substring(0, sep) : member;
                    String reason = sep >= 0 ? member.substring(sep + 1) : null;
                    UUID conversationId = null;
                    try {
                        conversationId = UUID.fromString(idPart);
                    } catch (IllegalArgumentException ex) {
                        log.warn("[collab-archive] bad zset member dropped: {}", member); // 防御：坏成员清出
                    }
                    if (conversationId != null && conversations.markArchived(conversationId,
                            reason == null ? Conversation.ARCHIVE_REASON_TARGET_CLOSED : reason)) {
                        archived++;
                        log.info("[collab-archive] archived conv={} reason={}", conversationId,
                                reason == null ? "target_closed" : reason);
                    }
                    conversations.removeArchiveCandidate(member);
                }
            if (archived > 0) {
                log.info("[collab-archive] sweep done, archived={} at now={}", archived, now);
            }
        } catch (DataAccessException ex) {
            warnThrottled("[collab-archive] valkey unavailable, sweep skipped: " + ex.getMessage());
        }
    }

    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_INTERVAL_MS) {
            lastWarnAt = now;
            log.warn(message);
        }
    }
}
