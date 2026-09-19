-- V11：eng 代码评审与门禁（M2-INC-3 U5~U7：MR 对比、会签状态机与合并门禁，V-14 验收基准）
-- 红线：
--   * 只追加（不改 V1~V10 既有结构）
--   * eng 域零 prd/collab 依赖：用户引用 author_id/user_id 为逻辑引用，work_item 引用为文本键
--   * 主键统一 gen_random_uuid()

-- ================= eng.mr_review（MR 评审单核心表） =================
CREATE TABLE IF NOT EXISTS eng.mr_review (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id              uuid NOT NULL REFERENCES eng.repository(id) ON DELETE CASCADE,
  mr_number            int NOT NULL,
  title                text NOT NULL,
  description          text,
  source_branch        text NOT NULL,
  target_branch        text NOT NULL,
  author_id            uuid NOT NULL,
  status               text NOT NULL DEFAULT 'open'
                       CHECK (status IN ('draft', 'open', 'merged', 'closed')),
  merge_commit_sha     text,
  merged_by_id         uuid,
  merged_at            timestamptz,
  closed_at            timestamptz,
  linked_work_item_key text,
  based_on_baseline_id uuid,
  rebase_required      boolean NOT NULL DEFAULT false,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_mr_repo_number UNIQUE (repo_id, mr_number)
);

CREATE INDEX IF NOT EXISTS idx_mr_review_repo_status ON eng.mr_review (repo_id, status);
CREATE INDEX IF NOT EXISTS idx_mr_review_author ON eng.mr_review (author_id);
CREATE INDEX IF NOT EXISTS idx_mr_review_linked_wi ON eng.mr_review (linked_work_item_key);

-- ================= eng.mr_reviewer（MR 会签评审人表） =================
CREATE TABLE IF NOT EXISTS eng.mr_reviewer (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  mr_id      uuid NOT NULL REFERENCES eng.mr_review(id) ON DELETE CASCADE,
  user_id    uuid NOT NULL,
  state      text NOT NULL DEFAULT 'pending'
             CHECK (state IN ('pending', 'approved', 'changes_requested')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_mr_reviewer UNIQUE (mr_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_mr_reviewer_user ON eng.mr_reviewer (user_id);

-- ================= eng.mr_check（MR 门禁检查项） =================
CREATE TABLE IF NOT EXISTS eng.mr_check (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  mr_id      uuid NOT NULL REFERENCES eng.mr_review(id) ON DELETE CASCADE,
  kind       text NOT NULL,     -- 'unit_test', 'conflict', 'rebase', 'ci'
  name       text NOT NULL,     -- 显示名称
  passed     boolean NOT NULL DEFAULT false,
  payload    jsonb,             -- 扩展指标：覆盖率、用例数、豁免信息等
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_mr_check_kind UNIQUE (mr_id, kind)
);

-- ================= eng.mr_comment（MR 评审讨论/行间评论） =================
CREATE TABLE IF NOT EXISTS eng.mr_comment (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  mr_id       uuid NOT NULL REFERENCES eng.mr_review(id) ON DELETE CASCADE,
  author_id   uuid NOT NULL,
  text        text NOT NULL,
  file_path   text,
  line_number int,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mr_comment_mr ON eng.mr_comment (mr_id, created_at);

-- ================= eng.mr_conflict_resolution（冲突解决方案留痕） =================
CREATE TABLE IF NOT EXISTS eng.mr_conflict_resolution (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  mr_id           uuid NOT NULL REFERENCES eng.mr_review(id) ON DELETE CASCADE,
  file_path       text NOT NULL,
  solution        text NOT NULL,
  confirmed_by_id uuid,
  reviewed_by_id  uuid,
  resolved_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mr_conflict_res_mr ON eng.mr_conflict_resolution (mr_id);

-- 默认种子数据：为 teamone 仓库注入演示/自举 MR
DO $$
DECLARE
  v_repo_id uuid;
  v_admin_id uuid;
BEGIN
  SELECT id INTO v_repo_id FROM eng.repository WHERE repo_path = 'teamone/teamone.git' LIMIT 1;
  SELECT id INTO v_admin_id FROM platform.app_user WHERE username = 'admin' LIMIT 1;

  IF v_repo_id IS NOT NULL AND v_admin_id IS NOT NULL THEN
    -- MR !1: feat: 自研 Git 内核出墙口与文件浏览
    INSERT INTO eng.mr_review (id, repo_id, mr_number, title, description, source_branch, target_branch, author_id, status, linked_work_item_key, rebase_required)
    VALUES (
      '00000000-0000-0000-0000-000000000011',
      v_repo_id,
      1,
      'feat: 自研 Git 内核出墙口与文件浏览',
      '实现 GitCommandPort 与 RepositoryController，接通目录树与文件查看。' || E'\n\n' || 'Refs #T-101',
      'main',
      'main',
      v_admin_id,
      'open',
      'T-101',
      false
    ) ON CONFLICT (repo_id, mr_number) DO NOTHING;

    -- Reviewers for MR !1
    INSERT INTO eng.mr_reviewer (mr_id, user_id, state)
    VALUES ('00000000-0000-0000-0000-000000000011', v_admin_id, 'approved')
    ON CONFLICT (mr_id, user_id) DO NOTHING;

    -- Checks for MR !1
    INSERT INTO eng.mr_check (mr_id, kind, name, passed, payload)
    VALUES
      ('00000000-0000-0000-0000-000000000011', 'unit_test', '单测门禁 (≥60% / ≥80%)', true, '{"hasTests":true,"passed":true,"coverageTotal":78.5,"coverageDelta":85.0,"gatePassed":true}'::jsonb),
      ('00000000-0000-0000-0000-000000000011', 'conflict', '可合并性 (merge-tree)', true, '{"conflictFiles":[]}'::jsonb),
      ('00000000-0000-0000-0000-000000000011', 'rebase', 'Rebase 状态', true, '{"rebaseRequired":false}'::jsonb)
    ON CONFLICT (mr_id, kind) DO NOTHING;

    -- MR !2: fix: MR 会签状态机与合并门禁检测
    INSERT INTO eng.mr_review (id, repo_id, mr_number, title, description, source_branch, target_branch, author_id, status, linked_work_item_key, rebase_required)
    VALUES (
      '00000000-0000-0000-0000-000000000012',
      v_repo_id,
      2,
      'fix: MR 会签状态机与合并门禁检测',
      '落实 L1 合并门禁硬约束，支持单测豁免留痕与可合并性检测。' || E'\n\n' || 'Refs #T-102',
      'main',
      'main',
      v_admin_id,
      'open',
      'T-102',
      false
    ) ON CONFLICT (repo_id, mr_number) DO NOTHING;

    -- Reviewers for MR !2
    INSERT INTO eng.mr_reviewer (mr_id, user_id, state)
    VALUES ('00000000-0000-0000-0000-000000000012', v_admin_id, 'pending')
    ON CONFLICT (mr_id, user_id) DO NOTHING;

    -- Checks for MR !2 (unit test not yet passed)
    INSERT INTO eng.mr_check (mr_id, kind, name, passed, payload)
    VALUES
      ('00000000-0000-0000-0000-000000000012', 'unit_test', '单测门禁 (≥60% / ≥80%)', false, '{"hasTests":true,"passed":false,"coverageTotal":55.0,"coverageDelta":60.0,"gatePassed":false}'::jsonb),
      ('00000000-0000-0000-0000-000000000012', 'conflict', '可合并性 (merge-tree)', true, '{"conflictFiles":[]}'::jsonb),
      ('00000000-0000-0000-0000-000000000012', 'rebase', 'Rebase 状态', true, '{"rebaseRequired":false}'::jsonb)
    ON CONFLICT (mr_id, kind) DO NOTHING;
  END IF;
END $$;
