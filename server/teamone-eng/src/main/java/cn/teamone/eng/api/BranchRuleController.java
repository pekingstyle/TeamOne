package cn.teamone.eng.api;

import cn.teamone.eng.app.BranchRuleService;
import cn.teamone.eng.dto.BranchRuleItem;
import cn.teamone.eng.dto.SaveBranchRulesRequest;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.shared.auth.RequireRepoPerm;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分支规则 REST 端点（分支治理批：分支模型可配置）。
 *
 * <p>ACL 接入（⑥j-A M-b B1）：全端点 {@code /repos/{idOrName}} 前缀走切面 URI 定位——
 * GET → view（§5.2 读端点收口）；PUT/DELETE → manage-settings（<b>批裁决口径</b>：分支模型
 * 属仓库级结构配置，比 §2.2 归入 manage-protection 的保护规则更高一档，仅 Owner——
 * M-b 批映射表明确 branch-rules 写端点挂 manage-settings；§2.2 原文两动作并档的表述
 * 以本批口径为准，IT 矩阵按此断言）。
 * 字段名与前端批接口契约严格一致，勿改。</p>
 *
 * @author Ivan Yang, 2026-09-14
 */
@RestController
@RequestMapping("/api/v1/repos/{idOrName}/branch-rules")
public class BranchRuleController {

    private final BranchRuleService branchRuleService;

    public BranchRuleController(BranchRuleService branchRuleService) {
        this.branchRuleService = branchRuleService;
    }

    /**
     * 查询仓库全部分支规则（无规则返回 []）。
     *
     * <p>ACL（M-b B1）：view（§5.2 读端点收口）。</p>
     */
    @GetMapping
    @RequireRepoPerm(action = RepoActions.VIEW)
    public List<BranchRuleItem> listRules(@PathVariable String idOrName) {
        return branchRuleService.listRules(idOrName);
    }

    /**
     * 整仓替换式保存（事务内先删后插），200 返回保存后列表。
     *
     * <p>ACL（M-b B1）：manage-settings（批口径：仅 Owner；与 protections 的
     * manage-protection 形成「保护 Maintainer+ / 规则 Owner」分级对照）。</p>
     */
    @PutMapping
    @RequireRepoPerm(action = RepoActions.MANAGE_SETTINGS)
    public List<BranchRuleItem> saveRules(
            @PathVariable String idOrName,
            @RequestBody SaveBranchRulesRequest req) {
        return branchRuleService.saveRules(idOrName, req);
    }

    /**
     * 删除指定分支类型的规则（成功 204，规则不存在 404）。
     *
     * <p>ACL（M-b B1）：manage-settings（批口径：仅 Owner）。</p>
     */
    @DeleteMapping("/{branchType}")
    @RequireRepoPerm(action = RepoActions.MANAGE_SETTINGS)
    public ResponseEntity<Void> deleteRule(
            @PathVariable String idOrName,
            @PathVariable String branchType) {
        branchRuleService.deleteRule(idOrName, branchType);
        return ResponseEntity.noContent().build();
    }
}
