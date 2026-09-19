package cn.teamone.eng.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.teamone.eng.app.RepoLocator;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.UpsertRepoMemberRequest;
import cn.teamone.platform.authz.RepoMemberService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.dto.RepoMemberDto;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.auth.RequireRepoPerm;

/**
 * 仓库成员授权端点（⑥i-A1 M-a · docs/v2/13 §6.1 A4，members 三端点契约与并行前端批一致）。
 *
 * <p>权限口径：GET 查看仅需登录态（本批以 {@code view} 真实验证切面，INTERNAL 仓经
 * visibility 兜底全员可读，对现网零收紧）；PUT / DELETE 管理需仓库 manage-settings
 * （Owner 角色 ∨ 平台 OWNER/ADMIN 短路，判定在 {@code @RequireRepoPerm} 切面）。
 * 变更动作在 {@code RepoMemberService} 内同事务写审计（acl.grant/acl.role/acl.revoke）
 * 并 INCR 判定缓存版本。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@RestController
@RequestMapping("/api/v1/repos/{idOrName}/members")
public class RepoMemberController {

    private final RepoLocator locator;
    private final RepoMemberService memberService;

    public RepoMemberController(RepoLocator locator, RepoMemberService memberService) {
        this.locator = locator;
        this.memberService = memberService;
    }

    /**
     * 成员列表：{"items":[{userId,username,displayName,role,source,grantedByName,grantedAt}]}。
     * INHERITED 行 source=INHERITED（前端标注「建仓人」）；查看仅需登录态（view 兜底可读）。
     */
    @GetMapping
    @RequireRepoPerm(action = RepoActions.VIEW)
    public Map<String, Object> list(@PathVariable String idOrName) {
        Repository repo = locator.resolve(idOrName);
        List<RepoMemberDto> items = memberService.listMembers(repo.getId());
        return Map.of("items", items);
    }

    /**
     * 授予/改角色（upsert，source=DIRECT）：body {"userId":"...","role":"maintainer|developer|reporter"}
     * → 200 item。目标为 Owner（含建仓人 INHERITED）时 409 最后 Owner 保护。
     */
    @PutMapping
    @RequireRepoPerm(action = RepoActions.MANAGE_SETTINGS)
    public RepoMemberDto upsert(@PathVariable String idOrName,
                                @AuthenticationPrincipal AppUser me,
                                @RequestBody UpsertRepoMemberRequest req) {
        Repository repo = locator.resolve(idOrName);
        UUID subjectUserId = parseUserId(req == null ? null : req.userId());
        return memberService.upsertMember(repo.getId(), subjectUserId,
                req.role(), me == null ? null : me.getId());
    }

    /**
     * 移除授权（删 DIRECT 行）：204。INHERITED/唯一 Owner 行 409 最后 Owner 保护；无条目 404。
     */
    @DeleteMapping("/{userId}")
    @RequireRepoPerm(action = RepoActions.MANAGE_SETTINGS)
    public ResponseEntity<Void> remove(@PathVariable String idOrName,
                                       @PathVariable String userId,
                                       @AuthenticationPrincipal AppUser me) {
        Repository repo = locator.resolve(idOrName);
        memberService.removeMember(repo.getId(), parseUserId(userId), me == null ? null : me.getId());
        return ResponseEntity.noContent().build();
    }

    private UUID parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "userId 不能为空");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "userId 非法（须为 UUID）: " + raw);
        }
    }
}
