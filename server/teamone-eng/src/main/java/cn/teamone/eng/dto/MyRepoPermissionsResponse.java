package cn.teamone.eng.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 我在本仓库的权限能力位响应（⑥j-A M-b · docs/v2/13 §4.5，与并行前端批契约严格一致）。
 *
 * <p>字段契约：{@code role} 恒出现（无仓库角色时为 JSON null——全局 non_null 序列化下
 * Map 形态会丢字段，故用 DTO + ALWAYS 钉住）；{@code platformAdmin} 仅平台 OWNER/ADMIN
 * 短路时出现（true）；{@code capabilities} 为 14 动作目录序的实际放行集合。</p>
 *
 * @author Ivan Yang, 2026-09-19
 */
public record MyRepoPermissionsResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS) String role,
        List<String> capabilities,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean platformAdmin) {
}
