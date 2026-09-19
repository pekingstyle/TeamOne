-- V12：eng 基线全流程管理与分支保护规则（M2-INC-3 U8+U10 前段，V-15 验收基准）
-- 遵循红线：
--   * 只追加，不改既有结构
--   * 主键统一 gen_random_uuid()
--   * eng 域零 prd/collab 编译期外键耦合，仅逻辑引用用户 UUID

-- ================= eng.baseline（基线主表） =================
CREATE TABLE IF NOT EXISTS eng.baseline (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id                  uuid NOT NULL REFERENCES eng.repository(id) ON DELETE CASCADE,
  name                     text NOT NULL,
  type                     text NOT NULL
                           CHECK (type IN ('functional', 'allocated', 'product')),
  tag_ref                  text NOT NULL,
  commit_sha               text,
  artifact_version         text,
  requirement_snapshot_id  text,
  status                   text NOT NULL DEFAULT 'draft'
                           CHECK (status IN ('draft', 'in_review', 'approved', 'superseded')),
  superseded_by_id         uuid,
  approver_ids             jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_by               uuid NOT NULL,
  created_at               timestamptz NOT NULL DEFAULT now(),
  approved_at              timestamptz,
  CONSTRAINT uq_baseline_repo_tag UNIQUE (repo_id, tag_ref)
);

CREATE INDEX IF NOT EXISTS idx_baseline_repo_status ON eng.baseline (repo_id, status);
CREATE INDEX IF NOT EXISTS idx_baseline_tag ON eng.baseline (tag_ref);

-- ================= eng.branch_protection（分支保护规则表） =================
CREATE TABLE IF NOT EXISTS eng.branch_protection (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id                  uuid NOT NULL REFERENCES eng.repository(id) ON DELETE CASCADE,
  branch_pattern           text NOT NULL,
  require_mr               boolean NOT NULL DEFAULT true,
  min_approvals            int NOT NULL DEFAULT 1,
  require_unit_test        boolean NOT NULL DEFAULT true,
  block_force_push         boolean NOT NULL DEFAULT true,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_branch_protection UNIQUE (repo_id, branch_pattern)
);

CREATE INDEX IF NOT EXISTS idx_branch_protection_repo ON eng.branch_protection (repo_id);

-- ================= 种子数据初始化（teamone 仓库） =================
-- MF(P0)：种子不得硬编码开发库 repo_id——全新环境 eng.repository 为空，
-- 外键违规会导致应用启动崩溃循环（私有化空机安装必踩）。
-- 改为「仓库存在才插」的安全形式：INSERT..SELECT..WHERE EXISTS；新环境优雅跳过。
INSERT INTO eng.baseline (
  id, repo_id, name, type, tag_ref, commit_sha,
  artifact_version, requirement_snapshot_id, status, approver_ids, created_by, approved_at
)
SELECT * FROM (VALUES
(
  '00000000-0000-0000-0000-0000000000b1'::uuid,
  '365d3eef-0279-4439-8107-64392a1e8044'::uuid,
  'TeamOne v2.4 分配基线',
  'allocated',
  'v2.4.0-baseline-allocated',
  'a7faecdf86af9fbc690d80e9383bead1eb67a57f',
  '2.4.0-SNAPSHOT',
  'RS-2401',
  'approved',
  '["57a7b589-92ff-4c82-bab0-ff2dfe5fe74a", "6a111111-2222-3333-4444-555555555555"]'::jsonb,
  '57a7b589-92ff-4c82-bab0-ff2dfe5fe74a'::uuid,
  now() - interval '2 days'
),
(
  '00000000-0000-0000-0000-0000000000b2'::uuid,
  '365d3eef-0279-4439-8107-64392a1e8044'::uuid,
  'TeamOne v2.4 产品基线（候选）',
  'product',
  'v2.4.0-rc1',
  'a7faecdf86af9fbc690d80e9383bead1eb67a57f',
  '2.4.0-RC1',
  'RS-2402',
  'in_review',
  '["57a7b589-92ff-4c82-bab0-ff2dfe5fe74a"]'::jsonb,
  '57a7b589-92ff-4c82-bab0-ff2dfe5fe74a'::uuid,
  NULL
)) AS seed(id, repo_id, name, type, tag_ref, commit_sha,
          artifact_version, requirement_snapshot_id, status, approver_ids, created_by, approved_at)
WHERE EXISTS (SELECT 1 FROM eng.repository r WHERE r.id = seed.repo_id)
ON CONFLICT (repo_id, tag_ref) DO NOTHING;

INSERT INTO eng.branch_protection (
  id, repo_id, branch_pattern, require_mr, min_approvals, require_unit_test, block_force_push
)
SELECT * FROM (VALUES
(
  '00000000-0000-0000-0000-0000000000c1'::uuid,
  '365d3eef-0279-4439-8107-64392a1e8044'::uuid,
  'main',
  true,
  1,
  true,
  true
)) AS seed(id, repo_id, branch_pattern, require_mr, min_approvals, require_unit_test, block_force_push)
WHERE EXISTS (SELECT 1 FROM eng.repository r WHERE r.id = seed.repo_id)
ON CONFLICT (repo_id, branch_pattern) DO NOTHING;
