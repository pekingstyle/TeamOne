-- V19：R-12 系统设置模块（B6 批 · docs/v2/12 §1 R-12 / 裁决 D6）
-- 遵循红线：
--   * 只追加，不改既有结构；主键复合业务键 (group, key)，无代理主键（配置项天然按名寻址）
--   * 零硬编码业务 uuid（无种子数据依赖；权限码目录沿用 V1 先例 ON CONFLICT DO NOTHING）

-- ================= platform.setting（系统设置，秘密值 AES-256-GCM 加密落库） =================
-- 分组约定（group）：project=项目 / repo=仓库 / server=服务器 / credential=凭据 / llm=大模型（预留）
-- 秘密值纪律（D6 + 全栈评审必改③）：is_secret=true 的行明文只进 value_cipher（AES-256-GCM，
--   AAD=group+":"+key 绑定坐标防密文挪用），value 列保持 NULL；读取一律掩码（前4位****），
--   明文永不回显。密钥来自环境变量 TEAMONE_CRYPTO_KEY（Base64 的 32 字节），不入库不进 git。
-- 乐观锁：version 每次成功 PUT +1，请求携带读到的旧值，不匹配回 409（PLT_4091）。
CREATE TABLE platform.setting (
  "group"      text   NOT NULL,
  key          text   NOT NULL,
  value        text,
  is_secret    boolean NOT NULL DEFAULT false,
  value_cipher bytea,
  updated_by   uuid,                                    -- 逻辑引用 platform.app_user.id（弱引用，配置可先于人存在）
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      bigint  NOT NULL DEFAULT 0,
  PRIMARY KEY ("group", key)
);

COMMENT ON TABLE platform.setting IS '系统设置（R-12/D6）：秘密值仅密文落库（AES-256-GCM），读取掩码，行级乐观锁';

-- ================= 权限码目录（settings:read / settings:edit，仅管理员可用） =================
-- 授权真相仍在四步短路链（PermissionService）：OWNER/ADMIN 角色短路放行，其余默认拒绝
-- （除非 resource_acl 显式授 ALLOW）——权限码在此注册目录，供审计与后续 ACL 授予引用。
INSERT INTO platform.permission (code, description) VALUES
  ('settings:read', '系统设置查看'),
  ('settings:edit', '系统设置编辑')
ON CONFLICT (code) DO NOTHING;
