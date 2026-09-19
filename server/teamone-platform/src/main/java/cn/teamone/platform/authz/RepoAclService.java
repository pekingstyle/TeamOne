package cn.teamone.platform.authz;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.RepoMember;
import cn.teamone.platform.domain.RepoMember.Effect;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.RepoMemberRepository;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;

/**
 * 仓库级五步判定链（docs/v2/13 §3.1，⑥i-A1 M-a）——与平台四步链「按动作分级分轨」：
 * 平台级动作走 {@link PermissionService}，仓库级动作走本链，一端点只走一条链、不串联叠加。
 *
 * <pre>
 * checkRepoPerm(userId, repoId, action):
 *   0. 用户 ACTIVE？                         （非 ACTIVE 一律拒绝，复用现状口径）
 *   1. 平台角色 ∈ {OWNER, ADMIN} → ALLOW     （平台管理员短路仓库级：每仓库隐式能力全集）
 *   2. repo_member DENY 条目命中 → DENY      （deny 优先，对齐 resource_acl 的 allowed && !denied）
 *   3. ALLOW 条目命中取最高角色
 *      且 role.capabilities ∋ action → ALLOW （能力位判定零查库，矩阵见 §2.3 / RepoRole）
 *   4. visibility 兜底：action=view 且 visibility ≠ private → ALLOW
 *      （M-a 口径：仅 view 只读兜底；INTERNAL/PUBLIC 全员可读，PRIVATE 不兜底）
 *   5. 默认 DENY（403 T1-PLT-4030 + 审计，审计在端点层越权时落）
 * </pre>
 *
 * <p>依赖说明：visibility 经 {@link RepoVisibilityPort} 解析（platform 不依赖 eng，R2 纪律）；
 * GROUP 组命中为运行时派生不落行（source 枚举预留，M-a 无组行）；
 * 缓存为键版本方案（§4.4 推荐 B）：决策键携带 {@code acl:repo:{repoId}:ver} 版本号，
 * 成员变更 INCR 版本即全量惰性失效，Valkey 故障降级直查 DB（缓存只是加速器）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@Service
public class RepoAclService {

    private final AppUserRepository users;
    private final RepoMemberRepository members;
    private final RepoVisibilityPort visibilityPort;
    private final Optional<DecisionCache> cache;

    public RepoAclService(AppUserRepository users, RepoMemberRepository members,
                          RepoVisibilityPort visibilityPort, Optional<DecisionCache> cache) {
        this.users = users;
        this.members = members;
        this.visibilityPort = visibilityPort;
        this.cache = cache == null ? Optional.empty() : cache;
    }

    /**
     * 仓库级判定入口：先查带版本的决策缓存，miss/缓存不可用回退五步链并回填。
     *
     * @param userId 用户 id
     * @param repoId 仓库 id（eng.repository.id）
     * @param action 仓库动作（{@link RepoActions} 目录）
     * @return 是否放行
     */
    public boolean checkRepoPerm(UUID userId, UUID repoId, String action) {
        // 决策缓存优先（miss/故障回退查库；键版本方案见类注释）
        Optional<DecisionCache> c = cache;
        if (c.isPresent()) {
            DecisionCache dc = c.get();
            Optional<Long> ver = dc.repoVersion(repoId);
            if (ver.isPresent()) {
                Optional<Boolean> hit = dc.getRepoDecision(repoId, ver.get(), userId, action);
                if (hit.isPresent()) {
                    return hit.get();
                }
                boolean allowed = computeChain(userId, repoId, action);
                dc.putRepoDecision(repoId, ver.get(), userId, action, allowed);
                return allowed;
            }
        }
        return computeChain(userId, repoId, action);
    }

    /** 五步判定链本体（无缓存路径）：按序短路，默认拒绝，deny 优先 */
    private boolean computeChain(UUID userId, UUID repoId, String action) {
        // 步骤 0：用户 ACTIVE 校验（非 ACTIVE 一律拒绝）
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != AppUser.Status.ACTIVE) {
            return false;
        }
        // 步骤 1：平台角色 OWNER/ADMIN 短路（若仓库级不短路会出现「平台管理员在某仓库权限
        // 反而低于普通 Developer」的荒谬，§3.1 拍板结论）
        if (user.getPlatformRole() == AppUser.PlatformRole.OWNER
                || user.getPlatformRole() == AppUser.PlatformRole.ADMIN) {
            return true;
        }
        // 步骤 2/3：repo_member 条目判定（deny 优先 → 最高角色能力位）
        List<RepoMember> mine = members.findByRepoId(repoId).stream()
                .filter(m -> userId.equals(m.getSubjectUserId()))
                .toList();
        // 步骤 2：DENY 条目命中即拒（先于任何 ALLOW 与 visibility 兜底）
        if (mine.stream().anyMatch(m -> m.getEffect() == Effect.DENY)) {
            return false;
        }
        // 步骤 3：ALLOW 条目取能力最大角色（枚举声明序 = 能力升序）；能力位判定零查库
        Optional<RepoRole> top = mine.stream()
                .filter(m -> m.getEffect() == Effect.ALLOW)
                .map(RepoMember::getRole)
                .max(java.util.Comparator.comparingInt(RepoRole::ordinal));
        if (top.isPresent() && top.get().can(action)) {
            return true;
        }
        // 步骤 4：visibility 只读兜底（M-a 口径：仅 view；visibility ≠ private 即放行；
        // 仓库不存在（null）按不可兜底处理，落默认拒绝）
        if (RepoActions.VIEW.equals(action)) {
            String visibility = visibilityPort.visibilityOf(repoId);
            if (visibility != null && !"PRIVATE".equalsIgnoreCase(visibility.trim())) {
                return true;
            }
        }
        // 步骤 5：默认 DENY（403 T1-PLT-4030，由 require 抛出）
        return false;
    }

    /** 供切面 / 服务层回退调用的断言式入口：不通过即抛 T1-PLT-4030（403） */
    public void require(UUID userId, UUID repoId, String action) {
        if (!checkRepoPerm(userId, repoId, action)) {
            throw new PermissionDeniedException(
                    ErrorCode.PLT_4030,
                    "无权限：仓库 #" + repoId + " 动作 " + action,
                    List.of("action=" + action, "repoId=" + repoId));
        }
    }

    // ==================== M-b 扩展（⑥j-A · docs/v2/13 §2.4 派生口径 / §4.5 能力位） ====================

    /**
     * 角色下限判定（「Maintainer+」这类角色门槛派生口径，§2.4：MR close/reopen、单测豁免）。
     *
     * <p>实现等价性：{@link RepoRole} 四级能力集严格嵌套（owner ⊇ maintainer ⊇ developer ⊇ reporter，
     * 枚举按档构造保证），故「角色 ≥ floor」⟺「具备 floor 档新增的探测动作」——探测动作复用
     * {@link #checkRepoPerm} 的五步链与决策缓存（同动作同键，无额外缓存面）；平台 OWNER/ADMIN
     * 短路与 DENY 优先语义与动作判定完全一致。</p>
     *
     * @param userId 用户 id
     * @param repoId 仓库 id
     * @param floor  角色下限（如 {@code RepoRole.MAINTAINER}）
     * @return 角色是否达到下限
     */
    public boolean checkRoleAtLeast(UUID userId, UUID repoId, RepoRole floor) {
        return checkRepoPerm(userId, repoId, probeActionOf(floor));
    }

    /** 角色下限断言（不达即 403 T1-PLT-4030）：scene 为业务场景文案（如「关闭评审」「单测豁免」） */
    public void requireRoleAtLeast(UUID userId, UUID repoId, RepoRole floor, String scene) {
        if (!checkRoleAtLeast(userId, repoId, floor)) {
            throw new PermissionDeniedException(
                    ErrorCode.PLT_4030,
                    "无权限：" + scene + "需仓库 " + floor.wire() + " 及以上角色（或平台管理员）",
                    List.of("roleFloor=" + floor.wire(), "repoId=" + repoId));
        }
    }

    /** floor 档新增的探测动作（见 {@link #checkRoleAtLeast} 等价性说明） */
    private static String probeActionOf(RepoRole floor) {
        return switch (floor) {
            case REPORTER -> RepoActions.VIEW;               // 全角色共有
            case DEVELOPER -> RepoActions.PUSH;              // Developer 档新增
            case MAINTAINER -> RepoActions.MANAGE_PROTECTION; // Maintainer 档新增
            case OWNER -> RepoActions.MANAGE_SETTINGS;       // Owner 档独占
        };
    }

    /**
     * 我在仓库的有效角色（§4.5 能力位查询接口数据源）：repo_member 最高 ALLOW 角色；
     * 平台 OWNER/ADMIN 短路返回 {@code OWNER}（每仓库隐式能力全集，§3.1）；
     * 无条目 / 非 ACTIVE / DENY 命中返回 {@code null}（仅剩 visibility 兜底只读）。
     */
    public RepoRole effectiveRoleOf(UUID userId, UUID repoId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != AppUser.Status.ACTIVE) {
            return null;
        }
        if (user.getPlatformRole() == AppUser.PlatformRole.OWNER
                || user.getPlatformRole() == AppUser.PlatformRole.ADMIN) {
            return RepoRole.OWNER;
        }
        List<RepoMember> mine = members.findByRepoId(repoId).stream()
                .filter(m -> userId.equals(m.getSubjectUserId()))
                .toList();
        if (mine.stream().anyMatch(m -> m.getEffect() == Effect.DENY)) {
            return null;
        }
        return mine.stream()
                .filter(m -> m.getEffect() == Effect.ALLOW)
                .map(RepoMember::getRole)
                .max(java.util.Comparator.comparingInt(RepoRole::ordinal))
                .orElse(null);
    }

    /**
     * 我在仓库的实际放行动作集合（me/permissions 契约的 capabilities 字段）：
     * 对 {@link RepoActions#ALL} 14 动作逐个走 {@link #checkRepoPerm}（真相 = 五步链，
     * 含 DENY、visibility 只读兜底与平台短路；命中走决策缓存）。目录声明序输出。
     *
     * <p>实现取舍（契约二选一，取「逐动作走链」）：保证与后端放行 100% 同源一致
     * （前端「不渲染 = 后端必 403」双向等价的根基，§5.2 原则②）；代价为 14 次链判定
     * （缓存命中路径 O(1)/动作），UI 拉取频次下可忽略。</p>
     */
    public java.util.List<String> capabilitiesOf(UUID userId, UUID repoId) {
        return RepoActions.ALL.stream()
                .filter(action -> checkRepoPerm(userId, repoId, action))
                .toList();
    }
}
