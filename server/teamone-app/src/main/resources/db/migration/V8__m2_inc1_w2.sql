-- V8：M2-INC-1 W2（08-M2实施计划 §5 W2：冲突批算器原料 + 工作日历种子）
-- 红线：只追加（V1~V7 任何对象禁改；本文件仅加列 + 种子数据两类追加动作）

-- ① estimate_hours（负载摊销原料；红线④口径：estimate_hours 只做负载，story_points 只做进度）
--    numeric(6,1)：单人单任务 ≤ 9999.9h；存量行 NULL（批算摊销时按无原料跳过，不回填）
ALTER TABLE prd.work_item ADD COLUMN estimate_hours numeric(6,1);
COMMENT ON COLUMN prd.work_item.estimate_hours IS
  '预估工时（insight 冲突批算 CF-1/CF-4 按工作日摊销；进度聚合仍走 story_points）';

-- ② calendar 种子（方向审查②；platform.calendar 实际结构（V4:196 建表，V7 自 prd 迁入）：
--    date date PRIMARY KEY / is_workday boolean —— 列名为 date 非 cal_date，写法据此定）
--    周一~五为工作日（isodow 1..5），周六日非工作日；ON CONFLICT 走 date 主键，重放幂等
INSERT INTO platform.calendar (date, is_workday)
SELECT d::date, (EXTRACT(isodow FROM d) < 6)
FROM generate_series('2026-01-01'::date, '2027-12-31'::date, interval '1 day') d
ON CONFLICT (date) DO NOTHING;

-- ③ 节假日：原型 store.ts HOLIDAYS（'2026-10-01/02/03'，§5.1 workdays 剔除集）
--    种子数据可运维 UPDATE（后续年份/调休同法维护，不改迁移）
UPDATE platform.calendar SET is_workday = false
WHERE date IN ('2026-10-01'::date, '2026-10-02'::date, '2026-10-03'::date);
