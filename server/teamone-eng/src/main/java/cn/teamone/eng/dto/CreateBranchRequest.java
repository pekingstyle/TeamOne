package cn.teamone.eng.dto;

/**
 * 创建分支请求体（POST /api/v1/repos/{idOrName}/branches）。
 *
 * @param name     新分支名（如 feature/x）
 * @param startRef 起点 ref（可省略=默认分支头）
 */
public record CreateBranchRequest(
        String name,
        String startRef
) {}
