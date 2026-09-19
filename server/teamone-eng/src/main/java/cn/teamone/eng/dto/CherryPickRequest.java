package cn.teamone.eng.dto;

/**
 * cherry-pick（部分合并）请求体（POST /api/v1/repos/{idOrName}/cherry-pick）。
 *
 * @param commitSha    待拣选的完整 40 位提交 SHA（小写 hex）
 * @param targetBranch 拣选落地目标分支（如 release/1.2）
 */
public record CherryPickRequest(
        String commitSha,
        String targetBranch
) {}
