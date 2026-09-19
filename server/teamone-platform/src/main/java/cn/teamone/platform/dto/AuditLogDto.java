package cn.teamone.platform.dto;

import cn.teamone.platform.audit.AuditLog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 操作审计日志数据传输契约。
 */
public class AuditLogDto {

    /**
     * 审计日志展示条目。
     *
     * @param id 日志唯一标识 UUID
     * @param actorId 操作人 UUID
     * @param actorName 操作人显示名称（若关联存在）
     * @param action 动作标识（如 user.create, data_migration.import 等）
     * @param resourceType 涉及资源类型
     * @param resourceId 涉及资源标识
     * @param detail 结构化详情 JSON
     * @param createdAt 事件产生时间戳
     */
    public record AuditLogEntry(
            UUID id,
            UUID actorId,
            String actorName,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> detail,
            Instant createdAt
    ) {
        public static AuditLogEntry fromEntity(AuditLog log, String actorName) {
            return new AuditLogEntry(
                    log.getId(),
                    log.getActorId(),
                    actorName,
                    log.getAction(),
                    log.getResourceType(),
                    log.getResourceId(),
                    log.getDetail(),
                    log.getCreatedAt()
            );
        }
    }
}
