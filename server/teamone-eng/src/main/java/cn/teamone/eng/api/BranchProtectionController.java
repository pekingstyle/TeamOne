package cn.teamone.eng.api;

import cn.teamone.eng.app.BranchProtectionService;
import cn.teamone.eng.dto.BranchProtectionRequest;
import cn.teamone.eng.dto.BranchProtectionResponse;
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
     * @param idOrName 仓库 ID 或仓库短名称（如 "teamone"）
     * @return 分支保护策略列表
     */
    @GetMapping
    public List<BranchProtectionResponse> listProtections(@PathVariable String idOrName) {
        return protectionService.listProtections(idOrName);
    }

    /**
     * 新增或更新指定仓库的分支保护策略。
     *
     * @param idOrName 仓库 ID 或仓库短名称
     * @param req      分支保护配置请求体（分支通配表达式、强制 MR、最少批准人数、单测卡点等）
     * @return 保存成功后的保护规则响应 DTO
     */
    @PostMapping
    public BranchProtectionResponse saveProtection(
            @PathVariable String idOrName,
            @RequestBody BranchProtectionRequest req) {
        return protectionService.saveProtection(idOrName, req);
    }

    /**
     * 删除指定仓库下的一条分支保护规则。
     *
     * @param idOrName     仓库 ID 或仓库短名称
     * @param protectionId 待删除的分支保护策略 UUID
     * @return 包含操作结果状态的操作响应 Map
     */
    @DeleteMapping("/{protectionId}")
    public Map<String, Object> deleteProtection(
            @PathVariable String idOrName,
            @PathVariable UUID protectionId) {
        protectionService.deleteProtection(idOrName, protectionId);
        return Map.of("ok", true, "message", "已删除分支保护规则");
    }
}
