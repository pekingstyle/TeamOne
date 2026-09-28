-- ⑥q 迭代收官（docs/v2/15 §9 挂账清偿）：升级预警「产品演进集」查询索引。
-- E 集谓词：product_id = ? AND origin='product' AND component_id IS NOT NULL AND updated_at >= 基线；
-- 部分索引把谓词恒定项收进 WHERE，(product_id, updated_at) 覆盖范围扫描——老产品池下避免全表过滤。
CREATE INDEX IF NOT EXISTS idx_work_item_evolution
    ON prd.work_item (product_id, updated_at)
    WHERE origin = 'product' AND component_id IS NOT NULL;
