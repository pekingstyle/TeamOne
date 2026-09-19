package cn.teamone.platform.authz;

import java.util.Locale;
import java.util.Set;

import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;

/**
 * 仓库四级角色能力模板（docs/v2/13 §1.2 / §2.3 / §4.2：代码常量不建表——角色能力是产品语义
 * 而非运维数据，进 DB 会被绕过评审直接改）。
 *
 * <p>能力矩阵逐格照抄文档 §2.3 默认能力全表，owner ⊇ maintainer ⊇ developer ⊇ reporter
 * 单调叠加；判定零查库：{@code role.capabilities.contains(action)}。
 * 平台 OWNER/ADMIN 不在本表内——他们在仓库判定链第 1 步短路（§3.1）。</p>
 *
 * <p>枚举声明顺序即能力升序（REPORTER → OWNER），
 * {@link RepoAclService} 取角色并集最大者时依赖该序（多行命中取高角色，§1.1）。</p>
 *
 * <p>矩阵抽样（✓/✗ 语义锚点，评审基准）：Reporter 只读 + create-mr/review；
 * Developer 不可 delete-branch（Q7 保守值）但有 merge 能力（能否真合并另过六项门禁）；
 * Maintainer 管保护/豁免/部署/基线定版但不可 manage-settings；Owner 独占 manage-settings。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
public enum RepoRole {

    /** 只读 + 参与评审：浏览代码、建 MR（从可读分支）、评审表态、评论 */
    REPORTER(Set.of(
            RepoActions.VIEW, RepoActions.PULL,
            RepoActions.CREATE_MR, RepoActions.REVIEW)),

    /** 开发主力：推代码、建分支、建并合并 MR、触发流水线、创建基线 */
    DEVELOPER(Set.of(
            // Reporter 全集
            RepoActions.VIEW, RepoActions.PULL, RepoActions.CREATE_MR, RepoActions.REVIEW,
            // Developer 新增
            RepoActions.PUSH, RepoActions.CREATE_BRANCH, RepoActions.MERGE,
            RepoActions.TRIGGER_PIPELINE, RepoActions.BASELINE_CREATE)),

    /** 技术管家：管分支保护、豁免门禁、登记部署、基线定版（Developer 全集 + 4 项） */
    MAINTAINER(Set.of(
            // Developer 全集
            RepoActions.VIEW, RepoActions.PULL, RepoActions.CREATE_MR, RepoActions.REVIEW,
            RepoActions.PUSH, RepoActions.CREATE_BRANCH, RepoActions.MERGE,
            RepoActions.TRIGGER_PIPELINE, RepoActions.BASELINE_CREATE,
            // Maintainer 新增
            RepoActions.DELETE_BRANCH, RepoActions.MANAGE_PROTECTION,
            RepoActions.REGISTER_DEPLOYMENT, RepoActions.BASELINE_APPROVE)),

    /** 仓库最高管理者：Maintainer 全集 + manage-settings（成员授权/仓库设置，§2.3 唯一独占格） */
    OWNER(Set.of(
            // Maintainer 全集
            RepoActions.VIEW, RepoActions.PULL, RepoActions.CREATE_MR, RepoActions.REVIEW,
            RepoActions.PUSH, RepoActions.CREATE_BRANCH, RepoActions.MERGE,
            RepoActions.TRIGGER_PIPELINE, RepoActions.BASELINE_CREATE,
            RepoActions.DELETE_BRANCH, RepoActions.MANAGE_PROTECTION,
            RepoActions.REGISTER_DEPLOYMENT, RepoActions.BASELINE_APPROVE,
            // Owner 新增
            RepoActions.MANAGE_SETTINGS));

    private final Set<String> capabilities;

    RepoRole(Set<String> capabilities) {
        this.capabilities = Set.copyOf(capabilities);
    }

    /** 能力位判定（零查库）：该角色是否具备某仓库动作 */
    public boolean can(String action) {
        return capabilities.contains(action);
    }

    /** 能力全集（只读视图，供能力位查询接口 GET /repos/{id}/me/permissions 后续使用） */
    public Set<String> capabilities() {
        return capabilities;
    }

    /** API/DB 线格式（小写，与 V21 DDL CHECK 约束及前端契约一致） */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 线格式解析（大小写不敏感）：非法值抛 PLT_4000（参数校验，而非静默降级）。
     *
     * @param wire  前端/DB 传来的角色串（owner/OWNER 均可）
     * @return 对应枚举
     */
    public static RepoRole fromWire(String wire) {
        if (wire != null && !wire.isBlank()) {
            try {
                return RepoRole.valueOf(wire.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 落入下方统一 400
            }
        }
        throw new BusinessException(ErrorCode.PLT_4000,
                "非法仓库角色: " + wire + "（允许值 owner/maintainer/developer/reporter）");
    }
}
