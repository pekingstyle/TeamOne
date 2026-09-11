-- TeamOne M0 基线：六个 schema + 平台表 + 刷新令牌表 + 权限点目录
-- 主键统一 gen_random_uuid()（PG13+；uuidv7() 为 PG18 特性，见 05 架构文档 Spike S-2）

CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS prd;
CREATE SCHEMA IF NOT EXISTS collab;
CREATE SCHEMA IF NOT EXISTS eng;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS infra;

CREATE TABLE IF NOT EXISTS platform.department (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name          text NOT NULL,
  parent_id     uuid REFERENCES platform.department(id),
  lead_user_id  uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.app_user (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  username             text NOT NULL UNIQUE,
  password_hash        text NOT NULL,
  display_name         text NOT NULL,
  title                text,
  email                text,
  platform_role        text NOT NULL DEFAULT 'MEMBER'
                       CHECK (platform_role IN ('OWNER','ADMIN','MEMBER')),
  department_id        uuid REFERENCES platform.department(id),
  daily_capacity_hours int  NOT NULL DEFAULT 8,
  status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.permission (
  code        text PRIMARY KEY,
  description text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.resource_acl (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_type  text NOT NULL CHECK (subject_type IN ('user','role','department')),
  subject_id    uuid NOT NULL,
  resource_type text NOT NULL,
  resource_id   uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
  action        text NOT NULL,
  effect        text NOT NULL CHECK (effect IN ('allow','deny')),
  granted_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (subject_type, subject_id, resource_type, resource_id, action)
);
CREATE INDEX IF NOT EXISTS idx_acl_resource ON platform.resource_acl (resource_type, resource_id);

CREATE TABLE IF NOT EXISTS infra.refresh_token (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES platform.app_user(id),
  token_hash text NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rt_user ON infra.refresh_token (user_id);

INSERT INTO platform.permission (code, description) VALUES
  ('user:list',      '查看用户列表'),
  ('platform:manage','平台管理')
ON CONFLICT (code) DO NOTHING;
