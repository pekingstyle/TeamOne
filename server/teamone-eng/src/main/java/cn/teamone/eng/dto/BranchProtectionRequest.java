package cn.teamone.eng.dto;

public record BranchProtectionRequest(
        String branchPattern,
        boolean requireMr,
        int minApprovals,
        boolean requireUnitTest,
        boolean blockForcePush
) {}
