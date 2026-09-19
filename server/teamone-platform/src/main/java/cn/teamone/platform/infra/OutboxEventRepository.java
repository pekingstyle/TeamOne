package cn.teamone.platform.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 发件箱仓库（表 infra.outbox_event）。 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
}
