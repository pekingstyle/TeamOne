package cn.teamone.insight.repo;

import cn.teamone.insight.domain.InsightNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** collab.notification 写仓库（insight 写实体例外，见包声明；仅 save 落红色冲突通知）。 */
public interface InsightNotificationRepository extends JpaRepository<InsightNotification, UUID> {
}
