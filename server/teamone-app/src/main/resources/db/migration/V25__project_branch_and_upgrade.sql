-- ⑥r 项目交付分支管理（docs/v2/16 §1）：交付分支绑定 + 升级记录。
-- 交付分支 = 客户实例的工程线（project/*，客制化经 feature/* MR 合入，产品升级经 release/* MR 合入）。
ALTER TABLE prd.project ADD COLUMN branch_name text NULL;

-- 升级记录：mr_id 为 eng.mr_review 的逻辑引用（跨 schema 不建 FK，镜像 eng 零 prd 依赖风格）
CREATE TABLE prd.project_upgrade (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  uuid NOT NULL REFERENCES prd.project(id),
    mr_id       uuid NOT NULL,
    from_ref    text NOT NULL,
    status      text NOT NULL DEFAULT 'requested' CHECK (status IN ('requested', 'merged', 'closed')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_project_upgrade_project ON prd.project_upgrade (project_id, created_at DESC);
