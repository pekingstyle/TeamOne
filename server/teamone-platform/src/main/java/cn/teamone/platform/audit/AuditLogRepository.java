package cn.teamone.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 审计日志仓库（表 audit.audit_log）。 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
