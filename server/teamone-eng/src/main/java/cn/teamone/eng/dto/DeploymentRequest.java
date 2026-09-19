package cn.teamone.eng.dto;

import java.util.UUID;

/**
 * 部署登记请求 DTO（R-9 发布一致性 · B2 批，POST /api/v1/releases/{id}/deployments）。
 *
 * @param env             部署环境：dev / staging / prod（必填，白名单校验）
 * @param artifactVersion 部署制品版本（可选，如 2.4.0-rc.3）
 * @param note            备注（可选）
 * @param pipelineRunId   关联流水线运行 ID（可选；提供且该运行未挂版本时回填其 release_id）
 */
public record DeploymentRequest(
        String env,
        String artifactVersion,
        String note,
        UUID pipelineRunId
) {}
