package cn.teamone.platform.authz;

import java.util.Optional;
import java.util.UUID;

/**
 * 仓库可见性解析端口（仓库判定链第 4 步 visibility 只读兜底用）。
 *
 * <p>依赖纪律（ArchUnit R2/R5）：platform 不得依赖 eng，而 visibility 列在 eng.repository——
 * 故 platform 只定义本端口，由 eng 侧（{@code cn.teamone.eng.app.RepoVisibilityResolver}）
 * 实现并经组合根装配注入 {@link RepoAclService}，依赖方向 eng → platform 合法。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
public interface RepoVisibilityPort {

    /**
     * 取仓库 visibility 三态值（INTERNAL/PRIVATE/PUBLIC）。
     *
     * @return visibility 字符串；仓库不存在返回 null（按「不可兜底」处理，默认拒绝）
     */
    String visibilityOf(UUID repoId);
}
