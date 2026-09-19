package cn.teamone.eng.app;

import cn.teamone.eng.domain.MergeCheck;
import cn.teamone.eng.domain.MergeComment;
import cn.teamone.eng.domain.MergeRequest;
import cn.teamone.eng.domain.MergeReviewer;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.MergeResultResponse;
import cn.teamone.eng.repo.MergeCheckRepository;
import cn.teamone.eng.repo.MergeCommentRepository;
import cn.teamone.eng.repo.MergeConflictResolutionRepository;
import cn.teamone.eng.repo.MergeRequestRepository;
import cn.teamone.eng.repo.MergeReviewerRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.PermissionDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MergeRequestService 小单测（⑥h 批，纯 Mockito 无 Spring）：
 * 关闭/重开状态机（非法迁移 4xx）、作者/管理员权限口径、
 * V15 auto_delete_after_merge 合并后自动删源分支的三条前置与最佳努力语义。
 *
 * @author Ivan Yang, 2026-09-14
 */
class MergeRequestServiceTest {

    private static final String SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    private MergeRequestRepository mrRepo;
    private MergeReviewerRepository reviewerRepo;
    private MergeCheckRepository checkRepo;
    private MergeCommentRepository commentRepo;
    private RepositoryRepository repositoryRepo;
    private BranchProtectionService branchProtectionService;
    private BranchRuleService branchRuleService;
    private cn.teamone.eng.infra.git.GitPort gitPort;
    private AuditService audit;
    private PermissionService permissions;
    private MergeRequestService service;

    private final UUID authorId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();
    private final UUID mrId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mrRepo = mock(MergeRequestRepository.class);
        reviewerRepo = mock(MergeReviewerRepository.class);
        checkRepo = mock(MergeCheckRepository.class);
        commentRepo = mock(MergeCommentRepository.class);
        repositoryRepo = mock(RepositoryRepository.class);
        branchProtectionService = mock(BranchProtectionService.class);
        branchRuleService = mock(BranchRuleService.class);
        gitPort = mock(cn.teamone.eng.infra.git.GitPort.class);
        audit = mock(AuditService.class);
        permissions = mock(PermissionService.class);

        service = new MergeRequestService(mrRepo, reviewerRepo, checkRepo, commentRepo,
                mock(MergeConflictResolutionRepository.class), repositoryRepo,
                branchProtectionService, branchRuleService, gitPort, audit, permissions, 60, 80);
    }

    private MergeRequest mr(String status) {
        MergeRequest mr = new MergeRequest();
        mr.setId(mrId);
        mr.setRepoId(repoId);
        mr.setMrNumber(7);
        mr.setTitle("t");
        mr.setSourceBranch("feature/x");
        mr.setTargetBranch("develop");
        mr.setAuthorId(authorId);
        mr.setStatus(status);
        return mr;
    }

    private Repository repo() {
        Repository r = new Repository();
        r.setId(repoId);
        r.setName("teamone");
        r.setRepoPath("teamone/teamone.git");
        r.setDefaultBranch("develop");
        return r;
    }

    private void stubFound(MergeRequest mr) {
        when(mrRepo.findById(mrId)).thenReturn(Optional.of(mr));
    }

    /** 作者本人可关闭/重开（无需管理员） */
    private void stubAuthorPermission() {
        // isAuthor 命中即短路，不会触达 permissions.check
    }

    // ==================== A：关闭/重开 ====================

    @Test
    void close_byAuthor_openToClosed_withCommentAndAudit() {
        MergeRequest mr = mr("open");
        stubFound(mr);
        stubAuthorPermission();

        service.closeMr(mrId, authorId);

        assertEquals("closed", mr.getStatus());
        assertNotNull(mr.getClosedAt());
        verify(mrRepo).save(mr);
        ArgumentCaptor<MergeComment> comment = ArgumentCaptor.forClass(MergeComment.class);
        verify(commentRepo).save(comment.capture());
        assertTrue(comment.getValue().getText().contains("关闭评审"));
        verify(audit).record(eq(authorId), eq("mr.close"), eq("mr"), eq(mrId.toString()), any());
    }

    @Test
    void close_byAdminWithoutAuthorship_allowed() {
        MergeRequest mr = mr("draft");
        stubFound(mr);
        when(permissions.check(actorId, "platform", PermissionService.PLATFORM_RESOURCE_ID, "platform:manage"))
                .thenReturn(true);

        service.closeMr(mrId, actorId);

        assertEquals("closed", mr.getStatus());
        verify(audit).record(eq(actorId), eq("mr.close"), anyString(), anyString(), any());
    }

    @Test
    void close_byOutsider_denied403() {
        stubFound(mr("open"));
        when(permissions.check(actorId, "platform", PermissionService.PLATFORM_RESOURCE_ID, "platform:manage"))
                .thenReturn(false);

        PermissionDeniedException e = assertThrows(PermissionDeniedException.class,
                () -> service.closeMr(mrId, actorId));
        assertEquals(403, e.errorCode().httpStatus());
        verify(mrRepo, never()).save(any());
    }

    @Test
    void close_illegalTransitions_mergedOrClosed_rejected4xx() {
        // merged 不可关闭
        stubFound(mr("merged"));
        BusinessException e1 = assertThrows(BusinessException.class, () -> service.closeMr(mrId, authorId));
        assertEquals(400, e1.errorCode().httpStatus());
        assertTrue(e1.getMessage().contains("不可关闭"));
        // closed 重复关闭同样拒绝
        stubFound(mr("closed"));
        BusinessException e2 = assertThrows(BusinessException.class, () -> service.closeMr(mrId, authorId));
        assertEquals(400, e2.errorCode().httpStatus());
    }

    @Test
    void reopen_closedToOpen_resetsClosedAt() {
        MergeRequest mr = mr("closed");
        mr.setClosedAt(java.time.Instant.now());
        stubFound(mr);

        service.reopenMr(mrId, authorId);

        assertEquals("open", mr.getStatus());
        assertNull(mr.getClosedAt());
        ArgumentCaptor<MergeComment> comment = ArgumentCaptor.forClass(MergeComment.class);
        verify(commentRepo).save(comment.capture());
        assertTrue(comment.getValue().getText().contains("重新打开"));
        verify(audit).record(eq(authorId), eq("mr.reopen"), eq("mr"), eq(mrId.toString()), any());
    }

    @Test
    void reopen_illegalTransitions_mergedAndOpen_rejected4xx() {
        // merged 为不可逆终态
        stubFound(mr("merged"));
        BusinessException e1 = assertThrows(BusinessException.class, () -> service.reopenMr(mrId, authorId));
        assertEquals(400, e1.errorCode().httpStatus());
        assertTrue(e1.getMessage().contains("不可重开"));
        // open 状态无需重开
        stubFound(mr("open"));
        BusinessException e2 = assertThrows(BusinessException.class, () -> service.reopenMr(mrId, authorId));
        assertEquals(400, e2.errorCode().httpStatus());
        // 重开也要过权限关（非作者非管理员拒绝）
        stubFound(mr("closed"));
        when(permissions.check(actorId, "platform", PermissionService.PLATFORM_RESOURCE_ID, "platform:manage"))
                .thenReturn(false);
        assertThrows(PermissionDeniedException.class, () -> service.reopenMr(mrId, actorId));
    }

    // ==================== B：合并后自动删源分支 ====================

    /** 组装一个可通过全部门禁的 open MR（1 名 approved 评审、无失败检查项） */
    private void stubMergeGatePassing(MergeRequest mr) {
        MergeReviewer r = new MergeReviewer();
        r.setMrId(mr.getId());
        r.setUserId(UUID.randomUUID());
        r.setState("approved");
        when(reviewerRepo.findByMrId(mr.getId())).thenReturn(List.of(r));
        when(checkRepo.findByMrId(mr.getId())).thenReturn(List.<MergeCheck>of());
        when(repositoryRepo.findById(mr.getRepoId())).thenReturn(Optional.of(repo()));
        when(gitPort.merge(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SHA);
    }

    @Test
    void merge_ruleHitsAndUnprotected_deletesSourceBranchAndComments() {
        MergeRequest mr = mr("open");
        stubFound(mr);
        stubMergeGatePassing(mr);
        when(branchRuleService.shouldAutoDeleteAfterMerge(any(), eq("feature/x"))).thenReturn(true);
        when(branchProtectionService.findMatchingProtection(eq(repoId), eq("feature/x")))
                .thenReturn(Optional.empty());

        MergeResultResponse res = service.merge(mrId, actorId);

        assertTrue(res.ok());
        assertEquals("merged", mr.getStatus());
        verify(gitPort).deleteBranch("teamone/teamone.git", "feature/x");
        ArgumentCaptor<MergeComment> comment = ArgumentCaptor.forClass(MergeComment.class);
        verify(commentRepo).save(comment.capture());
        assertTrue(comment.getValue().getText().contains("源分支已按分支策略自动删除")
                || comment.getValue().getText().contains("auto_delete_after_merge"));
    }

    @Test
    void merge_sourceProtectedOrRuleMiss_orSameAsTarget_neverDeletes() {
        MergeRequest mr = mr("open");
        stubFound(mr);
        stubMergeGatePassing(mr);
        // 规则命中但源分支受保护：保护语义优先，绝不删
        when(branchRuleService.shouldAutoDeleteAfterMerge(any(), eq("feature/x"))).thenReturn(true);
        when(branchProtectionService.findMatchingProtection(eq(repoId), eq("feature/x")))
                .thenReturn(Optional.of(new cn.teamone.eng.domain.BranchProtection()));

        assertTrue(service.merge(mrId, actorId).ok());
        verify(gitPort, never()).deleteBranch(anyString(), anyString());

        // 源 == 目标（即便规则误配）：不删
        MergeRequest same = mr("open");
        same.setSourceBranch("develop");
        same.setTargetBranch("develop");
        stubFound(same);
        stubMergeGatePassing(same);
        when(branchRuleService.shouldAutoDeleteAfterMerge(any(), eq("develop"))).thenReturn(true);
        when(branchProtectionService.findMatchingProtection(eq(repoId), eq("develop")))
                .thenReturn(Optional.empty());
        assertTrue(service.merge(mrId, actorId).ok());
        verify(gitPort, never()).deleteBranch(anyString(), anyString());
    }

    @Test
    void merge_deleteBranchFails_bestEffort_mergeStillSucceeds() {
        MergeRequest mr = mr("open");
        stubFound(mr);
        stubMergeGatePassing(mr);
        when(branchRuleService.shouldAutoDeleteAfterMerge(any(), eq("feature/x"))).thenReturn(true);
        when(branchProtectionService.findMatchingProtection(eq(repoId), eq("feature/x")))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("git boom")).when(gitPort)
                .deleteBranch(anyString(), anyString());

        MergeResultResponse res = service.merge(mrId, actorId);

        // 最佳努力：删除失败仅 WARN，不回滚合并
        assertTrue(res.ok());
        assertEquals(SHA, res.mergeCommitSha());
        assertEquals("merged", mr.getStatus());
        // 删除失败不写「已自动删除」评论（评论与事实一致）
        verify(commentRepo, never()).save(any());
    }
}
