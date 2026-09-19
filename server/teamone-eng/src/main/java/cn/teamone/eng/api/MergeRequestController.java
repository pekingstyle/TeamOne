package cn.teamone.eng.api;

import cn.teamone.eng.app.MergeRequestService;
import cn.teamone.eng.app.RepoPermChecker;
import cn.teamone.eng.domain.MergeComment;
import cn.teamone.eng.dto.CreateMrRequest;
import cn.teamone.eng.dto.ExemptUnitTestRequest;
import cn.teamone.eng.dto.MergeResultResponse;
import cn.teamone.eng.dto.MrDetailResponse;
import cn.teamone.eng.dto.ResolveConflictRequest;
import cn.teamone.eng.dto.ReviewMrRequest;
import cn.teamone.eng.dto.UnitTestReportRequest;
import cn.teamone.eng.infra.git.GitDiffResult;
import cn.teamone.eng.repo.MergeCommentRepository;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.authz.RepoRole;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import cn.teamone.shared.auth.RequireRepoPerm;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 代码评审（MR）与门禁控制 REST 端点（05 §3.3 / M2-INC-3 U6/U7）。
 *
 * <p>ACL 接入（⑥j-A M-b B1/B3/B4 · docs/v2/13 §2.2/§2.4/§5.2）：MR 权限锚点 = 源/目标
 * 分支所属仓库（§2.1 目标仓库优先）。除 {@code GET /repos/{repoId}/mrs}（/repos 前缀，
 * 切面 URI 定位 → view）外，本控制器端点均无 /repos 前缀——控制器内显式服务层回退：
 * 先解析 MR/请求参数 → repoId，再经 {@link RepoPermChecker} 断言。动作映射（§2.4 派生
 * 端点判定口径表）：POST /mrs→create-mr；review→review；merge→merge；
 * conflicts/resolve→merge、rebase→push（两行 §2.4 原文「MR 作者 ∨ Developer+」，批口径
 * 取能力门 Developer+ 实现从简，作者短路省略——挂账见批报告）；comments POST→review
 * （§2.4「Reporter+ 即 view 即可评」，取 review 能力位：评论=参与评审动作）；
 * GET /mrs、/mrs/{id}(/diff)→view（跨仓列表含 PRIVATE 结果集过滤 B4）；
 * exempt→Maintainer+（§2.4「MR 单测豁免」行）；close/reopen 判定在服务层（B3）。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1")
public class MergeRequestController {

    private final MergeRequestService mrService;
    private final MergeCommentRepository commentRepo;
    private final RepoPermChecker permChecker;

    public MergeRequestController(MergeRequestService mrService, MergeCommentRepository commentRepo,
                                  RepoPermChecker permChecker) {
        this.mrService = mrService;
        this.commentRepo = commentRepo;
        this.permChecker = permChecker;
    }

    /**
     * MR 列表查询（支持按仓库或状态过滤）。
     *
     * <p>ACL（M-b B4）：跨仓列表按仓库可见性过滤结果集（无 view 的仓 MR 元数据不返回，
     * §5.2 清单行）；过滤在 {@link MergeRequestService#listMrs} 服务层执行。</p>
     */
    @GetMapping("/mrs")
    public Map<String, Object> listMrs(
            @AuthenticationPrincipal AppUser me,
            @RequestParam(required = false) String repoId,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        Page<MrDetailResponse> p = mrService.listMrs(me, repoId, status, page, size);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", p.getContent());
        res.put("page", p.getNumber() + 1);
        res.put("size", p.getSize());
        res.put("total", p.getTotalElements());
        res.put("totalPages", p.getTotalPages());
        return res;
    }

    /**
     * 仓库维度的 MR 列表便捷端点。
     *
     * <p>ACL（M-b B1）：view（/repos 前缀走切面 URI 定位，§5.2「无 view → 403（单仓）」；
     * 列表内过滤对单仓形态天然幂等）。</p>
     */
    @GetMapping("/repos/{repoId}/mrs")
    @RequireRepoPerm(action = RepoActions.VIEW)
    public Map<String, Object> listRepoMrs(
            @AuthenticationPrincipal AppUser me,
            @PathVariable String repoId,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return listMrs(me, repoId, status, page, size);
    }

    /**
     * 创建 MR 评审单。
     *
     * <p>ACL（M-b B1）：create-mr（§2.2；能力矩阵 Reporter+——对标 GitLab「从可读分支发 MR」，
     * §2.3 注记；请求参数 repoId 解析仓库后断言，服务层回退定位）。</p>
     */
    @PostMapping("/mrs")
    public MrDetailResponse createMr(
            @AuthenticationPrincipal AppUser me,
            @RequestBody CreateMrRequest req) {
        UUID userId = requireMe(me);
        // 权限先于业务校验后于参数校验：repoId 缺失仍按既有 400 口径（findRepo 空标识 400）
        permChecker.require(userId, mrService.findRepo(req == null ? null : req.repoId()).getId(),
                RepoActions.CREATE_MR);
        return mrService.createMr(req, userId);
    }

    /**
     * MR 详情查询（含状态机、评审人、门禁与三路变更集）。
     *
     * <p>ACL（M-b B1）：view（服务层回退：MR→目标仓解析，§5.2 清单行）。</p>
     */
    @GetMapping("/mrs/{id}")
    public MrDetailResponse getMr(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        requireMrRepoPerm(me, id, RepoActions.VIEW);
        return mrService.getMr(id);
    }

    /**
     * 独立获取 MR 逐文件三路 Diff。
     *
     * <p>ACL（M-b B1）：view（服务层回退：MR→目标仓解析，§5.2「/mrs/{id}/diff」）。</p>
     */
    @GetMapping("/mrs/{id}/diff")
    public GitDiffResult getMrDiff(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        requireMrRepoPerm(me, id, RepoActions.VIEW);
        return mrService.getMrDiff(id);
    }

    /**
     * 会签评审动作：批准或请求修改。
     *
     * <p>ACL（M-b B1）：review（§2.2 扩展动作「MR 评审表态」，Reporter+；服务层回退定位）。</p>
     */
    @PostMapping("/mrs/{id}/review")
    public MrDetailResponse review(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody ReviewMrRequest req) {
        UUID userId = requireMe(me);
        requireMrRepoPerm(me, id, RepoActions.REVIEW);
        return mrService.review(id, userId, req.state(), req.comment());
    }

    /**
     * 单测门禁豁免申请与批准（R8 门禁闭环）。
     *
     * <p>ACL（M-b B1）：Maintainer+（§2.4「MR 单测豁免」行——对齐 05 §3.3「需基线管理员
     * 权限」既有口径；批口径以角色下限断言实现：requireRoleAtLeast(MAINTAINER)，
     * 能力嵌套下等价于 manage-protection 档，平台 OWNER/ADMIN 由链内短路放行）。
     * 豁免必填理由 + 审计留痕口径不变。</p>
     */
    @PostMapping("/mrs/{id}/checks/unit-test/exempt")
    public MrDetailResponse exemptUnitTest(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody ExemptUnitTestRequest req) {
        UUID userId = requireMe(me);
        requireMrRoleAtLeast(me, id, RepoRole.MAINTAINER, "豁免单测门禁");
        return mrService.exemptUnitTest(id, userId, req.reason());
    }

    /**
     * CI 流水线单测报告回传上报协议（05 §6.3）。
     *
     * <p>ACL：服务间通道（流水线封装凭据），不走仓库 ACL（§2.4 行；鉴权方案见开放问题 Q6，
     * 本批不动）。</p>
     */
    @PostMapping("/mrs/{id}/checks/unit-test")
    public MrDetailResponse uploadUnitTestReport(
            @PathVariable UUID id,
            @RequestBody UnitTestReportRequest report) {
        return mrService.uploadUnitTestReport(id, report);
    }

    /**
     * 冲突文件方案解决标记留痕。
     *
     * <p>ACL（M-b B1）：merge（§2.4 原文「MR 作者 ∨ Developer+」——merge 能力位即 Developer+，
     * 批口径取纯能力门实现从简，作者短路省略并挂账；服务层回退定位）。</p>
     */
    @PostMapping("/mrs/{id}/conflicts/resolve")
    public MrDetailResponse resolveConflict(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody ResolveConflictRequest req) {
        UUID userId = requireMe(me);
        requireMrRepoPerm(me, id, RepoActions.MERGE);
        return mrService.resolveConflict(id, userId, req.filePath(), req.solution());
    }

    /**
     * Rebase 状态重新探测与刷新。
     *
     * <p>ACL（M-b B1）：push（§2.4 原文「MR 作者 ∨ Developer+」——push 能力位即 Developer+，
     * 批口径同上从简；服务层回退定位）。</p>
     */
    @PostMapping("/mrs/{id}/rebase")
    public MrDetailResponse rebase(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        requireMe(me);
        requireMrRepoPerm(me, id, RepoActions.PUSH);
        return mrService.rebase(id);
    }

    /**
     * 执行原生服务端合并（含 L1 门禁硬核重算）。
     *
     * <p>ACL（M-b B1）：merge（§5.3 合并端到端判定序第 1 步「作者对目标仓库具备 merge 能力？」，
     * Developer+；六项门禁在其后独立重算——能力与门禁是两回事，§2.3 注记）。</p>
     */
    @PostMapping("/mrs/{id}/merge")
    public MergeResultResponse merge(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        UUID userId = requireMe(me);
        requireMrRepoPerm(me, id, RepoActions.MERGE);
        return mrService.merge(id, userId);
    }

    /**
     * 关闭评审（⑥h 评审生命周期补全）：draft|open → closed。
     *
     * <p>ACL（M-b B3 · §2.4「MR close/reopen」行）：「MR 作者 ∨ 仓库 Maintainer+ ∨ 平台
     * 管理员」——替换原「作者 ∨ platform:manage」服务层断言（行为兼容：平台 OWNER/ADMIN
     * 由仓库链第 1 步短路放行；作者短路在前）。判定在 {@link MergeRequestService} 服务层；
     * 非法状态迁移（merged/closed）返回 4xx。</p>
     */
    @PostMapping("/mrs/{id}/close")
    public MrDetailResponse close(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        // ⑥h QA 复审 NICE：/api/** 虽有 authenticated 兜底，此处仍显式 401——
        // 不给未来放开匿名时「以系统兜底身份过作者判定」留口子
        if (me == null) {
            throw new BusinessException(ErrorCode.PLT_4010, "未认证或凭证已失效");
        }
        return mrService.closeMr(id, me.getId());
    }

    /**
     * 重新打开评审（⑥h 评审生命周期补全）：closed → open。
     *
     * <p>ACL（M-b B3）：口径与关闭一致（作者 ∨ Maintainer+ ∨ 平台管理员）；merged 为不可逆
     * 终态不可重开（4xx）。</p>
     */
    @PostMapping("/mrs/{id}/reopen")
    public MrDetailResponse reopen(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        if (me == null) {
            throw new BusinessException(ErrorCode.PLT_4010, "未认证或凭证已失效");
        }
        return mrService.reopenMr(id, me.getId());
    }

    /**
     * 发表评审讨论留言。
     *
     * <p>ACL（M-b B1）：review（§2.4「MR comments：Reporter+（即 view 即可评）」——与 review
     * 表态同档取 review 能力位：评论 = 参与评审动作，须为仓库成员或平台管理员，visibility
     * 兜底只读者不可评；服务层回退定位）。</p>
     */
    @PostMapping("/mrs/{id}/comments")
    public MrDetailResponse addComment(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        UUID userId = requireMe(me);
        requireMrRepoPerm(me, id, RepoActions.REVIEW);
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "评论内容不能为空");
        }
        MergeComment c = new MergeComment();
        c.setMrId(id);
        c.setAuthorId(userId);
        c.setText(text.trim());
        commentRepo.save(c);

        return mrService.getMr(id);
    }

    // ==================== 服务层回退定位（§4.4 定位器②） ====================

    /** 登录态防御：显式 401（不给系统兜底身份过仓库判定留口子），返回用户 id */
    private UUID requireMe(AppUser me) {
        if (me == null) {
            throw new PermissionDeniedException(ErrorCode.PLT_4010, "未认证或凭证已失效", null);
        }
        return me.getId();
    }

    /** MR→目标仓解析后断言仓库动作（无 /repos 前缀端点统一入口） */
    private void requireMrRepoPerm(AppUser me, UUID mrId, String action) {
        permChecker.require(requireMe(me), mrService.repoIdOf(mrId), action);
    }

    /** MR→目标仓解析后断言角色下限（§2.4「Maintainer+」派生口径） */
    private void requireMrRoleAtLeast(AppUser me, UUID mrId, RepoRole floor, String scene) {
        permChecker.requireRoleAtLeast(requireMe(me), mrService.repoIdOf(mrId), floor, scene);
    }
}
