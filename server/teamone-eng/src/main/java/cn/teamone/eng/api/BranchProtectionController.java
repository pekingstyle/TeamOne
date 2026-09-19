package cn.teamone.eng.api;

import cn.teamone.eng.app.BranchProtectionService;
import cn.teamone.eng.dto.BranchProtectionRequest;
import cn.teamone.eng.dto.BranchProtectionResponse;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.shared.auth.RequireRepoPerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分支保护规则 REST 控制器（FR-v2-14 / U8；M2-INC-3 V-15 验收基准）。
 *
 * <p>ACL 接入（⑥j-A M-b B1）：全端点 {@code /repos/{idOrName}} 前缀走切面 URI 定位——
 * 写端点（POST/DELETE）→ manage-protection（§2.2 生效点「BranchProtectionController 写端点」，
 * 能力矩阵仅 Maintainer+，治理规则本身不再可被任意人改写，堵 G2）；读端点（GET）→ view
 * （§5.2 单仓读端点收口）。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@RestController
@RequestMapping("/api/v1/repos/{idOrName}/protections")
public class BranchProtectionController {

    private final BranchProtectionService protectionService;

    public BranchProtectionController(BranchProtectionService protectionService) {
        this.protectionService = protectionService;
    }

    /**
     * 查询指定仓库配置的所有分支保护策略。
     *
     * <p>ACL（M-b B1）：view（§5.2 读端点收口；PRIVATE 无条目 403）。</p>
     *
     * @param idOrName 仓库 ID 或仓库短名称（如 "teamone"）
     * @return 分支保护策略列表
     */
    @GetMapping
    @RequireRepoPerm(action = RepoActions.VIEW)
    public List<BranchProtectionResponse> listProtections(@PathVariable String idOrName) {
        return protectionService.listProtections(idOrName);
    }

    /**
     * 新增或更新指定仓库的分支保护策略。
     *
     * <p>ACL（M-b B1）：manage-protection（§2.2；Maintainer+，§2.3 矩阵）。</p>
     *
     * @param idOrName 仓库 ID 或仓库短名称
     * @param req      分支保护配置请求体（分支通配表达式、强制 MR、最少批准人数、单测卡点等）
     * @return 保存成功后的保护规则响应 DTO
     */
    @PostMapping
    @RequireRepoPerm(action = RepoActions.MANAGE_PROTECTION)
    public BranchProtectionResponse saveProtection(
            @PathVariable String idOrName,
            @RequestBody BranchProtectionRequest req) {
        return protectionService.saveProtection(idOrName, req);
    }

    /**
     * 删除指定仓库下的一条分支保护规则。
     *
     * <p>ACL（M-b B1）：manage-protection（§2.2；Maintainer+）。</p>
     *
     * @param idOrName     仓库 ID 或仓库短名称
     * @param protectionId 待删除的分支保护策略 UUID
     * @return 包含操作结果状态的操作响应 Map
     */
    @DeleteMapping("/{protectionId}")
    @RequireRepoPerm(action = RepoActions.MANAGE_PROTECTION)
    public Map<String, Object> deleteProtection(
            @PathVariable String idOrName,
            @PathVariable UUID protectionId) {
        protectionService.deleteProtection(idOrName, protectionId);
        return Map.of("ok", true, "message", "已删除分支保护规则");
    }
}
