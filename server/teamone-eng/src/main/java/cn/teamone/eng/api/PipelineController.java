package cn.teamone.eng.api;

import cn.teamone.eng.app.PipelineService;
import cn.teamone.eng.dto.PipelineRunResponse;
import cn.teamone.eng.dto.TriggerPipelineRequest;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.auth.RequireRepoPerm;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * CI 流水线 REST 控制器（07 §2.4 / 05 §2.3 / M2-INC-3 V-16）。
 *
 * <p>ACL 接入（⑥j-A M-b B1/B4 · docs/v2/13 §2.2）：{@code POST /repos/{idOrName}/pipelines/trigger}
 * 走切面 URI 定位 → trigger-pipeline（Developer+）；无 /repos 前缀端点（GET /pipelines、
 * GET /pipelines/{id}、POST /pipelines/{id}/rerun）控制器内把当前用户传入服务层回退
 * （run/参数→关联仓库归一）：查询→view（跨仓列表含 PRIVATE 结果集过滤 B4）、rerun→trigger-pipeline。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /**
     * 查询 CI 流水线运行列表。
     * <p>
     * 支持多维度组合筛选（所属仓库、当前执行状态）并按最新创建时间降序分页返回。
     * </p>
     *
     * <p>ACL（M-b B1/B4）：view + 跨仓 PRIVATE 结果集过滤（服务层回退，详见
     * {@link PipelineService#listPipelines}；PRIVATE 仓运行记录对无 view 者剔除）。</p>
     *
     * @param me           当前登录用户（跨仓过滤判定主体）
     * @param repoIdOrName 可选，按目标仓库 ID 或名称过滤（例如 "teamone" 或 UUID）
     * @param status       可选，按运行状态过滤（如 "passed", "failed", "running" 等）
     * @param page         当前页码（从 1 开始，缺省为 1）
     * @param size         每页数据条数（缺省为 20）
     * @return 包含数据项列表 items、总数 total、当前页 page、页大小 size 的分页响应对象
     */
    @GetMapping("/pipelines")
    public Map<String, Object> listPipelines(
            @AuthenticationPrincipal AppUser me,
            @RequestParam(required = false) String repoIdOrName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PipelineRunResponse> p = pipelineService.listPipelines(me, repoIdOrName, status, page, size);
        return Map.of(
                "items", p.getContent(),
                "total", p.getTotalElements(),
                "page", page,
                "size", size
        );
    }

    /**
     * 查询指定流水线的详细运行信息及作业日志。
     *
     * <p>ACL（M-b B1）：view（服务层回退：run→关联仓库；无 view → 403，§5.2）。</p>
     *
     * @param me 当前登录用户
     * @param id 流水线记录的唯一标识 UUID
     * @return 包含构建阶段、各作业步骤执行状态与实时控制台日志的完整流水线快照
     */
    @GetMapping("/pipelines/{id}")
    public PipelineRunResponse getPipeline(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        return pipelineService.getPipeline(me, id);
    }

    /**
     * 重新触发执行指定的流水线（Rerun）。
     * <p>
     * 重新执行流水线各个 Stage，并自动将通过的单测与覆盖率指标重新同步至关联的 MR（若存在）。
     * </p>
     *
     * <p>ACL（M-b B1）：trigger-pipeline（§2.2「手动触发 / rerun」；服务层回退定位）。</p>
     *
     * @param me 当前登录用户，若未携带身份则回退至内置系统管理员账户
     * @param id 流水线记录的唯一标识 UUID
     * @return 重新触发后的流水线最新运行快照
     */
    @PostMapping("/pipelines/{id}/rerun")
    public PipelineRunResponse rerunPipeline(
            @AuthenticationPrincipal AppUser me,
            @PathVariable UUID id) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return pipelineService.rerunPipeline(me, id, userId);
    }

    /**
     * 为指定仓库手动或按条件触发一次新的流水线构建。
     * <p>
     * 支持指定触发分支、Commit SHA、触发源（manual/push/mr/schedule）以及关联的 MR ID。
     * </p>
     *
     * <p>ACL（M-b B1）：trigger-pipeline（§2.2 生效点「POST pipelines/trigger」；切面 URI 定位，
     * Developer+，Reporter 403 能力缺失）。</p>
     *
     * @param idOrName 仓库 ID 或仓库短名（如 "teamone"）
     * @param me       当前登录用户，若未携带身份则回退至内置系统管理员账户
     * @param req      触发请求体（包含目标分支、Commit、触发源、关联 MR）
     * @return 新创建并执行完成的流水线运行快照
     */
    @PostMapping("/repos/{idOrName}/pipelines/trigger")
    @RequireRepoPerm(action = RepoActions.TRIGGER_PIPELINE)
    public PipelineRunResponse triggerPipeline(
            @PathVariable String idOrName,
            @AuthenticationPrincipal AppUser me,
            @RequestBody(required = false) TriggerPipelineRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return pipelineService.triggerPipeline(idOrName, req, userId);
    }
}
