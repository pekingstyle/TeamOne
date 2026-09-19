package cn.teamone.platform.api;

import cn.teamone.platform.app.AuditLogQueryService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.AppUser.PlatformRole;
import cn.teamone.platform.dto.AuditLogDto.AuditLogEntry;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作审计日志只读查询端点（P0 企业治理底座 · 安全审计大盘）。
 *
 * <p>权限规约：仅系统管理员（ADMIN / OWNER）具备查看安全操作审计日志的权限。</p>
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    /**
     * 分页查询全局操作审计事件流。
     *
     * @param me 当前登录用户
     * @param pageable 分页参数
     * @return 分页结果
     */
    @GetMapping
    public Page<AuditLogEntry> listAuditLogs(@AuthenticationPrincipal AppUser me,
                                            @PageableDefault(size = 20) Pageable pageable) {
        requireAdmin(me);
        return auditLogQueryService.listAuditLogs(pageable);
    }

    private static void requireAdmin(AppUser me) {
        if (me == null || (me.getPlatformRole() != PlatformRole.ADMIN && me.getPlatformRole() != PlatformRole.OWNER)) {
            throw new BusinessException(ErrorCode.PLT_4030, "只有系统管理员或所有者可以查看操作审计日志");
        }
    }
}
