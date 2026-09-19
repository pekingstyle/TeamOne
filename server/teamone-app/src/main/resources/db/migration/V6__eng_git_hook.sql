-- V6：W4 Git hook 数据链（07 决策 D-2026-09-11-B §2.2 push 入口与事件；eng.repository 为
--     07 §1 对 05 §2.3 的修订结构：repo_path 相对路径 + default_branch，无 Gitea 外键）
-- 红线：
--   * 只追加（不改 V1~V5 任何对象）
--   * 主键 gen_random_uuid()（外部 PG 17.5，uuidv7 不可用，S-2 结论同 V4）
--   * eng 域零 prd 依赖：product_id/component_id 为逻辑引用（不建 FK），work_item_key 文本化（不引用 prd.work_item）

-- ================= eng.repository（bare repo 映射，05 §2.3/07 §2.1 布局） =================

CREATE TABLE eng.repository (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_path      text NOT NULL UNIQUE,               -- 相对 TEAMONE_GIT_ROOT，如 teamone/web.git
  product_id     uuid,                               -- 逻辑引用 prd.product(id)，不建 FK（eng 零 prd 依赖）
  component_id   uuid,                               -- 逻辑引用 prd.component(id)，不建 FK
  default_branch text NOT NULL DEFAULT 'main',       -- 07 §2.1：git init --bare -b main
  created_at     timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE eng.repository IS 'bare repo 映射（repo_path ↔ 产品/组件；M2 建库流程写入，本迁移不填种子）';

-- ================= eng.commit_work_item（提交↔工作项映射，07 §2.2 hook 解析落点） =================

CREATE TABLE eng.commit_work_item (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_key      text NOT NULL,                       -- 与 hook payload 的 repo 一致（teamone/web.git）
  commit_sha    text NOT NULL,                       -- git 完整 sha（40 位 hex）
  author_name   text,
  author_email  text,
  committed_at  timestamptz,                         -- git %aI（author 严格 ISO 8601）
  subject       text,                                -- git %s
  body          text,                                -- git %b（refs #KEY 解析源，连同 subject）
  work_item_key text NOT NULL,                       -- 如 D-88；文本化，不校验 prd.work_item 存在性
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_commit_work_item UNIQUE (repo_key, commit_sha, work_item_key)  -- 幂等：ON CONFLICT DO NOTHING
);
-- 工作项详情页热点查询：按 key 倒序取关联提交
CREATE INDEX idx_commit_work_item_key ON eng.commit_work_item (work_item_key);
COMMENT ON TABLE eng.commit_work_item IS 'push webhook 解析 refs #KEY 的提交↔工作项映射（重复 push 幂等去重）';
