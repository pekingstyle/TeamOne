-- V15：分支管理策略（eng.branch_rule）——分支模型可配置 + MR 目标约束（分支治理批）
-- 遵循红线：
--   * 只追加，不改既有结构
--   * 主键统一 gen_random_uuid()
--   * 零硬编码 repo uuid：本迁移只建表不插种子；GitFlow 默认规则由运行期
--     BranchRuleSeeder（ApplicationRunner，幂等：仅补零规则仓库）补齐，
--     规避「本地库首次迁移早于 Git 仓库初始化」的外键违规启动循环（同 V12 复盘教训）

-- ================= eng.branch_rule（分支规则表） =================
-- 语义：
--   name_pattern   分支名 glob 模式（'*' 通配任意字符，应用层匹配、忽略大小写）
--   base_branch    起源分支（如 feature/* 基于 develop；NULL=不限定）
--   merge_target   合入目标（如 release/* → main；NULL=不设固定目标；非 NULL 时 MR 创建强校验）
--   allow_direct_push       true=允许绕过 MR 直推（执行点在 git hook 侧，本批仅落库）
--   auto_delete_after_merge true=合并后自动删除源分支（执行点在合并流程，本批仅落库）
CREATE TABLE IF NOT EXISTS eng.branch_rule (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id                 uuid NOT NULL REFERENCES eng.repository(id) ON DELETE CASCADE,
  branch_type             text NOT NULL
                          CHECK (branch_type IN ('main', 'develop', 'release', 'hotfix', 'feature', 'fix', 'poc', 'other')),
  name_pattern            text NOT NULL,
  base_branch             text,
  merge_target            text,
  allow_direct_push       boolean NOT NULL DEFAULT false,
  auto_delete_after_merge boolean NOT NULL DEFAULT false,
  description             text,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  -- 每类分支至多一条规则（PUT 整仓替换式保存按此唯一键落库）
  CONSTRAINT uq_branch_rule_repo_type UNIQUE (repo_id, branch_type)
);

CREATE INDEX IF NOT EXISTS idx_branch_rule_repo ON eng.branch_rule (repo_id);
