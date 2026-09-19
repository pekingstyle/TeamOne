package cn.teamone.platform.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 审计服务（05 §7 安全设计清单：登录成功/失败、ACL 变更、发布动作等必审计）。
 *
 * <p>@Transactional(REQUIRED)：随调用方事务提交（如 ACL 变更）；无事务调用（如登录失败）
 * 则自开短事务独立落库。detail 写 jsonb。</p>
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** 记录一条审计（append-only，不提供更新/删除入口） */
    @Transactional
    public void record(UUID actorId, String action, String resourceType, String resourceId,
                       Map<String, Object> detail) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDetail(detail);
        repository.save(log);
    }
}
