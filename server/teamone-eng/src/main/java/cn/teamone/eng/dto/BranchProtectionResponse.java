package cn.teamone.eng.dto;

import java.time.Instant;
import java.util.UUID;

public record BranchProtectionResponse(
        UUID id,
        UUID repoId,
        String branchPattern,
        boolean requireMr,
        int minApprovals,
        boolean requireUnitTest,
        boolean blockForcePush,
        Instant createdAt,
        Instant updatedAt
) {}
