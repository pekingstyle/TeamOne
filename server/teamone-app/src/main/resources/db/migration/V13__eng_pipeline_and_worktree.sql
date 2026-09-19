-- V13：eng 自研流水线运行视图与 WorkTree 工作副本上报（M2-INC-3 V-16 验收基准）
-- 遵循红线：
--   * 只追加，不改既有结构
--   * 主键统一 gen_random_uuid()
--   * eng 域零 prd/collab 编译期外键耦合，逻辑引用用户 UUID

-- ================= eng.pipeline_run（流水线运行视图） =================
CREATE TABLE IF NOT EXISTS eng.pipeline_run (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id          uuid NOT NULL REFERENCES eng.repository(id) ON DELETE CASCADE,
  title            text NOT NULL,
  branch           text NOT NULL,
  commit_sha       text NOT NULL,
  commit_short     text NOT NULL,
  trigger          text NOT NULL DEFAULT 'push'
                   CHECK (trigger IN ('push', 'manual', 'mr', 'schedule')),
  mr_id            uuid REFERENCES eng.mr_review(id) ON DELETE SET NULL,
  status           text NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('pending', 'running', 'passed', 'failed', 'canceled', 'skipped')),
  trigger_user_id  uuid,
  duration_sec     int NOT NULL DEFAULT 0,
  stages           jsonb NOT NULL DEFAULT '[]'::jsonb,
  started_at       timestamptz,
  finished_at      timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pipeline_run_repo_status ON eng.pipeline_run (repo_id, status);
CREATE INDEX IF NOT EXISTS idx_pipeline_run_mr ON eng.pipeline_run (mr_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_run_created ON eng.pipeline_run (created_at DESC);

-- ================= eng.worktree_report（工作副本上报表） =================
CREATE TABLE IF NOT EXISTS eng.worktree_report (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id          uuid NOT NULL REFERENCES eng.repository(id) ON DELETE CASCADE,
  local_path       text NOT NULL,
  branch_name      text NOT NULL,
  base_ref         text NOT NULL DEFAULT 'main',
  owner_user_id    uuid,
  dirty_file_count int NOT NULL DEFAULT 0,
  ahead_count      int NOT NULL DEFAULT 0,
  behind_count     int NOT NULL DEFAULT 0,
  status           text NOT NULL DEFAULT 'active'
                   CHECK (status IN ('active', 'merged', 'stale')),
  last_commit_sha  text,
  last_commit_at   timestamptz,
  last_active_at   timestamptz NOT NULL DEFAULT now(),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_worktree_report UNIQUE (repo_id, local_path)
);

CREATE INDEX IF NOT EXISTS idx_worktree_report_repo ON eng.worktree_report (repo_id);
;