package cn.teamone.eng.api;

import cn.teamone.eng.app.MergeRequestService;
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
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
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
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1")
public class MergeRequestController {

    private final MergeRequestService mrService;
    private final MergeCommentRepository commentRepo;

    public MergeRequestController(MergeRequestService mrService, MergeCommentRepository commentRepo) {
        this.mrService = mrService;
        this.commentRepo = commentRepo;
    }

    /**
     * MR 列表查询（支持按仓库或状态过滤）。
     */
    @GetMapping("/mrs")
    public Map<String, Object> listMrs(
            @RequestParam(required = false) String repoId,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        Page<MrDetailResponse> p = mrService.listMrs(repoId, status, page, size);
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
     */
    @GetMapping("/repos/{repoId}/mrs")
    public Map<String, Object> listRepoMrs(
            @PathVariable String repoId,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return listMrs(repoId, status, page, size);
    }

    /**
     * 创建 MR 评审单。
     */
    @PostMapping("/mrs")
    public MrDetailResponse createMr(
            @AuthenticationPrincipal AppUser me,
            @RequestBody CreateMrRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return mrService.createMr(req, userId);
    }

    /**
     * MR 详情查询（含状态机、评审人、门禁与三路变更集）。
     */
    @GetMapping("/mrs/{id}")
    public MrDetailResponse getMr(@PathVariable UUID id) {
        return mrService.getMr(id);
    }

    /**
     * 独立获取 MR 逐文件三路 Diff。
     */
    @GetMapping("/mrs/{id}/diff")
    public GitDiffResult getMrDiff(@PathVariable UUID id) {
        return mrService.getMrDiff(id);
    }

    /**
     * 会签评审动作：批准或请求修改。
     */
    @PostMapping("/mrs/{id}/review")
    public MrDetailResponse review(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody ReviewMrRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return mrService.review(id, userId, req.state(), req.comment());
    }

    /**
     * 单测门禁豁免申请与批准（R8 门禁闭环）。
     */
    @PostMapping("/mrs/{id}/checks/unit-test/exempt")
    public MrDetailResponse exemptUnitTest(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody ExemptUnitTestRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return mrService.exemptUnitTest(id, userId, req.reason());
    }

    /**
     * CI 流水线单测报告回传上报协议（05 §6.3）。
     */
    @PostMapping("/mrs/{id}/checks/unit-test")
    public MrDetailResponse uploadUnitTestReport(
            @PathVariable UUID id,
            @RequestBody UnitTestReportRequest report) {
        return mrService.uploadUnitTestReport(id, report);
    }

    /**
     * 冲突文件方案解决标记留痕。
     */
    @PostMapping("/mrs/{id}/conflicts/resolve")
    public MrDetailResponse resolveConflict(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody ResolveConflictRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return mrService.resolveConflict(id, userId, req.filePath(), req.solution());
    }

    /**
     * Rebase 状态重新探测与刷新。
     */
    @PostMapping("/mrs/{id}/rebase")
    public MrDetailResponse rebase(@PathVariable UUID id) {
        return mrService.rebase(id);
    }

    /**
     * 执行原生服务端合并（含 L1 门禁硬核重算）。
     */
    @PostMapping("/mrs/{id}/merge")
    public MergeResultResponse merge(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return mrService.merge(id, userId);
    }

    /**
     * 关闭评审（⑥h 评审生命周期补全）：draft|open → closed。
     *
     * <p>权限：仅 MR 作者本人或平台管理员（platform:manage，OWNER/ADMIN 短路），
     * 断言在服务层完成；非法状态迁移（merged/closed）返回 4xx。</p>
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
     * <p>权限口径与关闭一致；merged 为不可逆终态不可重开（4xx）。</p>
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
     */
    @PostMapping("/mrs/{id}/comments")
    public MrDetailResponse addComment(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "评论内容不能为空");
        }
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        MergeComment c = new MergeComment();
        c.setMrId(id);
        c.setAuthorId(userId);
        c.setText(text.trim());
        commentRepo.save(c);

        return mrService.getMr(id);
    }
}
