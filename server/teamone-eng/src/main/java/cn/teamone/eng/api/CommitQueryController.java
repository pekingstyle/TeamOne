package cn.teamone.eng.api;

import cn.teamone.eng.app.RepoPermChecker;
import cn.teamone.eng.domain.CommitWorkItem;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.CommitWorkItemRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提交查询端点（05 §3.3：GET /api/v1/commits?workItemKey=）。
 *
 * <p>数据源为 eng 自有表 commit_work_item（push hook 解析落库），按 created_at DESC 返回——
 * 工作项详情页「关联提交」清单用。eng 零 prd 依赖：key 文本化，未命中返回空清单
 * （不校验工作项存在性）。</p>
 *
 * <p>ACL 接入（⑥j-A M-b B1/B4 · docs/v2/13 §5.2 清单行）：无 /repos 前缀，注解不可定位
 * （§4.4 定位器②）——控制器内服务层回退：逐行经 commit_work_item.repo_key（=repoPath，
 * hook 落库口径）解析所属仓库，<b>逐仓 view 判定、无 view 仓库的提交行剔除</b>（不整单 403，
 * 避免以可见性差异探测仓库存在）。repo_key 键去重判定；能解析单仓时等价退化为单仓 view 查询。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
@RestController
@RequestMapping("/api/v1/commits")
public class CommitQueryController {

    private final CommitWorkItemRepository commitWorkItems;
    private final RepositoryRepository repositoryRepo;
    private final RepoPermChecker permChecker;

    public CommitQueryController(CommitWorkItemRepository commitWorkItems,
                                 RepositoryRepository repositoryRepo,
                                 RepoPermChecker permChecker) {
        this.commitWorkItems = commitWorkItems;
        this.repositoryRepo = repositoryRepo;
        this.permChecker = permChecker;
    }

    @GetMapping
    public Map<String, Object> byWorkItem(
            @AuthenticationPrincipal AppUser me,
            @RequestParam(required = false) String workItemKey) {
        // 登录态防御（/api/** 已 authenticated；显式 401 不给系统兜底身份过过滤留口子）
        if (me == null) {
            throw new PermissionDeniedException(ErrorCode.PLT_4010, "未认证或凭证已失效", null);
        }
        if (workItemKey == null || workItemKey.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "workItemKey 必填（如 D-88）");
        }
        final UUID userId = me.getId();
        final boolean platformAdmin = RepoPermChecker.isPlatformAdmin(me);
        // repo_key（repoPath）→ 可见性缓存：本请求内键去重，避免逐行重复解析/判定
        Map<String, Boolean> visibleByRepoKey = new HashMap<>();
        List<CommitWorkItem> rows =
                commitWorkItems.findByWorkItemKeyOrderByCreatedAtDesc(workItemKey.trim());
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (CommitWorkItem row : rows) {
            if (!platformAdmin && !visibleByRepoKey.computeIfAbsent(row.getRepoKey(), key -> repoVisible(userId, key))) {
                // 无 view 仓库的提交行剔除（§5.2：结果集过滤，不整单 403）
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("repo", row.getRepoKey());
            item.put("sha", row.getCommitSha());
            item.put("authorName", row.getAuthorName());
            item.put("authorEmail", row.getAuthorEmail());
            item.put("committedAt", row.getCommittedAt());
            item.put("subject", row.getSubject());
            items.add(item);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("workItemKey", workItemKey.trim());
        res.put("count", items.size());
        res.put("items", items);
        return res;
    }

    /**
     * repo_key（repoPath）→ 仓库 view 判定：解析不到仓库的键放行 true（历史脏键无从归属仓库，
     * 挂账注明：hook 侧键与 repository.repo_path 同源规范化后该分支天然消失）。
     */
    private boolean repoVisible(UUID userId, String repoKey) {
        if (repoKey == null || repoKey.isBlank()) {
            return true;
        }
        Repository repo = repositoryRepo.findByRepoPath(repoKey.trim()).orElse(null);
        return repo == null || permChecker.check(userId, repo.getId(), RepoActions.VIEW);
    }
}
