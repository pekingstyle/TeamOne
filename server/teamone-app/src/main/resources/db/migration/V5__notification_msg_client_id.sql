-- V5：W3 事件骨干前置迁移（06 §3-V5：collab.notification + collab.message.client_msg_id）
-- 方向审查口径（2026-09-10）：
--   * collab.message 为按月分区表（V4）——分区表不能建跨分区唯一索引，
--     client_msg_id 幂等不走唯一约束，走「事务内查询回放」（msg 帧处理先查后插，③b 落地）
--   * 两个索引均为部分索引：notification 只加速未读查询；message 幂等索引跳过历史行
-- 注：product.owner_id 列 V4 已存在（实体字段 + ProductSeeder 已指 admin），本迁移不涉及

-- ================= collab.notification（站内通知，③b/W4 消费事件落库） =================

CREATE TABLE collab.notification (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES platform.app_user(id),
  kind       text NOT NULL,                        -- review_invited / gate_unblocked / system …
  payload    jsonb NOT NULL DEFAULT '{}',          -- 通知渲染所需事实（事件 payload 投影）
  read_at    timestamptz,                          -- NULL=未读（部分索引只覆盖未读行）
  created_at timestamptz NOT NULL DEFAULT now()
);

-- 未读收件箱热点查询：WHERE user_id=? AND read_at IS NULL ORDER BY created_at DESC
CREATE INDEX idx_notification_unread ON collab.notification (user_id, created_at DESC)
  WHERE read_at IS NULL;

-- ================= collab.message.client_msg_id（WS 发帧幂等，③b） =================

ALTER TABLE collab.message ADD COLUMN client_msg_id uuid;

-- 幂等查询索引：WHERE conversation_id=? AND client_msg_id=?（重复帧回放判定）
CREATE INDEX idx_message_client_msg_id ON collab.message (conversation_id, client_msg_id)
  WHERE client_msg_id IS NOT NULL;
