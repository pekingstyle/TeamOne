package cn.teamone.platform.authz;

/**
 * 仓库级动作目录（docs/v2/13-ACL资源权限设计.md v1.1 §2.2，14 个动作的字符串常量）。
 *
 * <p>动作是 repo_member 角色能力位（{@link RepoRole}）与 {@code @RequireRepoPerm(action=...)}
 * 注解之间的契约词汇：业务代码禁止手写字面量，一律引用本目录（对齐 ErrorCode 目录纪律）。
 * {@code baseline:create / baseline:approve} 为 eng 专属扩展动作，带冒号命名沿文档原文。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
public final class RepoActions {

    /** 仓库可见：列表出现、详情/分支/提交/树/文件等全读端点可读 */
    public static final String VIEW = "view";
    /** clone / fetch 代码（git 传输通道 M-b 后接入） */
    public static final String PULL = "pull";
    /** 直推非保护分支（API 侧：cherry-pick 直写） */
    public static final String PUSH = "push";
    /** 建分支（仍受 branch_rule 名称治理约束） */
    public static final String CREATE_BRANCH = "create-branch";
    /** 删非保护分支 */
    public static final String DELETE_BRANCH = "delete-branch";
    /** 创建 MR 评审单 */
    public static final String CREATE_MR = "create-mr";
    /** 执行合并（过门禁后的合并动作本身） */
    public static final String MERGE = "merge";
    /** 管理分支保护与分支规则 */
    public static final String MANAGE_PROTECTION = "manage-protection";
    /** 仓库设置与成员授权（visibility、默认分支、ACL 面板） */
    public static final String MANAGE_SETTINGS = "manage-settings";
    /** 手动触发 / rerun 流水线 */
    public static final String TRIGGER_PIPELINE = "trigger-pipeline";
    /** 登记部署 */
    public static final String REGISTER_DEPLOYMENT = "register-deployment";
    /** MR 评审表态（approve/request-changes，扩展动作） */
    public static final String REVIEW = "review";
    /** 创建基线与提交送审（扩展动作） */
    public static final String BASELINE_CREATE = "baseline:create";
    /** 基线定版会签 approve 与变更换版 supersede（扩展动作） */
    public static final String BASELINE_APPROVE = "baseline:approve";

    private RepoActions() {
        // 常量目录，禁止实例化
    }
}
