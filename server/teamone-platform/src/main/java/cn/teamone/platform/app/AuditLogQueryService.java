package cn.teamone.platform.app;

import cn.teamone.platform.audit.AuditLog;
import cn.teamone.platform.audit.AuditLogRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.dto.AuditLogDto.AuditLogEntry;
import cn.teamone.platform.repo.AppUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 操作审计日志查询服务（P0 企业治理底座 · 安全审计可视化大盘数据源）。
 *
 * <p>以只读方式分页检索 {@code audit.audit_log} 表，并自动聚合关联操作人的显示名称。</p>
 */
@Service
public class AuditLogQueryService {

    private final AuditLogRepository auditRepo;
    private final AppUserRepository userRepo;

    public AuditLogQueryService(AuditLogRepository auditRepo, AppUserRepository userRepo) {
        this.auditRepo = auditRepo;
        this.userRepo = userRepo;
    }

    /**
     * 分页查询全局操作审计事件流。
     *
     * @param pageable 分页参数（默认按 created_at DESC 排序）
     * @return 分页审计日志视图（带操作人姓名补全）
     */
    @Transactional(readOnly = true)
    public Page<AuditLogEntry> listAuditLogs(Pageable pageable) {
        // 强制确保按时间倒序
        Pageable sortedPage = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<AuditLog> page = auditRepo.findAll(sortedPage);

        // 收集该页全部 actorId，批量查询用户名称避免 N+1
        Set<UUID> actorIds = page.getContent().stream()
                .map(AuditLog::getActorId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<UUID, String> actorNameMap = new HashMap<>();
        if (!actorIds.isEmpty()) {
            List<AppUser> users = userRepo.findAllById(actorIds);
            for (AppUser u : users) {
                actorNameMap.put(u.getId(), u.getDisplayName() + " (" + u.getUsername() + ")");
            }
        }

        return page.map(log -> {
            String actorName = log.getActorId() != null
                    ? actorNameMap.getOrDefault(log.getActorId(), "未知用户(" + log.getActorId() + ")")
                    : "系统后台";
            return AuditLogEntry.fromEntity(log, actorName);
        });
    }
}
