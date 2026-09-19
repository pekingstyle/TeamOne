package cn.teamone.eng.dto;

public record ReviewMrRequest(
        String state, // approved, changes_requested
        String comment
) {}
