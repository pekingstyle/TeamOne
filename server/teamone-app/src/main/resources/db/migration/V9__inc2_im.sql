-- V9：M2-INC-2 后端批（08-M2实施计划 §3 INC-2：dm 防裂 / 未读游标 / 撤回 / 文件两步制）
-- 红线：
--   * 只追加（V1~V8 任何对象禁改；本文件仅建新表 + 加列两类追加动作）
--   * 主键 gen_random_uuid()（外部 PG，uuidv7 不可用，S-2 结论同 V4/V6/V7）

-- ================= ① platform.file（文件两步制元数据，05 §9.3 / §2.3 file 行） =================
-- 两步制：presign 造行(status=uploading, object_key 服务端生成 uuid) → 客户端直传 MinIO
-- → complete statObject 验证存在 → status=ready；消息引用校验只认 ready 行（未 ready 不得被引用）
CREATE TABLE platform.file (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  uploader_id   uuid NOT NULL REFERENCES platform.app_user(id),
  bucket        text NOT NULL,
  object_key    text NOT NULL UNIQUE,               -- 服务端生成，客户端不可指定（红线 4）
  sha256        char(64),                           -- 客户端可选提供，预留 M3 秒传/校验
  size          bigint NOT NULL,                    -- presign 时申报，complete 时以 MinIO statObject 为准
  mime          text NOT NULL,
  original_name text NOT NULL,                      -- 下载时 response-content-disposition 还原文件名
  status        text NOT NULL DEFAULT 'uploading' CHECK (status IN ('uploading','ready')),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_file_uploader ON platform.file (uploader_id);
COMMENT ON TABLE platform.file IS '文件元数据（MinIO 两步制；05 §9.3）；DB 不存内容，只存 bucket/object_key/状态';

-- ================= ② collab.user_conversation_cursor（已读游标，05 §2.4 定稿结构） =================
-- 未读真相 = conversation.last_message_id - cursor.last_read_message_id（GET /conversations SQL 差值）；
-- Valkey unread:{uid} Hash 仅缓存加速，失败 WARN 降级（INC-2 红线 2）。
-- upsert 走 ON CONFLICT (user_id,conversation_id) DO UPDATE SET last_read_message_id=GREATEST(...)
-- （只前进不回退，§4.3 read 语义），幂等可重放。
CREATE TABLE collab.user_conversation_cursor (
  user_id              uuid NOT NULL REFERENCES platform.app_user(id),
  conversation_id      uuid NOT NULL REFERENCES collab.conversation(id),
  last_read_message_id bigint NOT NULL DEFAULT 0,
  read_at              timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, conversation_id)
);

-- ================= ③ collab.message 撤回（S-6 拍板：软删+system 消息，06 §9） =================
-- 撤回=脱敏非删除：DB 原文保留（body/attachments 原样），出参一律按 withdrawn_at IS NOT NULL 脱敏
-- （withdrawn=true、body=null、attachments=[]，红线 3）。withdrawn_by 不加列：
-- 撤回仅限 sender 本人（服务端校验），sender_id 即操作者；灰条 system 消息的 sender_id 亦为操作者，
-- 无第二操作者语义，冗余列只会引入两处真相。
ALTER TABLE collab.message ADD COLUMN withdrawn_at timestamptz;
