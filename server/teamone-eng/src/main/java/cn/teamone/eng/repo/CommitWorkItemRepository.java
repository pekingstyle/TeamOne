package cn.teamone.eng.repo;

import cn.teamone.eng.domain.CommitWorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 提交↔工作项映射仓库（表 eng.commit_work_item）。
 *
 * <p>写入一律走 {@link #insertIgnore}（ON CONFLICT 三元组 DO NOTHING，重复 push 幂等，
 * 红线：outbox(git.push) 与映射行同事务由调用方保证）；读侧走派生查询。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
public interface CommitWorkItemRepository extends JpaRepository<CommitWorkItem, UUID> {

    /** 工作项详情页：按 created_at DESC 取关联提交清单 */
    List<CommitWorkItem> findByWorkItemKeyOrderByCreatedAtDesc(String workItemKey);

    /** 幂等 upsert（命中唯一约束放弃写入），返回实际插入行数（0=重复） */
    @Modifying
    @Query(value = """
            INSERT INTO eng.commit_work_item
              (repo_key, commit_sha, author_name, author_email, committed_at, subject, body, work_item_key)
            VALUES (:repoKey, :commitSha, :authorName, :authorEmail, :committedAt, :subject, :body, :workItemKey)
            ON CONFLICT (repo_key, commit_sha, work_item_key) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(@Param("repoKey") String repoKey,
                     @Param("commitSha") String commitSha,
                     @Param("authorName") String authorName,
                     @Param("authorEmail") String authorEmail,
                     @Param("committedAt") Instant committedAt,
                     @Param("subject") String subject,
                     @Param("body") String body,
                     @Param("workItemKey") String workItemKey);
}
