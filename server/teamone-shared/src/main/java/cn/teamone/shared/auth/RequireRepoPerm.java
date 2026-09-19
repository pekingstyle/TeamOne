package cn.teamone.shared.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仓库级权限声明（五步判定链：ACTIVE → 平台 OWNER/ADMIN 短路 → DENY 优先 →
 * 角色能力位 → visibility 只读兜底 → 默认拒绝，docs/v2/13 §3.1）。
 *
 * <p>仅适用于 {@code /api/v1/repos/{idOrName}/...} 路径端点：切面从请求 URI 提取
 * idOrName（UUID 或仓库名，两路定位器之一）归一为 repoId 后走 {@code RepoAclService.require}。
 * 无 /repos 前缀的端点（如 /commits、/mrs/{id}/blame）注解不可定位，禁止标注本注解，
 * 必须服务层回退显式调用 {@code RepoPermChecker}（§4.4 第二路定位）。
 * action 取值见 platform {@code RepoActions} 目录。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRepoPerm {

    /** 仓库动作（view / push / manage-settings / ...，对齐 RepoActions 目录） */
    String action();
}
