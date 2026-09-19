package cn.teamone.eng.dto;

public record MergeResultResponse(
        boolean ok,
        String mergeCommitSha,
        String message
) {}
