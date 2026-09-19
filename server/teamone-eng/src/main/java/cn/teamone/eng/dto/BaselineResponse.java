package cn.teamone.eng.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BaselineResponse(
        UUID id,
        UUID repoId,
        String repoName,
        String name,
        String type,
        String tagRef,
        String commitSha,
        String artifactVersion,
        String requirementSnapshotId,
        String status,
        UUID supersededById,
        List<UUID> approverIds,
        UUID createdBy,
        Instant createdAt,
        Instant approvedAt
) {}
