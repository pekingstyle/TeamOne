-- V10：eng 仓库信息扩展与种子数据（M2-INC-3 U1~U3 仓储/文件/提交/分支浏览）
-- 红线：
--   * 只追加（不改 V1~V9 既有结构）
--   * eng 域零 prd 依赖：product_id/component_id 仅为逻辑引用，不建外键
--   * 幂等插入 teamone 种子仓库，作为自研一体化研发协作平台的主裸库

ALTER TABLE eng.repository
  ADD COLUMN IF NOT EXISTS name text,
  ADD COLUMN IF NOT EXISTS description text,
  ADD COLUMN IF NOT EXISTS visibility text NOT NULL DEFAULT 'INTERNAL',
  ADD COLUMN IF NOT EXISTS ci_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_eng_repository_name ON eng.repository (name);

-- 默认种子数据：TeamOne 自身裸库
INSERT INTO eng.repository (name, repo_path, default_branch, description, visibility, ci_enabled)
VALUES ('teamone', 'teamone/teamone.git', 'main', 'TeamOne 自研一体化研发协作平台主仓库', 'INTERNAL', false)
ON CONFLICT (repo_path) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    updated_at = now()
WHERE eng.repository.name IS NULL;
