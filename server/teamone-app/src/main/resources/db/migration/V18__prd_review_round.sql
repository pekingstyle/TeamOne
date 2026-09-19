-- V18：R-6 需求评审团签真实化 + 纪要（B4 批 · docs/v2/12 §1 R-6 / 裁决 D2）
-- 遵循红线：
--   * 只追加，不改既有结构；主键统一 gen_random_uuid()
--   * 零硬编码业务 uuid（无种子数据依赖）
--   * 会签真相表 prd.requirement_review（V4）不动：轮次结论 = round 内逐人 result 聚合，
--     本表只落「轮次纪要 + 结论时间」两类派生事实（红线①：状态/进度不落冗余列的例外收口，
--     minutes/summary 属文档引用而非状态，允许落列）

-- ================= prd.review_round（评审轮次纪要，round 唯一） =================
-- 每次 submit 轮次 +1（requirement_review 已按 round 逐人建行）；
-- 本表按 (requirement_id, round) 稀疏随行：GET review-rounds 对已有评审记录的轮次自动补行；
-- minutes_file_id → platform.file（两步制文件先 presign/complete 拿 fileId 再挂载）；
-- concluded_at = 该轮会签出结论（任一 rejected 回 draft / 全员 approved 受理）的时刻，评审中为 NULL。
CREATE TABLE prd.review_round (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id  uuid NOT NULL REFERENCES prd.work_item(id),
  round           int NOT NULL,
  minutes_file_id uuid,                                     -- 逻辑引用 platform.file.id（可空：纪要可选，D2）
  summary         text,                                     -- 轮次结论摘要（可随后续 PUT 补充）
  concluded_at    timestamptz,                              -- 轮次结论时刻（评审中 NULL）
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (requirement_id, round)
);

COMMENT ON TABLE prd.review_round IS '需求评审轮次纪要（R-6/D2）：round 唯一，纪要挂 platform.file，结论时刻由会签判定回写';
