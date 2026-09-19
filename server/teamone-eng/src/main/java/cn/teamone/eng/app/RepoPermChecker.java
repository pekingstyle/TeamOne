package cn.teamone.eng.app;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import cn.teamone.eng.domain.Repository;
import cn.teamone.platform.authz.RepoAclService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.authz.RepoRole;
import cn.teamone.platform.domain.AppUser;

/**
 * 仓库权限回退检查器（⑥i-A1 M-a · docs/v2/13 §4.4 两路定位器之二）。
 *
 * <p>无 {@code /repos} 前缀的端点（如 /commits?workItemKey= 经 commit_work_item 解析所属仓库、
 * /mrs/{id}/blame 经 MR→目标仓解析）注解不可定位，必须服务层回退显式调用本检查器——
 * 这是 M-b 全端点接入（B1/B2）的既定模式；M-a 先立地基并附单测，不改动任何存量端点行为
 * （零收紧口径）。</p>
 *
 * <p>薄封装：判定真相始终在 platform {@link RepoAclService} 五步链（含决策缓存），
 * 本类只为 eng 服务层提供语义化入口，避免 eng 各服务直接耦合 platform authz 细节。</p>
 *
 * <p>M-b（⑥j-A）新增三族入口：① 角色下限（§2.4 派生口径：close/reopen、单测豁免的
 * 「Maintainer+」）；② 跨仓列表 PRIVATE 过滤（B4：GET /mrs、/pipelines、/commits 结果集
 * 逐仓剔除，复用 GET /repos 既有过滤语义）；③ me/permissions 能力位数据源（§4.5 契约）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@Component
public class RepoPermChecker {

    private final RepoAclService acl;

    public RepoPermChecker(RepoAclService acl) {
        this.acl = acl;
    }

    /** 判定（不抛异常版）：供结果集过滤（逐仓剔除 / 列表过滤）等需要布尔结果的场景 */
    public boolean check(UUID userId, UUID repoId, String action) {
        return acl.checkRepoPerm(userId, repoId, action);
    }

    /** 断言（抛 403 T1-PLT-4030 版）：服务层回退端点的统一强制入口 */
    public void require(UUID userId, UUID repoId, String action) {
        acl.require(userId, repoId, action);
    }

    // ==================== ⑥j-A M-b 扩展 ====================

    /** 平台管理员短路（结果集过滤的整体跳过判定；五步链第 1 步的调用侧前移，纯加速） */
    public static boolean isPlatformAdmin(AppUser me) {
        return me != null && (me.getPlatformRole() == AppUser.PlatformRole.OWNER
                || me.getPlatformRole() == AppUser.PlatformRole.ADMIN);
    }

    /**
     * 跨仓列表 PRIVATE 过滤（B4 · docs/v2/13 §5.2 清单）：PRIVATE 仓仅对「五步链 view 命中者
     * （repo_member ∨ 平台 OWNER/ADMIN）」可见；INTERNAL/PUBLIC 全员可读直接放行（不逐仓查链，
     * 与链内 visibility 兜底语义等价）。me=null 视为不可达防御分支放行（/api/** 已authenticated）。
     */
    public List<Repository> filterVisible(AppUser me, Collection<Repository> repos) {
        if (me == null || isPlatformAdmin(me)) {
            return List.copyOf(repos);
        }
        return repos.stream()
                .filter(r -> !"PRIVATE".equalsIgnoreCase(r.getVisibility())
                        || acl.checkRepoPerm(me.getId(), r.getId(), RepoActions.VIEW))
                .toList();
    }

    /** 角色下限判定（§2.4「Maintainer+」派生口径；等价性见 RepoAclService#checkRoleAtLeast） */
    public boolean checkRoleAtLeast(UUID userId, UUID repoId, RepoRole floor) {
        return acl.checkRoleAtLeast(userId, repoId, floor);
    }

    /** 角色下限断言（抛 403）：scene 为业务场景文案（如「关闭评审」「单测豁免」） */
    public void requireRoleAtLeast(UUID userId, UUID repoId, RepoRole floor, String scene) {
        acl.requireRoleAtLeast(userId, repoId, floor, scene);
    }

    /** 我在仓库的有效角色（me/permissions 数据源；null=无角色仅剩 visibility 兜底只读） */
    public RepoRole effectiveRoleOf(UUID userId, UUID repoId) {
        return acl.effectiveRoleOf(userId, repoId);
    }

    /** 我在仓库的实际放行动作集合（me/permissions 数据源；14 动作逐个走五步链，目录序） */
    public List<String> capabilitiesOf(UUID userId, UUID repoId) {
        return acl.capabilitiesOf(userId, repoId);
    }
}
