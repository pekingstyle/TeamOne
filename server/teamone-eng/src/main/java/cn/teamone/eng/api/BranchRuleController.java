package cn.teamone.eng.api;

import cn.teamone.eng.app.BranchRuleService;
import cn.teamone.eng.dto.BranchRuleItem;
import cn.teamone.eng.dto.SaveBranchRulesRequest;
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
 * <p>权限与既有 eng 写端点一致（登录态，网关统一鉴权）。
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
     */
    @GetMapping
    public List<BranchRuleItem> listRules(@PathVariable String idOrName) {
        return branchRuleService.listRules(idOrName);
    }

    /**
     * 整仓替换式保存（事务内先删后插），200 返回保存后列表。
     */
    @PutMapping
    public List<BranchRuleItem> saveRules(
            @PathVariable String idOrName,
            @RequestBody SaveBranchRulesRequest req) {
        return branchRuleService.saveRules(idOrName, req);
    }

    /**
     * 删除指定分支类型的规则（成功 204，规则不存在 404）。
     */
    @DeleteMapping("/{branchType}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable String idOrName,
            @PathVariable String branchType) {
        branchRuleService.deleteRule(idOrName, branchType);
        return ResponseEntity.noContent().build();
    }
}
