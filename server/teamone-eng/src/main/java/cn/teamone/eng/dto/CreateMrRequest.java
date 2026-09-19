package cn.teamone.eng.dto;

import java.util.List;
import java.util.UUID;

public record CreateMrRequest(
        String repoId,
        String title,
        String description,
        String sourceBranch,
        String targetBranch,
        List<UUID> reviewerIds,
        String linkedWorkItemKey,
        UUID basedOnBaselineId
) {}
