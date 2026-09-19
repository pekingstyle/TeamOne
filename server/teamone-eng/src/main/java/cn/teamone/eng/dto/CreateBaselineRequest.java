package cn.teamone.eng.dto;

public record CreateBaselineRequest(
        String name,
        String type, // functional, allocated, product
        String tagRef,
        String targetRefOrSha,
        String artifactVersion,
        String requirementSnapshotId,
        String description
) {}
