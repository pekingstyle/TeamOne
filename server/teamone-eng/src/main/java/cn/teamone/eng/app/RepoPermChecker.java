package cn.teamone.eng.app;

import java.util.UUID;

import org.springframework.stereotype.Component;

import cn.teamone.platform.authz.RepoAclService;

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
}
