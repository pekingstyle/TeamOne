package cn.teamone.platform.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 仓库成员视图（⑥i-A1 M-a · members 三端点契约，与并行前端批严格一致）。
 *
 * <p>JSON 键即记录组件名：userId / username / displayName / role / source /
 * grantedByName / grantedAt；role 为小写线格式（owner/maintainer/developer/reporter），
 * source 为大写（DIRECT/INHERITED/GROUP，INHERITED 行由前端标注「建仓人」徽标）。
 * grantedByName 为授予人显示名（系统授予/无授予人时为 null，Jackson non_null 契约下省略键）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
public record RepoMemberDto(
        UUID userId,
        String username,
        String displayName,
        String role,
        String source,
        String grantedByName,
        Instant grantedAt
) {}
