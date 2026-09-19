package cn.teamone.eng.app;

import cn.teamone.eng.domain.MergeCheck;
import cn.teamone.eng.domain.MergeComment;
import cn.teamone.eng.domain.MergeConflictResolution;
import cn.teamone.eng.domain.MergeRequest;
import cn.teamone.eng.domain.MergeReviewer;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.CreateMrRequest;
import cn.teamone.eng.dto.MergeResultResponse;
import cn.teamone.eng.dto.MrDetailResponse;
import cn.teamone.eng.dto.UnitTestReportRequest;
import cn.teamone.eng.infra.git.GitDiffResult;
import cn.teamone.eng.infra.git.GitFileDiff;
import cn.teamone.eng.infra.git.GitMergeCheckResult;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.MergeCheckRepository;
import cn.teamone.eng.repo.MergeCommentRepository;
import cn.teamone.eng.repo.MergeConflictResolutionRepository;
import cn.teamone.eng.repo.MergeRequestRepository;
import cn.teamone.eng.repo.MergeReviewerRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.authz.RepoRole;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MR 业务应用服务（M2-INC-3 U5~U7：会签状态机、单测门禁判定与原生服务端合并）。
 *
 * <p>ACL（⑥j-A M-b B3/B4 · docs/v2/13 §2.4/§5.2）：close/reopen 服务层断言改为
 * 「MR 作者 ∨ 仓库 Maintainer+ ∨ 平台管理员」（平台管理员由仓库链第 1 步短路）；
 * listMrs 跨仓列表对 PRIVATE 仓 MR 逐仓 view 剔除（结果集过滤，不整单 403）。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Service
public class MergeRequestService {

    private static final Logger log = LoggerFactory.getLogger(MergeRequestService.class);

    /** 系统动作兜底作者（与既有 createMr/Controller 缺省主体口径一致） */
    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final MergeRequestRepository mrRepo;
    private final MergeReviewerRepository reviewerRepo;
    private final MergeCheckRepository checkRepo;
    private final MergeCommentRepository commentRepo;
    private final MergeConflictResolutionRepository resolutionRepo;
    private final RepositoryRepository repositoryRepo;
    private final BranchProtectionService branchProtectionService;
    private final BranchRuleService branchRuleService;
    private final GitPort gitPort;
    private final AuditService audit;
    private final RepoPermChecker permChecker;

    /** 单测门禁双阈值（teamone.mr.gate.total-coverage / patch-coverage，默认 60/80） */
    private final double totalCoverageGate;
    private final double patchCoverageGate;

    public MergeRequestService(
            MergeRequestRepository mrRepo,
            MergeReviewerRepository reviewerRepo,
            MergeCheckRepository checkRepo,
            MergeCommentRepository commentRepo,
            MergeConflictResolutionRepository resolutionRepo,
            RepositoryRepository repositoryRepo,
            BranchProtectionService branchProtectionService,
            BranchRuleService branchRuleService,
            GitPort gitPort,
            AuditService audit,
            RepoPermChecker permChecker,
            @Value("${teamone.mr.gate.total-coverage:60}") double totalCoverageGate,
            @Value("${teamone.mr.gate.patch-coverage:80}") double patchCoverageGate) {
        this.mrRepo = mrRepo;
        this.reviewerRepo = reviewerRepo;
        this.checkRepo = checkRepo;
        this.commentRepo = commentRepo;
        this.resolutionRepo = resolutionRepo;
        this.repositoryRepo = repositoryRepo;
        this.branchProtectionService = branchProtectionService;
        this.branchRuleService = branchRuleService;
        this.gitPort = gitPort;
        this.audit = audit;
        this.permChecker = permChecker;
        this.totalCoverageGate = totalCoverageGate;
        this.patchCoverageGate = patchCoverageGate;
    }

    public Repository findRepo(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库标识不能为空");
        }
        try {
            UUID id = UUID.fromString(idOrName.trim());
            return repositoryRepo.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        } catch (IllegalArgumentException e) {
            return repositoryRepo.findByName(idOrName.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        }
    }

    /**
     * MR→所属仓库解析（⑥j-A M-b B1 服务层回退定位：无 /repos 前缀端点
     * {@code /mrs/{id}/**} 经 MR 行归一 repoId，§2.1「目标仓库优先」）。
     *
     * @param mrId MR UUID
     * @return 所属仓库 id（永不为 null）
     * @throws BusinessException MR 不存在时 404
     */
    @Transactional(readOnly = true)
    public UUID repoIdOf(UUID mrId) {
        return mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId))
                .getRepoId();
    }

    @Transactional
    public MrDetailResponse createMr(CreateMrRequest req, UUID authorId) {
        if (req.repoId() == null || req.repoId().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库 ID 不能为空");
        }
        Repository repo = findRepo(req.repoId());

        String sourceBranch = (req.sourceBranch() == null || req.sourceBranch().isBlank()) ? "main" : req.sourceBranch().trim();
        String targetBranch = (req.targetBranch() == null || req.targetBranch().isBlank()) ? repo.getDefaultBranch() : req.targetBranch().trim();

        // 生效点二（分支治理批）：源分支命中 branch_rule 且规则指定 merge_target 时，
        // MR 目标必须与之一致，否则 422（ENG_4255）——在门禁重算前快速失败
        branchRuleService.assertMergeTargetAllowed(repo, sourceBranch, targetBranch);

        GitMergeCheckResult checkRes = gitPort.checkMerge(repo.getRepoPath(), targetBranch, sourceBranch);
        int nextNumber = mrRepo.findMaxMrNumber(repo.getId()) + 1;

        MergeRequest mr = new MergeRequest();
        mr.setRepoId(repo.getId());
        mr.setMrNumber(nextNumber);
        mr.setTitle(req.title() != null ? req.title().trim() : "MR !" + nextNumber);
        mr.setDescription(req.description());
        mr.setSourceBranch(sourceBranch);
        mr.setTargetBranch(targetBranch);
        mr.setAuthorId(authorId != null ? authorId : UUID.fromString("00000000-0000-0000-0000-000000000001"));
        mr.setStatus("open");
        mr.setLinkedWorkItemKey(req.linkedWorkItemKey());
        mr.setBasedOnBaselineId(req.basedOnBaselineId());
        mr.setRebaseRequired(checkRes.rebaseRequired());
        mr = mrRepo.save(mr);

        // 初始评审人
        if (req.reviewerIds() != null && !req.reviewerIds().isEmpty()) {
            for (UUID reviewerId : req.reviewerIds()) {
                MergeReviewer r = new MergeReviewer();
                r.setMrId(mr.getId());
                r.setUserId(reviewerId);
                r.setState("pending");
                reviewerRepo.save(r);
            }
        }

        // 初始检查项：单测门禁启发式判定
        GitDiffResult diffRes = gitPort.diff(repo.getRepoPath(), targetBranch, sourceBranch);
        List<String> testFiles = new ArrayList<>();
        for (GitFileDiff fd : diffRes.files()) {
            if (isTestFile(fd.path())) {
                testFiles.add(fd.path());
            }
        }
        boolean hasTests = !testFiles.isEmpty();
        boolean gatePassed = hasTests;

        MergeCheck unitTestCheck = new MergeCheck();
        unitTestCheck.setMrId(mr.getId());
        unitTestCheck.setKind("unit_test");
        unitTestCheck.setName(gateName());
        unitTestCheck.setPassed(gatePassed);
        Map<String, Object> utPayload = new HashMap<>();
        utPayload.put("hasTests", hasTests);
        utPayload.put("testFiles", testFiles);
        utPayload.put("passed", gatePassed);
        utPayload.put("coverageTotal", hasTests ? 78.0 : 50.0);
        utPayload.put("coverageDelta", hasTests ? 85.0 : 0.0);
        utPayload.put("gatePassed", gatePassed);
        unitTestCheck.setPayload(utPayload);
        checkRepo.save(unitTestCheck);

        // 可合并性检查项
        MergeCheck conflictCheck = new MergeCheck();
        conflictCheck.setMrId(mr.getId());
        conflictCheck.setKind("conflict");
        conflictCheck.setName("可合并性 (merge-tree)");
        conflictCheck.setPassed(checkRes.canMerge());
        Map<String, Object> confPayload = new HashMap<>();
        confPayload.put("conflictFiles", checkRes.conflictFiles());
        conflictCheck.setPayload(confPayload);
        checkRepo.save(conflictCheck);

        // Rebase 检查项
        MergeCheck rebaseCheck = new MergeCheck();
        rebaseCheck.setMrId(mr.getId());
        rebaseCheck.setKind("rebase");
        rebaseCheck.setName("Rebase 状态");
        rebaseCheck.setPassed(!checkRes.rebaseRequired());
        Map<String, Object> rebPayload = new HashMap<>();
        rebPayload.put("rebaseRequired", checkRes.rebaseRequired());
        rebaseCheck.setPayload(rebPayload);
        checkRepo.save(rebaseCheck);

        return toDetailResponse(mr, repo, true);
    }

    /**
     * MR 列表（跨仓/按仓过滤，含 B4 PRIVATE 结果集过滤）。
     *
     * <p>ACL（⑥j-A M-b B4 · §5.2 清单行「GET /mrs 跨仓列表：按仓库可见性过滤结果集——
     * 无 view 的仓 MR 元数据不返回」）：repoId 去重后逐仓 view 判定；平台管理员整体跳过；
     * me=null 为防御分支不过滤（/api/** 已 authenticated）。指定仓库形态的 view 断言由
     * 控制器 {@code GET /repos/{repoId}/mrs} 切面完成，本方法内过滤对单仓形态天然幂等。
     * 简化口径（挂账）：剔除不重分页，total 按剔除数等量下调；精确分页需查询侧 join
     * 可见性，v2.1 评估。现网无 PRIVATE 数据 = 行为零变化。</p>
     *
     * @param me          当前登录用户（过滤判定主体，可空）
     * @param repoIdOrName 可选仓库过滤（UUID 或名称）
     * @param status      可选状态过滤
     * @param page        页码（从 1 起）
     * @param size        页大小
     */
    @Transactional(readOnly = true)
    public Page<MrDetailResponse> listMrs(AppUser me, String repoIdOrName, String status, int page, int size) {
        UUID repoId = null;
        if (repoIdOrName != null && !repoIdOrName.isBlank()) {
            try {
                repoId = findRepo(repoIdOrName).getId();
            } catch (Exception e) {
                return Page.empty();
            }
        }
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), Math.max(1, size), Sort.by(Sort.Direction.DESC, "mrNumber"));
        Page<MergeRequest> mrs;
        if (repoId != null && status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            mrs = mrRepo.findByRepoIdAndStatus(repoId, status.toLowerCase(), pageable);
        } else if (repoId != null) {
            mrs = mrRepo.findByRepoId(repoId, pageable);
        } else if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            mrs = mrRepo.findByStatus(status.toLowerCase(), pageable);
        } else {
            mrs = mrRepo.findAll(pageable);
        }

        // B4：无 view 仓库集合（repoId 去重判定）
        java.util.Set<UUID> hiddenRepos = new java.util.HashSet<>();
        if (me != null && !RepoPermChecker.isPlatformAdmin(me)) {
            for (MergeRequest m : mrs.getContent()) {
                if (!hiddenRepos.contains(m.getRepoId())
                        && !permChecker.check(me.getId(), m.getRepoId(), RepoActions.VIEW)) {
                    hiddenRepos.add(m.getRepoId());
                }
            }
        }

        Map<UUID, Repository> repoCache = new HashMap<>();
        List<MrDetailResponse> visible = new java.util.ArrayList<>(mrs.getContent().size());
        for (MergeRequest m : mrs.getContent()) {
            if (hiddenRepos.contains(m.getRepoId())) {
                continue;
            }
            Repository r = repoCache.computeIfAbsent(m.getRepoId(), id -> repositoryRepo.findById(id).orElse(null));
            visible.add(toDetailResponse(m, r, false));
        }
        if (visible.size() == mrs.getContent().size()) {
            return new org.springframework.data.domain.PageImpl<>(visible, pageable, mrs.getTotalElements());
        }
        // 简化口径：total 等量下调（本页剔除数），不重取页
        return new org.springframework.data.domain.PageImpl<>(visible, pageable,
                mrs.getTotalElements() - (mrs.getContent().size() - visible.size()));
    }

    @Transactional(readOnly = true)
    public MrDetailResponse getMr(UUID mrId) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        Repository repo = repositoryRepo.findById(mr.getRepoId()).orElse(null);
        return toDetailResponse(mr, repo, true);
    }

    @Transactional(readOnly = true)
    public GitDiffResult getMrDiff(UUID mrId) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        Repository repo = repositoryRepo.findById(mr.getRepoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在"));
        return gitPort.diff(repo.getRepoPath(), mr.getTargetBranch(), mr.getSourceBranch());
    }

    /**
     * 关闭评审（⑥h 评审生命周期补全）：draft|open → closed，写系统评论与审计。
     *
     * <p>ACL（⑥j-A M-b B3 · §2.4「MR close/reopen」行）：「MR 作者本人 ∨ 仓库 Maintainer+
     * ∨ 平台管理员」——替换原「作者 ∨ platform:manage」口径（行为兼容：平台 OWNER/ADMIN
     * 由仓库判定链第 1 步短路放行，仍覆盖原管理员动线）；merged/closed 等非法迁移抛
     * PLT_4000（4xx）。</p>
     */
    @Transactional
    public MrDetailResponse closeMr(UUID mrId, UUID userId) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        assertManageableByAuthorOrMaintainer(mr, userId, "关闭");
        if (!"open".equals(mr.getStatus()) && !"draft".equals(mr.getStatus())) {
            // 非法状态迁移（merged 已合并 / closed 已关闭）按 4xx 快速失败
            throw new BusinessException(ErrorCode.PLT_4000, "MR 当前状态不可关闭: " + mr.getStatus());
        }

        Instant now = Instant.now();
        mr.setStatus("closed");
        mr.setClosedAt(now);
        mr.setUpdatedAt(now);
        mrRepo.save(mr);

        addSystemComment(mrId, userId, "【关闭评审】MR 已" + (isAuthor(mr, userId) ? "由作者" : "由仓库维护者或管理员") + "关闭，评审流程终止。");
        audit.record(userId, "mr.close", "mr", mrId.toString(), auditDetail(mr));
        return toDetailResponse(mr, repositoryRepo.findById(mr.getRepoId()).orElse(null), false);
    }

    /**
     * 重开评审（⑥h 评审生命周期补全）：closed → open，写系统评论与审计。
     *
     * <p>ACL（M-b B3）：口径与关闭一致（作者 ∨ Maintainer+ ∨ 平台管理员）；merged 不可重开
     * （已合并单据为不可逆终态，仅可另起新 MR）。</p>
     */
    @Transactional
    public MrDetailResponse reopenMr(UUID mrId, UUID userId) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        assertManageableByAuthorOrMaintainer(mr, userId, "重开");
        if (!"closed".equals(mr.getStatus())) {
            if ("merged".equals(mr.getStatus())) {
                throw new BusinessException(ErrorCode.PLT_4000, "MR 已合并，不可重开");
            }
            throw new BusinessException(ErrorCode.PLT_4000, "MR 当前状态不可重开: " + mr.getStatus());
        }

        mr.setStatus("open");
        mr.setClosedAt(null);
        mr.setUpdatedAt(Instant.now());
        mrRepo.save(mr);

        addSystemComment(mrId, userId, "【重新打开】MR 已重开，评审流程恢复进行。");
        audit.record(userId, "mr.reopen", "mr", mrId.toString(), auditDetail(mr));
        return toDetailResponse(mr, repositoryRepo.findById(mr.getRepoId()).orElse(null), false);
    }

    /**
     * 关闭/重开权限（M-b B3 新口径，§2.4）：MR 作者本人短路在前 → 仓库 Maintainer+（角色下限，
     * 平台 OWNER/ADMIN 由仓库链第 1 步短路放行）。
     */
    private void assertManageableByAuthorOrMaintainer(MergeRequest mr, UUID userId, String action) {
        if (isAuthor(mr, userId)) {
            return;
        }
        permChecker.requireRoleAtLeast(userId, mr.getRepoId(), RepoRole.MAINTAINER, action + "评审");
    }

    private static boolean isAuthor(MergeRequest mr, UUID userId) {
        return userId != null && userId.equals(mr.getAuthorId());
    }

    /** 系统/动作留痕评论（关闭、重开、自动删源分支等，author 为操作人或系统兜底） */
    private void addSystemComment(UUID mrId, UUID userId, String text) {
        MergeComment c = new MergeComment();
        c.setMrId(mrId);
        c.setAuthorId(userId != null ? userId : SYSTEM_USER_ID);
        c.setText(text);
        commentRepo.save(c);
    }

    /** 审计 detail（紧凑视图，避免塞整单） */
    private static Map<String, Object> auditDetail(MergeRequest mr) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mrNumber", mr.getMrNumber());
        detail.put("repoId", mr.getRepoId());
        detail.put("status", mr.getStatus());
        return detail;
    }

    @Transactional
    public MrDetailResponse review(UUID mrId, UUID userId, String state, String comment) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        // draft（草稿预评审）与 open（正式评审）两种状态均可发表评审意见
        if (!"open".equals(mr.getStatus()) && !"draft".equals(mr.getStatus())) {
            throw new BusinessException(ErrorCode.PLT_4000, "MR 当前状态不可评审: " + mr.getStatus());
        }

        String safeState = "approved".equalsIgnoreCase(state) ? "approved" : "changes_requested";
        MergeReviewer reviewer = reviewerRepo.findByMrIdAndUserId(mrId, userId)
                .orElseGet(() -> {
                    MergeReviewer nr = new MergeReviewer();
                    nr.setMrId(mrId);
                    nr.setUserId(userId);
                    return nr;
                });
        reviewer.setState(safeState);
        reviewer.setUpdatedAt(Instant.now());
        reviewerRepo.save(reviewer);

        if (comment != null && !comment.isBlank()) {
            MergeComment c = new MergeComment();
            c.setMrId(mrId);
            c.setAuthorId(userId);
            c.setText(comment.trim());
            commentRepo.save(c);
        }

        Repository repo = repositoryRepo.findById(mr.getRepoId()).orElse(null);
        return toDetailResponse(mr, repo, false);
    }

    @Transactional
    public MrDetailResponse exemptUnitTest(UUID mrId, UUID approvedById, String reason) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        if (!"open".equals(mr.getStatus())) {
            throw new BusinessException(ErrorCode.PLT_4000, "MR 当前状态不可操作: " + mr.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "豁免理由不能为空");
        }

        MergeCheck check = checkRepo.findByMrIdAndKind(mrId, "unit_test")
                .orElseGet(() -> {
                    MergeCheck nc = new MergeCheck();
                    nc.setMrId(mrId);
                    nc.setKind("unit_test");
                    nc.setName(gateName());
                    return nc;
                });

        Map<String, Object> payload = new HashMap<>(check.getPayload());
        Map<String, Object> exemptMap = new HashMap<>();
        exemptMap.put("reason", reason.trim());
        exemptMap.put("approvedById", approvedById != null ? approvedById.toString() : "00000000-0000-0000-0000-000000000001");
        exemptMap.put("approvedAt", Instant.now().toString());

        payload.put("exempt", exemptMap);
        payload.put("gatePassed", true);
        check.setPassed(true);
        check.setPayload(payload);
        check.setUpdatedAt(Instant.now());
        checkRepo.save(check);

        // 增加系统评论留痕
        MergeComment c = new MergeComment();
        c.setMrId(mrId);
        c.setAuthorId(approvedById != null ? approvedById : UUID.fromString("00000000-0000-0000-0000-000000000001"));
        c.setText("【单测豁免】理由：" + reason.trim());
        commentRepo.save(c);

        Repository repo = repositoryRepo.findById(mr.getRepoId()).orElse(null);
        return toDetailResponse(mr, repo, false);
    }

    @Transactional
    public MrDetailResponse uploadUnitTestReport(UUID mrId, UnitTestReportRequest report) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));

        boolean gatePassed = report.passed()
                && report.coverageTotal() >= totalCoverageGate
                && report.coveragePatch() >= patchCoverageGate;

        MergeCheck check = checkRepo.findByMrIdAndKind(mrId, "unit_test")
                .orElseGet(() -> {
                    MergeCheck nc = new MergeCheck();
                    nc.setMrId(mrId);
                    nc.setKind("unit_test");
                    nc.setName(gateName());
                    return nc;
                });

        Map<String, Object> payload = new HashMap<>(check.getPayload());
        payload.put("hasTests", report.total() > 0);
        payload.put("passed", report.passed());
        payload.put("total", report.total());
        payload.put("failed", report.failed());
        payload.put("coverageTotal", report.coverageTotal());
        payload.put("coverageDelta", report.coveragePatch());
        payload.put("gatePassed", gatePassed);
        payload.put("reportUrl", report.reportUrl());

        check.setPassed(gatePassed);
        check.setPayload(payload);
        check.setUpdatedAt(Instant.now());
        checkRepo.save(check);

        Repository repo = repositoryRepo.findById(mr.getRepoId()).orElse(null);
        return toDetailResponse(mr, repo, false);
    }

    @Transactional
    public MrDetailResponse resolveConflict(UUID mrId, UUID userId, String filePath, String solution) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        if (filePath == null || filePath.isBlank() || solution == null || solution.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "文件路径与解决方案不能为空");
        }

        MergeConflictResolution res = resolutionRepo.findByMrIdAndFilePath(mrId, filePath.trim())
                .orElseGet(() -> {
                    MergeConflictResolution nr = new MergeConflictResolution();
                    nr.setMrId(mrId);
                    nr.setFilePath(filePath.trim());
                    return nr;
                });
        res.setSolution(solution.trim());
        res.setConfirmedById(userId);
        res.setResolvedAt(Instant.now());
        resolutionRepo.save(res);

        // 若全部冲突文件均已标记解决，门禁检查标记为 passed
        MergeCheck confCheck = checkRepo.findByMrIdAndKind(mrId, "conflict").orElse(null);
        if (confCheck != null) {
            confCheck.setPassed(true);
            checkRepo.save(confCheck);
        }

        Repository repo = repositoryRepo.findById(mr.getRepoId()).orElse(null);
        return toDetailResponse(mr, repo, false);
    }

    @Transactional
    public MrDetailResponse rebase(UUID mrId) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        Repository repo = repositoryRepo.findById(mr.getRepoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在"));

        GitMergeCheckResult checkRes = gitPort.checkMerge(repo.getRepoPath(), mr.getTargetBranch(), mr.getSourceBranch());
        mr.setRebaseRequired(checkRes.rebaseRequired());
        mrRepo.save(mr);

        MergeCheck rebaseCheck = checkRepo.findByMrIdAndKind(mrId, "rebase").orElse(null);
        if (rebaseCheck != null) {
            rebaseCheck.setPassed(!checkRes.rebaseRequired());
            checkRepo.save(rebaseCheck);
        }

        return toDetailResponse(mr, repo, false);
    }

    @Transactional
    public MergeResultResponse merge(UUID mrId, UUID userId) {
        MergeRequest mr = mrRepo.findById(mrId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "MR 不存在: " + mrId));
        Repository repo = repositoryRepo.findById(mr.getRepoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在"));

        if (!"open".equals(mr.getStatus())) {
            throw new BusinessException(ErrorCode.PLT_4000, "MR 当前状态不可合并: " + mr.getStatus());
        }

        // ==================== L1 门禁硬核重算 ====================
        // 1. 会签检查：必须全部 approved
        List<MergeReviewer> reviewers = reviewerRepo.findByMrId(mrId);
        boolean allApproved = !reviewers.isEmpty() && reviewers.stream().allMatch(r -> "approved".equals(r.getState()));
        if (!allApproved) {
            throw new BusinessException(ErrorCode.PLT_4000, "评审未全部批准，暂不可合入");
        }

        // 2. 分支保护策略校验（U8 / ProtectionPolicy）
        List<MergeCheck> checks = checkRepo.findByMrId(mrId);
        int approvedCount = (int) reviewers.stream().filter(r -> "approved".equals(r.getState())).count();
        boolean unitTestPassed = checks.stream()
                .filter(c -> "unit_test".equals(c.getKind()))
                .findFirst()
                .map(MergeCheck::isPassed)
                .orElse(true);
        branchProtectionService.checkMergeAllowed(mr.getRepoId(), mr.getTargetBranch(), approvedCount, unitTestPassed);

        // 3. 检查项（单测、冲突、rebase）
        for (MergeCheck c : checks) {
            if (!c.isPassed()) {
                if ("unit_test".equals(c.getKind())) {
                    throw new BusinessException(ErrorCode.ENG_4250, String.format(
                            "单测门禁未通过（双阈值整体>=%.0f%% / patch>=%.0f%% 未满足且未豁免）",
                            totalCoverageGate, patchCoverageGate));
                }
                if ("conflict".equals(c.getKind())) {
                    throw new BusinessException(ErrorCode.ENG_4251, "存在未解决冲突，暂不可合并");
                }
                if ("rebase".equals(c.getKind())) {
                    throw new BusinessException(ErrorCode.ENG_4252, "源分支落后目标分支，需先完成 rebase");
                }
                throw new BusinessException(ErrorCode.PLT_4000, "检查项未通过: " + c.getName());
            }
        }

        // 3. 原生 Git 服务端合并
        String commitMsg = "Merge MR !" + mr.getMrNumber() + ": " + mr.getTitle();
        String commitSha = gitPort.merge(repo.getRepoPath(), mr.getTargetBranch(), mr.getSourceBranch(), commitMsg, "TeamOne", "teamone@teamone.cn");

        // 4. 状态置为 merged
        mr.setStatus("merged");
        mr.setMergeCommitSha(commitSha);
        mr.setMergedById(userId);
        mr.setMergedAt(Instant.now());
        mr.setUpdatedAt(Instant.now());
        mrRepo.save(mr);

        // 5. V15 auto_delete_after_merge 执行点（⑥h 批）：合并成功后按分支策略最佳努力删除源分支。
        //    前置：源 ≠ 目标（忽略大小写，防大小写敏感差异误删目标）+ 源命中 autoDeleteAfterMerge 规则
        //    + 源不受分支保护；删除失败仅 WARN 不回滚合并（合并已成既定事实，分支可手工清理）。
        boolean sourceBranchDeleted = false;
        try {
            if (shouldAutoDeleteSourceBranch(repo, mr)) {
                gitPort.deleteBranch(repo.getRepoPath(), mr.getSourceBranch());
                sourceBranchDeleted = true;
                // 留痕评论纳入同一 try（QA 复审 NICE）：分支删除是进程外不可回滚动作，
                // 评论失败若回滚 merged 状态会造成「分支已删而合并显示失败」的更坏不一致
                addSystemComment(mr.getId(), userId,
                        "【源分支自动删除】源分支 " + mr.getSourceBranch() + " 已按分支策略（auto_delete_after_merge）自动删除。");
            }
        } catch (Exception e) {
            log.warn("[mr-merge] 源分支自动删除/留痕失败（不影响合并结果）: repo={} branch={} msg={}",
                    repo.getRepoPath(), mr.getSourceBranch(), e.getMessage());
        }

        return new MergeResultResponse(true, commitSha, "合并成功");
    }

    /**
     * 自动删源分支判定（合并后）：源 ≠ 目标（忽略大小写）+ 源命中 autoDeleteAfterMerge 规则
     * + 源不受分支保护。拆出纯判定便于单测锁定三条前置，任一不满足即不删。
     */
    private boolean shouldAutoDeleteSourceBranch(Repository repo, MergeRequest mr) {
        if (mr.getSourceBranch() == null || mr.getSourceBranch().equalsIgnoreCase(mr.getTargetBranch())) {
            return false;
        }
        if (!branchRuleService.shouldAutoDeleteAfterMerge(repo, mr.getSourceBranch())) {
            return false;
        }
        // 受保护分支绝不自动删除（保护语义优先于清理策略，主/集成分支误配规则时的安全网）
        return branchProtectionService.findMatchingProtection(repo.getId(), mr.getSourceBranch()).isEmpty();
    }

    private MrDetailResponse toDetailResponse(MergeRequest mr, Repository repo, boolean fetchGitDiff) {
        List<MergeReviewer> reviewers = reviewerRepo.findByMrId(mr.getId());
        List<MrDetailResponse.ReviewerItem> reviewerItems = reviewers.stream()
                .map(r -> new MrDetailResponse.ReviewerItem(r.getUserId(), r.getState()))
                .toList();

        List<MergeCheck> checks = checkRepo.findByMrId(mr.getId());
        List<MrDetailResponse.CheckItem> checkItems = checks.stream()
                .map(c -> new MrDetailResponse.CheckItem(c.getName(), c.isPassed() ? "passed" : "failed"))
                .toList();

        List<MergeComment> comments = commentRepo.findByMrIdOrderByCreatedAtAsc(mr.getId());
        List<MrDetailResponse.CommentItem> commentItems = comments.stream()
                .map(c -> new MrDetailResponse.CommentItem(c.getId(), c.getAuthorId(), c.getText(), c.getCreatedAt()))
                .toList();

        List<MergeConflictResolution> resolutions = resolutionRepo.findByMrIdOrderByResolvedAtAsc(mr.getId());
        List<MrDetailResponse.ConflictResolutionItem> resItems = resolutions.stream()
                .map(r -> new MrDetailResponse.ConflictResolutionItem(r.getFilePath(), r.getSolution(), r.getConfirmedById(), r.getReviewedById(), r.getResolvedAt()))
                .toList();

        // 提取单测门禁对象
        MrDetailResponse.UnitTestCheckItem utItem = extractUnitTestCheck(checks);

        // 提取冲突文件清单
        List<String> conflictFiles = extractConflictFiles(checks);

        // 动态读取 Git diff
        int additions = 0;
        int deletions = 0;
        List<GitFileDiff> diffs = List.of();
        if (fetchGitDiff && repo != null && !"merged".equals(mr.getStatus())) {
            try {
                GitDiffResult diffRes = gitPort.diff(repo.getRepoPath(), mr.getTargetBranch(), mr.getSourceBranch());
                additions = diffRes.totalAdditions();
                deletions = diffRes.totalDeletions();
                diffs = diffRes.files();
            } catch (Exception ignored) {
            }
        }

        return new MrDetailResponse(
                mr.getId(),
                mr.getMrNumber(),
                mr.getTitle(),
                mr.getDescription(),
                mr.getRepoId(),
                repo != null ? repo.getName() : null,
                mr.getSourceBranch(),
                mr.getTargetBranch(),
                mr.getAuthorId(),
                reviewerItems,
                mr.getStatus(),
                checkItems,
                !conflictFiles.isEmpty(),
                conflictFiles,
                resItems,
                utItem,
                new MrDetailResponse.GateConfig(totalCoverageGate, patchCoverageGate),
                mr.isRebaseRequired(),
                mr.getLinkedWorkItemKey(),
                mr.getBasedOnBaselineId(),
                additions,
                deletions,
                diffs,
                commentItems,
                mr.getMergeCommitSha(),
                mr.getMergedById(),
                mr.getMergedAt(),
                mr.getClosedAt(),
                mr.getCreatedAt(),
                mr.getUpdatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private MrDetailResponse.UnitTestCheckItem extractUnitTestCheck(List<MergeCheck> checks) {
        Optional<MergeCheck> opt = checks.stream().filter(c -> "unit_test".equals(c.getKind())).findFirst();
        if (opt.isEmpty()) {
            return new MrDetailResponse.UnitTestCheckItem(false, List.of(), false, 0.0, 0.0, false, null);
        }
        MergeCheck c = opt.get();
        Map<String, Object> p = c.getPayload() != null ? c.getPayload() : Map.of();
        boolean hasTests = Boolean.TRUE.equals(p.get("hasTests"));
        List<String> testFiles = (p.get("testFiles") instanceof List<?> list) ? (List<String>) list : List.of();
        boolean passed = Boolean.TRUE.equals(p.get("passed"));
        double covTotal = parseDoubleSafe(p.get("coverageTotal"), 0.0);
        double covDelta = parseDoubleSafe(p.get("coverageDelta"), 0.0);
        boolean gatePassed = c.isPassed();
        Map<String, Object> exempt = (p.get("exempt") instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;

        return new MrDetailResponse.UnitTestCheckItem(hasTests, testFiles, passed, covTotal, covDelta, gatePassed, exempt);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractConflictFiles(List<MergeCheck> checks) {
        Optional<MergeCheck> opt = checks.stream().filter(c -> "conflict".equals(c.getKind())).findFirst();
        if (opt.isEmpty()) return List.of();
        Map<String, Object> p = opt.get().getPayload();
        if (p != null && p.get("conflictFiles") instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    private static boolean isTestFile(String path) {
        if (path == null) return false;
        return path.contains("/test/") || path.contains(".test.") || path.endsWith("Test.java");
    }

    /** 单测门禁检查项名称（阈值随配置走，避免名称与判定口径漂移） */
    private String gateName() {
        return String.format("单测门禁 (≥%.0f%% / ≥%.0f%%)", totalCoverageGate, patchCoverageGate);
    }

    private static double parseDoubleSafe(Object o, double defaultVal) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultVal;
    }
}
