package cn.teamone.platform.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cn.teamone.platform.domain.RepoMember;
import cn.teamone.platform.domain.RepoMember.Effect;
import cn.teamone.platform.authz.RepoRole;

/**
 * 仓库成员授权数据访问（V21 / docs/v2/13 §4.1）。
 *
 * <p>判定热路径 {@code findByRepoId}（按仓库拉全量成员）由 UNIQUE (repo_id, subject_user_id)
 * 的 btree 最左前缀服务；主体反查（「我参与哪些仓库」）走 idx_repo_member_subject_user。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
public interface RepoMemberRepository extends JpaRepository<RepoMember, UUID> {

    /** 判定热路径：按仓库拉全量成员（五步链第 2/3 步与成员面板共用） */
    List<RepoMember> findByRepoId(UUID repoId);

    /** 单主体条目（UNIQUE 约束保证至多一条）：upsert / 移除定位 */
    Optional<RepoMember> findByRepoIdAndSubjectUserId(UUID repoId, UUID subjectUserId);

    /** 最后 Owner 保护计数：role=owner 且 effect=allow 的有效 Owner 行数 */
    long countByRepoIdAndRoleAndEffect(UUID repoId, RepoRole role, Effect effect);

    /** 主体反查（预留：能力位聚合 / 仓库删除级联清理 M-c） */
    List<RepoMember> findBySubjectUserId(UUID subjectUserId);
}
