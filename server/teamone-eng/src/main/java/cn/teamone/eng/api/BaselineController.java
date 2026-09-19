package cn.teamone.eng.api;

import cn.teamone.eng.app.BaselineService;
import cn.teamone.eng.dto.BaselineResponse;
import cn.teamone.eng.dto.CreateBaselineRequest;
import cn.teamone.platform.domain.AppUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 基线管理 REST 控制器（FR-v2-13 / R9 / U10；M2-INC-3 V-15 验收基准）。
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1")
public class BaselineController {

    private final BaselineService baselineService;

    public BaselineController(BaselineService baselineService) {
        this.baselineService = baselineService;
    }

    /**
     * 查询指定仓库下所有的质量/工程基线列表。
     *
     * @param idOrName 仓库 ID 或仓库短名称（如 "teamone"）
     * @return 该仓库下的全部基线响应 DTO 列表（按创建时间倒序）
     */
    @GetMapping("/repos/{idOrName}/baselines")
    public List<BaselineResponse> listBaselines(@PathVariable String idOrName) {
        return baselineService.listBaselines(idOrName);
    }

    /**
     * 在指定仓库下新建基线（初始为 draft 草稿状态）。
     *
     * @param idOrName 仓库 ID 或仓库短名称
     * @param me       当前登录用户
     * @param req      基线新建请求体（名称、类型、TagRef、版本号、关联需求快照等）
     * @return 新建完成的基线响应 DTO
     */
    @PostMapping("/repos/{idOrName}/baselines")
    public BaselineResponse createBaseline(
            @PathVariable String idOrName,
            @AuthenticationPrincipal AppUser me,
            @RequestBody CreateBaselineRequest req) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return baselineService.createBaseline(idOrName, req, userId);
    }

    /**
     * 查询单条基线的详细信息与审批进展。
     *
     * @param id 基线唯一标识 UUID
     * @return 基线详细响应 DTO
     */
    @GetMapping("/baselines/{id}")
    public BaselineResponse getBaseline(@PathVariable UUID id) {
        return baselineService.getBaseline(id);
    }

    /**
     * 将草稿状态（draft）基线提交进入评审审批流（状态转为 in_review）。
     *
     * @param id 基线唯一标识 UUID
     * @return 状态流转后的基线响应 DTO
     */
    @PostMapping("/baselines/{id}/submit")
    public BaselineResponse submitBaseline(@PathVariable UUID id) {
        return baselineService.submitBaseline(id);
    }

    /**
     * 审批通过基线定版（支持双人会签机制）。
     * <p>
     * 当审批通过人数达到 2 人时，系统自动在对应 Commit 上生成 Annotated Tag 并在数据库中将状态冻结为 approved（不可篡改）。
     * </p>
     *
     * @param id 基线唯一标识 UUID
     * @param me 当前登录的审批用户
     * @return 审批操作后的基线响应 DTO
     */
    @PostMapping("/baselines/{id}/approve")
    public BaselineResponse approveBaseline(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUser me) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        String name = (me != null) ? me.getDisplayName() : "TeamOne";
        String email = (me != null) ? me.getEmail() : "teamone@teamone.cn";
        return baselineService.approveBaseline(id, userId, name, email);
    }

    /**
     * 对已定版（approved）基线发起版本废止与升级迭代（supersede）。
     * <p>
     * 原基线状态置为 superseded，并将 supersededById 指向新创建的替代基线，保障工程追溯链条完备。
     * </p>
     *
     * @param id     被替代的原基线 UUID
     * @param me     当前登录用户
     * @param newReq 新一代基线的创建请求参数
     * @return 新生成的基线响应 DTO
     */
    @PostMapping("/baselines/{id}/supersede")
    public BaselineResponse supersedeBaseline(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUser me,
            @RequestBody CreateBaselineRequest newReq) {
        UUID userId = (me != null) ? me.getId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return baselineService.supersedeBaseline(id, newReq, userId);
    }
}
