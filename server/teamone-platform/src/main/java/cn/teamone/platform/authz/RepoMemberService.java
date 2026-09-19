package cn.teamone.platform.authz;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.RepoMember;
import cn.teamone.platform.domain.RepoMember.Effect;
import cn.teamone.platform.domain.RepoMember.Source;
import cn.teamone.platform.dto.RepoMemberDto;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.RepoMemberRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;

/**
 * 仓库成员授权写侧服务（⑥i-A1 M-a · docs/v2/13 §5.1 授权/回收流程）。
 *
 * <p>职责：members 三端点的业务规则（upsert / 移除 / 最后 Owner 保护）、
 * 建仓人 INHERITED Owner 行（§1.4）、变更同事务审计（acl.grant / acl.role / acl.revoke，
 * Q7 纪律：不落任何敏感信息，仅 id/角色/来源坐标）与缓存版本失效（§4.4 键版本方案：
 * 写事务内直调 bump，L1 纪律，事件只是广播事实）。</p>
 *
 * <p>读侧判定在 {@link RepoAclService}；本服务不做权限自查（端点层由
 * {@code @RequireRepoPerm(action="manage-settings")} 断言：仓库 Owner ∨ 平台 OWNER/ADMIN）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@Service
public class RepoMemberService {

    private final RepoMemberRepository members;
    private final AppUserRepository users;
    private final AuditService audit;
    private final Optional<DecisionCache> cache;

    public RepoMemberService(RepoMemberRepository members, AppUserRepository users,
                             AuditService audit, Optional<DecisionCache> cache) {
        this.members = members;
        this.users = users;
        this.audit = audit;
        this.cache = cache == null ? Optional.empty() : cache;
    }

    /**
     * 成员列表（GET /repos/{idOrName}/members）：join app_user 取 username/displayName
     * 与授予人显示名（joined users 显示名口径）；INHERITED 行原样透出（前端标注「建仓人」）。
     */
    @Transactional(readOnly = true)
    public List<RepoMemberDto> listMembers(UUID repoId) {
        List<RepoMember> rows = members.findByRepoId(repoId);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 一次取全涉及用户（主体 + 授予人去重），避免 N+1
        Map<UUID, AppUser> userMap = users.findAllById(rows.stream()
                        .map(RepoMember::getSubjectUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        Map<UUID, AppUser> granterMap = users.findAllById(rows.stream()
                        .map(RepoMember::getGrantedBy).filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return rows.stream()
                .map(r -> toDto(r, userMap.get(r.getSubjectUserId()), granterMap.get(r.getGrantedBy())))
                .toList();
    }

    /**
     * 授予 / 改角色（PUT members，upsert；新建行 source=DIRECT，INHERITED 行改角色保留来源）。
     *
     * <p>规则（⑥i-Q4 裁决：Owner 为可授予角色，转移=「先授新 Owner、再降旧 Owner」两步）：
     * ① role 收 owner/maintainer/developer/reporter（非法值 400）；② 目标主体非 ACTIVE 拒绝（400）；
     * ③ 最后 Owner 保护：目标是唯一有效（allow）Owner 行且新角色 ≠ owner → 409（防仓库无主，
     * 先授新 Owner 即可解除）；④ 同角色幂等：不写不审计。管理权限（Owner∨平台短路）由切面/上层校验。</p>
     *
     * @return upsert 后的成员视图（契约 200 item）
     */
    @Transactional
    public RepoMemberDto upsertMember(UUID repoId, UUID subjectUserId, String roleWire, UUID actorId) {
        RepoRole targetRole = RepoRole.fromWire(roleWire);
        AppUser subject = users.findById(subjectUserId).orElse(null);
        if (subject == null || subject.getStatus() != AppUser.Status.ACTIVE) {
            throw new BusinessException(ErrorCode.PLT_4000, "目标用户不存在或非 ACTIVE");
        }
        RepoMember row = members.findByRepoIdAndSubjectUserId(repoId, subjectUserId).orElse(null);
        if (row != null && row.getRole() == RepoRole.OWNER && targetRole != RepoRole.OWNER) {
            // 最后 Owner 保护：唯一有效（allow）Owner 行降级为其他角色 → 409（先授新 Owner 即可解除）；
            // 多 Owner 场景放行（与 removeMember 口径一致）。INHERITED 建仓人行同样适用（建仓人徽标=历史身份，角色=当前授权）。
            long owners = members.countByRepoIdAndRoleAndEffect(repoId, RepoRole.OWNER, Effect.ALLOW);
            if (owners <= 1) {
                throw lastOwnerConflict(row.getSource() == Source.INHERITED
                        ? "建仓人是该仓库唯一的 Owner，不可降级（最后 Owner 保护）：请先授予新 Owner"
                        : "该成员是该仓库唯一的 Owner，不可降级（最后 Owner 保护）：请先授予新 Owner");
            }
        }
        boolean created = (row == null);
        if (created) {
            row = new RepoMember();
            row.setRepoId(repoId);
            row.setSubjectUserId(subjectUserId);
            row.setRole(targetRole);
            row.setSource(Source.DIRECT);
            row.setEffect(Effect.ALLOW);
            row.setGrantedBy(actorId);
            row.setGrantedAt(Instant.now());
        } else if (row.getRole() != targetRole) {
            row.setRole(targetRole);
            row.setGrantedBy(actorId);
            row.setGrantedAt(Instant.now());
        } else {
            // 同角色幂等：无变更不写库不审计不失效
            return toDto(row, subject, granterOf(row));
        }
        members.save(row);
        // 同事务审计 + 版本失效（L1 纪律）；不落敏感信息（仅 id/角色/来源坐标，Q7）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("repoId", repoId);
        detail.put("subjectUserId", subjectUserId);
        detail.put("role", targetRole.wire());
        detail.put("source", row.getSource().name());
        detail.put("effect", row.getEffect().name());
        audit.record(actorId, created ? "acl.grant" : "acl.role", "repository", repoId.toString(), detail);
        bumpVersion(repoId);
        return toDto(row, subject, granterOf(row));
    }

    /**
     * 移除授权（DELETE members/{userId}）：删 DIRECT 行即回收；INHERITED（建仓人）行删除 =
     * 409 最后 Owner 保护（唯一 Owner 不可移除；多 Owner 场景放行，§1.4「INHERITED 条目可移除
     * 但受最后 Owner 保护」）。无条目 404。
     */
    @Transactional
    public void removeMember(UUID repoId, UUID subjectUserId, UUID actorId) {
        RepoMember row = members.findByRepoIdAndSubjectUserId(repoId, subjectUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "成员不存在: " + subjectUserId));
        if (row.getRole() == RepoRole.OWNER) {
            // 最后 Owner 保护：仅剩一个有效（allow）Owner 行时拒绝移除/降级
            long owners = members.countByRepoIdAndRoleAndEffect(repoId, RepoRole.OWNER, Effect.ALLOW);
            if (owners <= 1) {
                throw lastOwnerConflict(row.getSource() == Source.INHERITED
                        ? "建仓人是该仓库唯一的 Owner，不可移除（最后 Owner 保护）：请先转移 Owner"
                        : "该成员是该仓库唯一的 Owner，不可移除（最后 Owner 保护）：请先转移 Owner");
            }
        }
        members.delete(row);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("repoId", repoId);
        detail.put("subjectUserId", subjectUserId);
        detail.put("role", row.getRole().wire());
        detail.put("source", row.getSource().name());
        audit.record(actorId, "acl.revoke", "repository", repoId.toString(), detail);
        bumpVersion(repoId);
    }

    /**
     * 建仓系统授予（docs/v2/13 §1.4）：建仓人 INHERITED owner 行，建仓事务内调用。
     *
     * <p>幂等（已存在不覆盖）；不单独落 acl.grant 审计（repo.create 审计已覆盖本次系统授予，
     * 避免同事务双条）；不 bump 版本（新仓库不存在任何已缓存决策）。
     * 存量仓库无此行 = 无 Owner，由平台 OWNER/ADMIN 短路兜底（V21 迁移头注已声明）。</p>
     */
    @Transactional
    public void grantInheritedOwner(UUID repoId, UUID creatorId) {
        if (members.findByRepoIdAndSubjectUserId(repoId, creatorId).isPresent()) {
            return;
        }
        RepoMember row = new RepoMember();
        row.setRepoId(repoId);
        row.setSubjectUserId(creatorId);
        row.setRole(RepoRole.OWNER);
        row.setSource(Source.INHERITED);
        row.setEffect(Effect.ALLOW);
        row.setGrantedBy(creatorId);
        row.setGrantedAt(Instant.now());
        members.save(row);
    }

    /** 版本失效（键版本方案）：成员变更 → INCR ver + DEL 该仓判定键；Valkey 故障由实现方静默降级 */
    private void bumpVersion(UUID repoId) {
        cache.ifPresent(c -> c.bumpRepoVersion(repoId));
    }

    private AppUser granterOf(RepoMember row) {
        return row.getGrantedBy() == null ? null
                : users.findById(row.getGrantedBy()).orElse(null);
    }

    private RepoMemberDto toDto(RepoMember row, AppUser subject, AppUser granter) {
        return new RepoMemberDto(
                row.getSubjectUserId(),
                subject == null ? null : subject.getUsername(),
                subject == null ? null : subject.getDisplayName(),
                row.getRole().wire(),
                row.getSource().name(),
                granter == null ? null : granter.getDisplayName(),
                row.getGrantedAt());
    }

    private BusinessException lastOwnerConflict(String message) {
        // 409 口径（总监裁决）：T1-PLT-4091 承载「最后 Owner 保护」，message 提示先转移 Owner
        return new BusinessException(ErrorCode.PLT_4091, message, List.of("reason=last-owner-protection"));
    }
}
