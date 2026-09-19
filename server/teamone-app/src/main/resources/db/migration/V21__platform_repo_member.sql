-- V21：eng 工程域资源级权限（ACL）地基 —— platform.repo_member 仓库成员授权表
--   （⑥i-A1 · M-a，docs/v2/13-ACL资源权限设计.md v1.1 §4.1 / §6.1 A1；迁移号固定 V21）
-- 遵循红线：
--   * 零硬编码 uuid：无种子数据、无存量回填（见下方「存量仓库口径」）
--   * 只追加，不改既有结构；跨 schema 逻辑引用不建 FK（eng.repository ↔ platform 惯例，同 §4.1）
--
-- 【存量仓库口径（重要，docs/v2/13 §1.4 已声明）】
--   eng.repository 无 created_by 列，建仓人自动回填的唯一路径 = 建仓流程写入（RepositoryProvisionService
--   于建仓事务内插入建仓人 INHERITED owner 行）。因此：
--     * 建仓人 INHERITED owner 行只在本迁移之后【新建】的仓库产生；
--     * 存量仓库（V10 种子 teamone/teamone.git 等）在本表中【无行 = 无 Owner】，
--       其管理由平台 OWNER/ADMIN 角色短路（仓库判定链第 1 步）兜底，行为与现状一致；
--   现网无 PRIVATE 仓库数据，本表上线对存量行为零收紧（唯一可见变化 = GET /repos 的 PRIVATE 过滤能力，
--   对全 INTERNAL 的现网等效无变化，M-a 验收标准 6）。
--
-- 【权限码说明（settings 先例 V19 为何不沿用）】
--   仓库级动作（view/pull/push/…/manage-settings，docs/v2/13 §2.2 共 14 个）走 repo_member 角色能力位
--   判定链（RepoRole.capabilities，代码常量不建表，§4.2），不经 platform.permission / resource_acl
--   目录——settings:read/settings:edit 先例仅适用于平台级动作（四步短路链），故本迁移不补权限码行。

-- ================= platform.repo_member（仓库成员授权，角色粒度 ACL） =================
-- 判定链真相源（缓存只是加速器）：一仓库一主体一条（角色唯一，改角色 = UPDATE 走审计 acl.role）。
-- role 取小写与 API 契约一致（owner/maintainer/developer/reporter）；
-- source/effect 大小写遵循总监裁决 DDL 口径：source 大写（DIRECT/INHERITED/GROUP）、effect 小写（allow/deny）。
CREATE TABLE platform.repo_member (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id         uuid NOT NULL,                 -- eng.repository 逻辑引用（跨域不建 FK；仓库删除 M-c 时级联清理）
  subject_user_id uuid NOT NULL,                 -- 主体 = platform.app_user.id（Q2 裁决：M-a 仅用户主体，组管理 UI 不做）
  role            text NOT NULL CHECK (role IN ('owner','maintainer','developer','reporter')),
  source          text NOT NULL DEFAULT 'DIRECT' CHECK (source IN ('DIRECT','INHERITED','GROUP')),
                  -- DIRECT=面板手工授予；INHERITED=建仓系统授予（建仓人 Owner）；GROUP=组命中（运行时解析不落行，模型预留）
  effect          text NOT NULL DEFAULT 'allow' CHECK (effect IN ('allow','deny')),
                  -- deny 优先语义（对齐 resource_acl 的 allowed && !denied）：封禁 = 原行 UPDATE effect='deny'
                  -- 且 role 保留原值（封禁 ≠ 降级，解封改回 allow 恢复原角色）；一期 API 不暴露 deny 写路径
  granted_by      uuid,                          -- 逻辑引用 platform.app_user.id（系统授予=操作人/建仓人）
  granted_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (repo_id, subject_user_id)              -- 一仓库一主体一条（该唯一约束的 btree 前缀同时服务仓侧热路径）
);

COMMENT ON TABLE platform.repo_member IS
  '仓库成员授权（⑥i-A1 M-a）：角色粒度 ACL，仓库判定链真相源；存量仓库无行=无 Owner，由平台 OWNER/ADMIN 短路兜底管理';
COMMENT ON COLUMN platform.repo_member.subject_user_id IS '主体用户（app_user.id）；GROUP 组主体为运行时派生不落行（source=GROUP 预留）';
COMMENT ON COLUMN platform.repo_member.role IS '仓库四级角色（小写）：owner ⊇ maintainer ⊇ developer ⊇ reporter，能力矩阵见 docs/v2/13 §2.3';
COMMENT ON COLUMN platform.repo_member.source IS 'DIRECT=手工授予；INHERITED=建仓系统授予（面板显示「建仓人」）；GROUP=组命中（运行时解析，预留）';
COMMENT ON COLUMN platform.repo_member.effect IS 'deny 优先（allow/deny 小写）：封禁保留 role 原值，解封恢复；一期 UI/API 仅 allow';

-- 索引：主体反查（「我参与哪些仓库」）；仓侧热路径（按仓库拉全量成员/判定 owner 数）
-- 由 UNIQUE (repo_id, subject_user_id) 的 btree 最左前缀覆盖，不另建冗余 repo_id 索引。
CREATE INDEX idx_repo_member_subject_user ON platform.repo_member (subject_user_id);
