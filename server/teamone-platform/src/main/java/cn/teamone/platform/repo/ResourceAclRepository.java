package cn.teamone.platform.repo;

import cn.teamone.platform.domain.ResourceAcl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceAclRepository extends JpaRepository<ResourceAcl, UUID> {

    Optional<ResourceAcl> findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceIdAndAction(
            ResourceAcl.SubjectType subjectType, UUID subjectId,
            String resourceType, UUID resourceId, String action);

    /** 四步链第 4 步：主体（用户本人 / 所在部门）在该资源上的全部相关条目（deny 优先在外层判定） */
    @Query("""
           SELECT a FROM ResourceAcl a
           WHERE a.resourceType = :resourceType
             AND a.resourceId IN (:resourceIds)
             AND a.action = :action
             AND ( (a.subjectType = cn.teamone.platform.domain.ResourceAcl$SubjectType.USER     AND a.subjectId = :userId)
                OR (a.subjectType = cn.teamone.platform.domain.ResourceAcl$SubjectType.DEPARTMENT AND a.subjectId = :departmentId) )
           """)
    List<ResourceAcl> findRelevant(@Param("userId") UUID userId,
                                   @Param("departmentId") UUID departmentId,
                                   @Param("resourceType") String resourceType,
                                   @Param("resourceIds") List<UUID> resourceIds,
                                   @Param("action") String action);
}
