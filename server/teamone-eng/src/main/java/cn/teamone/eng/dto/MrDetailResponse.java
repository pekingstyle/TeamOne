package cn.teamone.eng.dto;

import cn.teamone.eng.infra.git.GitFileDiff;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MrDetailResponse(
        UUID id,
        Integer number,
        String title,
        String description,
        UUID repoId,
        String repoName,
        String sourceBranch,
        String targetBranch,
        UUID authorId,
        List<ReviewerItem> reviewers,
        String status,
        List<CheckItem> checks,
        boolean conflicts,
        List<String> conflictFiles,
        List<ConflictResolutionItem> conflictResolutions,
        UnitTestCheckItem unitTestCheck,
        GateConfig gateConfig,
        boolean rebaseRequired,
        String linkedWorkItemKey,
        UUID basedOnBaselineId,
        int additions,
        int deletions,
        List<GitFileDiff> diffs,
        List<CommentItem> comments,
        String mergeCommitSha,
        UUID mergedById,
        Instant mergedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public record ReviewerItem(
            UUID userId,
            String state
    ) {}

    public record CheckItem(
            String name,
            String status // passed, failed, running, pending
    ) {}

    public record ConflictResolutionItem(
            String filePath,
            String solution,
            UUID confirmedById,
            UUID reviewedById,
            Instant resolvedAt
    ) {}

    public record UnitTestCheckItem(
            boolean hasTests,
            List<String> testFiles,
            boolean passed,
            double coverageTotal,
            double coverageDelta,
            boolean gatePassed,
            Map<String, Object> exempt
    ) {}

    /** 单测门禁双阈值（teamone.mr.gate.total-coverage / patch-coverage，默认 60/80） */
    public record GateConfig(
            double totalCoverage,
            double patchCoverage
    ) {}

    public record CommentItem(
            UUID id,
            UUID authorId,
            String text,
            Instant createdAt
    ) {}
}
