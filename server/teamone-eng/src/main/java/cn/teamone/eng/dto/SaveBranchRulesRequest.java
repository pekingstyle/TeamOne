package cn.teamone.eng.dto;

import java.util.List;

/**
 * 分支规则整仓保存请求体（PUT /api/v1/repos/{idOrName}/branch-rules）。
 *
 * @param model 分支模型标识（gitflow | github-flow | custom，仅为配置意图留痕，不落库）
 * @param rules 规则列表（整仓替换式保存：事务内先删后插）
 */
public record SaveBranchRulesRequest(
        String model,
        List<BranchRuleItem> rules
) {}
