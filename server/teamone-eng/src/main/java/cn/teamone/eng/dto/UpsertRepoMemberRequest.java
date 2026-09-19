package cn.teamone.eng.dto;

/**
 * 仓库成员授予/改角色请求体（⑥i-A1 M-a · PUT /api/v1/repos/{idOrName}/members）。
 *
 * <p>契约：role 仅收 maintainer / developer / reporter（owner 不经 API 授予——
 * 建仓自动产生 INHERITED 行；对 Owner 目标的任何授予都会因「最后 Owner 保护」409）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
public record UpsertRepoMemberRequest(
        String userId,
        String role
) {}
