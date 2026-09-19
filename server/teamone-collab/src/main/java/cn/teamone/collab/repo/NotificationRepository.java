package cn.teamone.collab.repo;

import cn.teamone.collab.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** 站内通知仓库（collab.notification；未读查询恒走 V5 部分索引）。 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /** 已读推进（只前进不回退：read_at IS NULL 守卫；仅限本人行） */
    @Modifying
    @Query("update Notification n set n.readAt = CURRENT_TIMESTAMP " +
           "where n.userId = :userId and n.readAt is null and n.id in :ids")
    int markRead(@Param("userId") UUID userId, @Param("ids") List<UUID> ids);

    /** 全部已读（PUT read{all:true}） */
    @Modifying
    @Query("update Notification n set n.readAt = CURRENT_TIMESTAMP " +
           "where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId);
}
