-- V7：M2-INC-1 W1（08-M2实施计划 §5 W1：七项清账 + 层级骨架）
-- 红线：
--   * 只追加（V1~V6 任何对象禁改；本文件仅做加列/搬 schema/建新表三类追加动作）
--   * 主键 gen_random_uuid()（外部 PG，uuidv7 不可用，S-2 结论同 V4/V6）
--   * 只追加纪律：冲突快照表为 W2 批算器产出落点，本迁移不填种子

-- ================= 清账⑤：calendar 归位（05 §2.3 六 schema 表：platform | calendar） =================
-- V4:196 误落 prd schema；工作日历属平台公共数据（insight 批算 CF-1 跨域读取，不属产品研发域）
ALTER TABLE prd.calendar SET SCHEMA platform;

-- ================= W2 冲突批算器落点：prd.conflict_snapshot（05 §2.3 定稿结构） =================
-- 快照式真相（INC-1 红线②）：读端点只查本表，不实时计算；kind CF-1~6 见 02 §三冲突公式
CREATE TABLE prd.conflict_snapshot (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind        text NOT NULL CHECK (kind IN ('CF-1','CF-2','CF-3','CF-4','CF-5','CF-6')),
  payload     jsonb NOT NULL,                      -- 冲突明细（当事人/区间/容器等，批算器写入）
  detected_at timestamptz NOT NULL DEFAULT now(),  -- 快照批时间（每日全量 + 变更增量）
  resolved_at timestamptz                          -- 消解时间（复检不再命中时回填）
);
CREATE INDEX idx_conflict_snapshot_kind_detected ON prd.conflict_snapshot (kind, detected_at DESC);
COMMENT ON TABLE prd.conflict_snapshot IS '冲突快照（CF-1~6 批任务产出，快照式只读；05 §2.3/§6.1）';

-- ================= sprint 完成标记（05 §6.1 迭代完成：POST /sprints/{id}/complete） =================
-- sprint 表无 status 列（M1 建表定稿）；完成态以 completed_at 时间戳表达（NULL=进行中），
-- 完成动作：未完成工作项滚动 + 同事务 outbox(sprint.completed)，见 teamone-prd SprintService
ALTER TABLE prd.sprint ADD COLUMN completed_at timestamptz;
