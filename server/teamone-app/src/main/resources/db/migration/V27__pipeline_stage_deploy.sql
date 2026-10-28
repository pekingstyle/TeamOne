-- ⑧ 部署流水线化（docs/v2/18）：pipeline_job stage 枚举补 deploy（V22 原 CHECK 未含——
-- 与 V26 branch_type 同款教训：加枚举值必须同步迁移 CHECK，否则链式建作业撞约束）。
ALTER TABLE eng.pipeline_job DROP CONSTRAINT pipeline_job_stage_check;
ALTER TABLE eng.pipeline_job ADD CONSTRAINT pipeline_job_stage_check
    CHECK (stage = ANY (ARRAY['build'::text, 'test'::text, 'deploy'::text]));
