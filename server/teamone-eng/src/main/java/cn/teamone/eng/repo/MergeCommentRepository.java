package cn.teamone.eng.repo;

import cn.teamone.eng.domain.MergeComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MergeCommentRepository extends JpaRepository<MergeComment, UUID> {

    List<MergeComment> findByMrIdOrderByCreatedAtAsc(UUID mrId);
}
