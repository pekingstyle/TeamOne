package cn.teamone.eng.api;

import cn.teamone.eng.app.WorktreeReportService;
import cn.teamone.eng.dto.WorktreeReportRequest;
import cn.teamone.eng.dto.WorktreeReportResponse;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.auth.RequireRepoPerm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 工作副本 WorkTree REST 控制器（FR-v2-10 / R7 / M2-INC-3 V-16）。
 *
 * <p>ACL 接入（⑥j-A M-b B1）：读端点（{@code /repos/{idOrName}/worktrees}）→ view
 * （§2.4 Worktree 行「读端点 view」）；上报端点维持「登录用户上报本人条目」口径
 * （§2.4：owner=自己的留痕语义，非仓库角色动作，本批不动）。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1")
public class WorktreeController {

    private final WorktreeReportService worktreeService;

    public WorktreeController(WorktreeReportService worktreeService) {
        this.worktreeService = worktreeService;
    }

    /**
     * 查询指定代码仓库当前已上报的所有活跃本地工作副本列表。
     * <p>
     * 对应前端「代码仓详情 -> 工作树」页面，返回每个本地副本的分支、路径、领先/落后提交数与未提交改动。
     * </p>
     *
     * <p>ACL（M-b B1）：view（§2.4 Worktree 行读端点口径；切面 URI 定位）。</p>
     *
     * @param idOrName 仓库 UUID 或仓库名称（如 "teamone"）
     * @return 活跃工作副本列表（按最近一次心跳活跃时间倒序排列）
     */
    @GetMapping("/repos/{idOrName}/worktrees")
    @RequireRepoPerm(action = RepoActions.VIEW)
    public List<WorktreeReportResponse> listWorktrees(@PathVariable String idOrName) {
        return worktreeService.listWorktrees(idOrName);
    }

    /**
     * 上报指定仓库的开发者本地工作副本数据。
     * <p>
     * 客户端（如 CLI 守护进程或开发工具钩子）以此端点周期性同步本地 git worktree 信息。
     * 依据 (repoId, localPath) 自动执行新增建档或心跳续期更新。
     * </p>
     *
     * @param idOrName 目标仓库 UUID 或名称
     * @param me       当前登录用户（Spring Security 认证上下文注入），用于标注工作副本所有者
     * @param req      工作副本状态指标（路径、分支、dirtyFileCount、ahead、behind 等）
     * @return 存储并更新后的工作副本详情
     */
    @PostMapping("/repos/{idOrName}/worktrees/report")
    public WorktreeReportResponse reportWorktree(
            @PathVariable String idOrName,
            @AuthenticationPrincipal AppUser me,
            @RequestBody WorktreeReportRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return worktreeService.reportWorktree(idOrName, req, userId);
    }

    /**
     * 工作副本全局上报入口（支持通过 query 参数传递仓库标识）。
     *
     * @param repoIdOrName 仓库 UUID 或名称（通过 URL query 参数指定）
     * @param me           当前登录用户
     * @param req          工作副本状态指标
     * @return 存储并更新后的工作副本详情
     */
    @PostMapping("/worktrees/report")
    public WorktreeReportResponse reportWorktreeGlobal(
            @RequestParam String repoIdOrName,
            @AuthenticationPrincipal AppUser me,
            @RequestBody WorktreeReportRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return worktreeService.reportWorktree(repoIdOrName, req, userId);
    }
}
