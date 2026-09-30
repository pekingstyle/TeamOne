-- ⑥r：branch_type 枚举补 project（交付分支类型，docs/v2/16 §1）——
-- V15 原始 CHECK 未含 project，seeder 运行期补种插入会撞约束。
ALTER TABLE eng.branch_rule DROP CONSTRAINT branch_rule_branch_type_check;
ALTER TABLE eng.branch_rule ADD CONSTRAINT branch_rule_branch_type_check
    CHECK (branch_type = ANY (ARRAY['main'::text, 'develop'::text, 'release'::text, 'hotfix'::text,
                                   'feature'::text, 'fix'::text, 'poc'::text, 'project'::text, 'other'::text]));
