-- V17：R-9 发布一致性与流水线联动（B2 批 · docs/v2/12 §1 R-9）
-- 遵循红线：
--   * 只追加，不改既有结构；主键统一 gen_random_uuid()
--   * eng 域零 prd 编译期/外键耦合（ArchUnit R5）——release_id 为逻辑引用 prd.release.id，
--     不建 FOREIGN KEY，一致性由应用层门禁与查询侧保证
--   * 零硬编码 repo/release UUID

-- ================= eng.pipeline_run 关联交付版本（R-9 b：构建→测试→部署阶段真实展示） =================
-- 关联版本的流水线运行（release_id 写入点：后续按 release 触发流水线时回填，M4/M5 联动；本批不自动触发）
ALTER TABLE eng.pipeline_run ADD COLUMN IF NOT EXISTS release_id uuid NULL;

CREATE INDEX IF NOT EXISTS idx_pipeline_run_release
  ON eng.pipeline_run (release_id) WHERE release_id IS NOT NULL;

-- ================= eng.deployment（部署登记表：env/status/artifact_version/deployed_at） =================
-- 本批仅「登记 + 展示」闭环（真实执行联动属 M4/M5，docs/v2/11）；deployed_at 缺省登记时刻
CREATE TABLE IF NOT EXISTS eng.deployment (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  release_id       uuid NOT NULL,                        -- 逻辑引用 prd.release.id（不建 FK）
  pipeline_run_id  uuid,                                 -- 逻辑引用 eng.pipeline_run.id（同库 eng 域内，仍不建 FK）
  env              text NOT NULL,                        -- 部署环境（dev/staging/prod）
  status           text NOT NULL DEFAULT 'success'
                   CHECK (status IN ('running', 'success', 'failed', 'rolled_back')),
  artifact_version text,                                 -- 部署制品版本（如 2.4.0-rc.3）
  deployed_by      uuid,                                 -- 登记人（逻辑引用 platform.app_user.id）
  deployed_at      timestamptz NOT NULL DEFAULT now(),
  note             text
);

CREATE INDEX IF NOT EXISTS idx_deployment_release ON eng.deployment (release_id);
