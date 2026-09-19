package cn.teamone.eng.dto;

/**
 * 分支规则条目（GET /branch-rules 列表项 与 PUT /branch-rules rules[] 元素同一结构，
 * 字段名与前端批接口契约严格一致，勿改）。
 *
 * @param branchType           分支类型（main/develop/release/hotfix/feature/fix/poc/other）
 * @param namePattern          分支名 glob 模式（如 feature/*，至多一个 '*'）
 * @param baseBranch           起源分支（可空）
 * @param mergeTarget          合入目标（可空；非空时 MR 创建强校验）
 * @param allowDirectPush      是否允许绕过 MR 直推
 * @param autoDeleteAfterMerge 合并后是否自动删除源分支
 * @param description          规则说明（可空）
 */
public record BranchRuleItem(
        String branchType,
        String namePattern,
        String baseBranch,
        String mergeTarget,
        boolean allowDirectPush,
        boolean autoDeleteAfterMerge,
        String description
) {}
