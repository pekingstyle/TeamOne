-- V23：产品与项目客制化双轨（⑥o · docs/v2/15）
-- 遵循红线：
--   * 只追加（prd.project 新表 / work_item 加列），不改既有结构；主键统一 gen_random_uuid()
--   * 零硬编码业务 uuid（无种子数据依赖）
--   * 客制化需求仍挂产品（product_id 不变），source_project_id 只记来源（15 §3.4 规则 1：
--     产品是唯一的工程账本，项目详情 = product_id=产品 ∧ source_project_id=项目）

-- ================= prd.project（项目交付实例线，15 §3.1） =================
CREATE TABLE prd.project (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name             text NOT NULL UNIQUE,             -- 项目名（如「A 集团协同平台实施」）
  customer_name    text,                             -- 客户名
  status           text NOT NULL DEFAULT 'delivering'
                   CHECK (status IN ('delivering','accepted','closed')),  -- 交付中/已验收/已关闭
  product_id       uuid NOT NULL REFERENCES prd.product(id),  -- 交付范围（一期单产品；多产品 M6）
  manager_id       uuid REFERENCES platform.app_user(id),    -- 项目经理
  start_date       date,                             -- 启动日
  plan_accept_date date,                             -- 计划验收日
  version          int  NOT NULL DEFAULT 0,          -- 乐观锁（If-Match 同工作项惯例）
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE prd.project IS '项目交付实例（⑥o 客制化双轨）：客制化需求挂产品同时记 source_project_id，回流走 promote-to-product';

-- ================= prd.work_item 加列（15 §3.2：来源/回流目标） =================
ALTER TABLE prd.work_item ADD COLUMN origin varchar(16) NOT NULL DEFAULT 'product'
  CHECK (origin IN ('product','project_custom'));   -- 需求来源：产品标准 / 项目客制
ALTER TABLE prd.work_item ADD COLUMN source_project_id uuid REFERENCES prd.project(id);
  -- origin=project_custom 时的来源项目（需求挂产品，同时记来源）
ALTER TABLE prd.work_item ADD COLUMN promoted_to_id uuid REFERENCES prd.work_item(id);
  -- 回流目标需求 id；非空=已回流（幂等防重，15 §3.4 规则 2）
CREATE INDEX idx_work_item_source_project ON prd.work_item (source_project_id)
  WHERE source_project_id IS NOT NULL;              -- 项目详情/客制化需求视图热点
CREATE INDEX idx_work_item_promoted_to ON prd.work_item (promoted_to_id)
  WHERE promoted_to_id IS NOT NULL;                 -- 回流幂等判定

-- ================= work_item_link.relation CHECK 扩值（15 §3.3） =================
-- promoted_from：to_item 由 from_item 回流而来——回流可追溯链（审计+图谱）。
-- V4 建表时列为内联 CHECK，PG 自动命名 work_item_link_relation_check；先 drop 再显式重建。
ALTER TABLE prd.work_item_link DROP CONSTRAINT work_item_link_relation_check;
ALTER TABLE prd.work_item_link ADD CONSTRAINT ck_work_item_link_relation
  CHECK (relation IN ('discovered_in','relates_to','fixed_by','blocks','promoted_from'));
