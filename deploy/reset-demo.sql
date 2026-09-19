-- ============================================================================
-- TeamOne 演示数据重置（P1-2 · 一次性收口，幂等可重复执行）
--
-- 语义：
--   1. 清理测试残留：删除 key >= D-90 的缺陷与 REQ-4~REQ-8 需求及其 link/评审/
--      自动建题会话/消息/游标/通知/冲突快照引用/关联提交，M2 验证产生的测试版本
--      （v2.5.0 / v1.0(p2) 等）与测试迭代，及对应 worktree_report / pipeline_run 演示行；
--   2. v2.4.0 复位：status=coding + blocked=false + 清空阻塞清单；
--   3. 业务种子补齐（不存在才插，幂等）：G-1 / p1 / 组件 / RM-1 / S-1、S-2 /
--      D-88(致命·修复中·挂v2.4.0) / D-87(一般) / REQ-1~3 及其评审记录；
--   4. 断言业务种子在场（缺失即 RAISE EXCEPTION 回滚整库事务——误删种子视为事故）；
--   5. 末尾输出各表计数供人工核对。
--
-- 执行（服务运行中可直接执行，事务内删完即生效）：
--   MSYS_NO_PATHCONV=1 wsl -d Ubuntu-24.04 -- bash -lc \
--     "docker exec -i teamone-postgres psql -U postgres -d teamone -v ON_ERROR_STOP=1" \
--     < deploy/reset-demo.sql
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 0) 收集待删除集合（临时表，事务结束自动清理）
-- ---------------------------------------------------------------------------

-- 0.1 测试残留工作项：缺陷 key >= D-90（D-87/D-88 种子保留）+ 需求 key >= REQ-4（REQ-1~3 保留）
CREATE TEMP TABLE _del_items ON COMMIT DROP AS
SELECT id, key, type FROM prd.work_item
WHERE (type = 'defect'      AND key >= 'D-90')
   OR (type = 'requirement' AND key >= 'REQ-4');

-- 0.2 测试版本（保留 v2.4.0）与测试迭代（保留 S-1/S-2）
CREATE TEMP TABLE _del_releases ON COMMIT DROP AS
SELECT id, key FROM prd.release WHERE key <> 'v2.4.0';

CREATE TEMP TABLE _del_sprints ON COMMIT DROP AS
SELECT id, name FROM prd.sprint WHERE name NOT LIKE 'S-1%' AND name NOT LIKE 'S-2%';

-- 0.3 指向待删对象的自动建题会话（defect/requirement/sprint/release 五类目标一并清理）
CREATE TEMP TABLE _del_convs ON COMMIT DROP AS
SELECT c.id FROM collab.conversation c
WHERE (c.target_type = 'defect'      AND c.target_id IN (SELECT id FROM _del_items))
   OR (c.target_type = 'requirement' AND c.target_id IN (SELECT id FROM _del_items))
   OR (c.target_type = 'release'     AND c.target_id IN (SELECT id FROM _del_releases))
   OR (c.target_type = 'sprint'      AND c.target_id IN (SELECT id FROM _del_sprints));

-- ---------------------------------------------------------------------------
-- 1) 会话链路：消息 → 游标 → 成员 → 会话（先子后父，FK 无级联）
-- ---------------------------------------------------------------------------
DELETE FROM collab.message                   WHERE conversation_id IN (SELECT id FROM _del_convs);
DELETE FROM collab.user_conversation_cursor  WHERE conversation_id IN (SELECT id FROM _del_convs);
DELETE FROM collab.conversation_member       WHERE conversation_id IN (SELECT id FROM _del_convs);
DELETE FROM collab.conversation              WHERE id            IN (SELECT id FROM _del_convs);

-- ---------------------------------------------------------------------------
-- 2) 通知 / 冲突快照 / 关联提交 / 工作项关联 / 需求评审（引用待删对象的行）
-- ---------------------------------------------------------------------------
-- 通知：payload 投影含 requirementId/requirement key 等事实，按 id 文本或 key 文本匹配
DELETE FROM collab.notification n
WHERE n.payload->>'requirementId' IN (SELECT id::text   FROM _del_items)
   OR EXISTS (SELECT 1 FROM _del_items k WHERE n.payload::text LIKE '%' || k.key || '%');

-- 冲突快照：subjectId/userId/relatedTaskIds 引用待删工作项的快照行（红线②：真相只认本表，删行即消解）
DELETE FROM prd.conflict_snapshot cs
WHERE cs.payload->>'subjectId' IN (SELECT id::text FROM _del_items)
   OR cs.payload->>'userId'    IN (SELECT id::text FROM _del_items)
   OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(cs.payload->'relatedTaskIds') t(t)
              WHERE t.t IN (SELECT id::text FROM _del_items));

-- 关联提交（push hook refs #KEY 留痕）
DELETE FROM eng.commit_work_item WHERE work_item_key IN (SELECT key FROM _del_items);

-- 工作项通用关联（双向）
DELETE FROM prd.work_item_link
WHERE from_item_id IN (SELECT id FROM _del_items)
   OR to_item_id   IN (SELECT id FROM _del_items);

-- 需求评审记录
DELETE FROM prd.requirement_review WHERE requirement_id IN (SELECT id FROM _del_items);

-- M2 验证演示行：客户端工作树上报与流水线运行（运行时产物，非种子；当前 0 行，重跑幂等）
DELETE FROM eng.worktree_report;
DELETE FROM eng.pipeline_run;

-- ---------------------------------------------------------------------------
-- 3) 断开保留工作项指向待删对象的引用（防 FK 残挂）
-- ---------------------------------------------------------------------------
UPDATE prd.work_item SET found_in_id    = NULL WHERE found_in_id    IN (SELECT id FROM _del_items);
UPDATE prd.work_item SET related_id     = NULL WHERE related_id     IN (SELECT id FROM _del_items);
UPDATE prd.work_item SET requirement_id = NULL WHERE requirement_id IN (SELECT id FROM _del_items);
UPDATE prd.work_item SET parent_id      = NULL WHERE parent_id      IN (SELECT id FROM _del_items);

-- ---------------------------------------------------------------------------
-- 4) 删除测试残留工作项本体
-- ---------------------------------------------------------------------------
DELETE FROM prd.work_item WHERE id IN (SELECT id FROM _del_items);

-- ---------------------------------------------------------------------------
-- 5) 测试版本与测试迭代：先断引用，再删子后删父
-- ---------------------------------------------------------------------------
UPDATE prd.work_item    SET sprint_id          = NULL WHERE sprint_id          IN (SELECT id FROM _del_sprints)
                                                     OR release_id           IN (SELECT id FROM _del_releases)
                                                     OR blocked_release_id   IN (SELECT id FROM _del_releases);
UPDATE prd.roadmap_item SET release_id         = NULL WHERE release_id         IN (SELECT id FROM _del_releases);
DELETE FROM prd.sprint  WHERE id IN (SELECT id FROM _del_sprints);
DELETE FROM prd.release WHERE id IN (SELECT id FROM _del_releases);

-- ---------------------------------------------------------------------------
-- 6) v2.4.0 复位：coding + 未阻塞 + 清空阻塞清单（D-88 的 blocked_release_id 挂链保留）
-- ---------------------------------------------------------------------------
UPDATE prd.release
SET status = 'coding', blocked = false, blocked_defect_ids = '{}'::uuid[]
WHERE key = 'v2.4.0';

-- ---------------------------------------------------------------------------
-- 7) 业务种子补齐（INSERT IF MISSING，幂等；绝不 UPDATE/DELETE 既有种子）
-- ---------------------------------------------------------------------------

-- 7.1 S-2 下一迭代（挂 p1 / v2.4.0，紧随 S-1）
INSERT INTO prd.sprint (name, product_id, release_id, capacity_hours, start_date, due_date)
SELECT 'S-2 下一迭代', p.id, r.id, 320, DATE '2026-09-28', DATE '2026-10-16'
FROM prd.product p
JOIN prd.release r ON r.key = 'v2.4.0'
WHERE p.key = 'p1'
  AND NOT EXISTS (SELECT 1 FROM prd.sprint WHERE name LIKE 'S-2%');

-- 7.2 REQ-1~3（与前端原型同名同文案，in_dev，挂 p1/G-1，admin 提出并负责）
INSERT INTO prd.work_item
  (key, type, title, description, status, priority, assignee_id, reporter_id, product_id, goal_id, path)
SELECT v.key, 'requirement', v.title, v.descr, 'in_dev', v.pri,
       u.id, u.id, p.id, g.id, '/' || lower(v.key) || '/'
FROM (VALUES
  ('REQ-1', '执行器失败重试与熔断机制',
   E'背景：第三方构建资源超时导致流水线大面积失败。\n价值：调度成功率 99.62% → 99.9%。\n验收标准：重试可配置、熔断自动切换、单测 ≥ 6 场景。', 'P0'),
  ('REQ-2', '效能报表看板 2.0',
   E'背景：管理方需要研发效能可视化。\n验收标准：趋势对比、结果分布、多维筛选。', 'P1'),
  ('REQ-3', 'Webhook 投递幂等与重试',
   E'背景：消费方重复收到事件。\n验收标准：幂等键强制、重复返回原结果、投递可追溯。', 'P0')
) AS v(key, title, descr, pri)
JOIN prd.product p ON p.key = 'p1'
LEFT JOIN prd.strategic_goal g ON g.name LIKE 'G-1%'
JOIN platform.app_user u ON u.username = 'admin'
WHERE NOT EXISTS (SELECT 1 FROM prd.work_item w WHERE w.key = v.key);

-- 7.3 评审记录（REQ-1/REQ-3 一轮通过；REQ-2 两轮——首轮驳回带意见，次轮通过，供「最近驳回意见」演示）
INSERT INTO prd.requirement_review (requirement_id, round, reviewer_id, result, comment, decided_at)
SELECT wi.id, 1, u.id, 'approved', '验收标准完整，评审通过', now()
FROM prd.work_item wi CROSS JOIN platform.app_user u
WHERE wi.key = 'REQ-1' AND u.username = 'admin'
ON CONFLICT (requirement_id, round, reviewer_id) DO NOTHING;

INSERT INTO prd.requirement_review (requirement_id, round, reviewer_id, result, comment, decided_at)
SELECT wi.id, 1, u.id, 'rejected', '驳回：验收标准不可量化，请补充可度量的成功率口径与回归范围后再提交。', now()
FROM prd.work_item wi CROSS JOIN platform.app_user u
WHERE wi.key = 'REQ-2' AND u.username = 'admin'
ON CONFLICT (requirement_id, round, reviewer_id) DO NOTHING;

INSERT INTO prd.requirement_review (requirement_id, round, reviewer_id, result, comment, decided_at)
SELECT wi.id, 2, u.id, 'approved', '已补充量化口径（99.9%），回归范围明确，通过。', now()
FROM prd.work_item wi CROSS JOIN platform.app_user u
WHERE wi.key = 'REQ-2' AND u.username = 'admin'
ON CONFLICT (requirement_id, round, reviewer_id) DO NOTHING;

INSERT INTO prd.requirement_review (requirement_id, round, reviewer_id, result, comment, decided_at)
SELECT wi.id, 1, u.id, 'approved', '幂等键设计合理，评审通过', now()
FROM prd.work_item wi CROSS JOIN platform.app_user u
WHERE wi.key = 'REQ-3' AND u.username = 'admin'
ON CONFLICT (requirement_id, round, reviewer_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8) 断言：业务种子必须在场（缺失即回滚整个事务）
-- ---------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM prd.product WHERE key = 'p1') THEN
    RAISE EXCEPTION '种子丢失: p1';
  END IF;
  IF (SELECT count(*) FROM prd.component) = 0 THEN
    RAISE EXCEPTION '种子丢失: 组件';
  END IF;
  IF (SELECT count(*) FROM prd.strategic_goal WHERE name LIKE 'G-1%') = 0 THEN
    RAISE EXCEPTION '种子丢失: G-1';
  END IF;
  IF (SELECT count(*) FROM prd.roadmap_item WHERE name LIKE 'RM-1%') = 0 THEN
    RAISE EXCEPTION '种子丢失: RM-1';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM prd.release WHERE key = 'v2.4.0' AND status = 'coding' AND blocked = false) THEN
    RAISE EXCEPTION '种子丢失/复位失败: v2.4.0(coding,unblocked)';
  END IF;
  IF (SELECT count(*) FROM prd.sprint WHERE name LIKE 'S-1%' OR name LIKE 'S-2%') < 2 THEN
    RAISE EXCEPTION '种子丢失: S-1/S-2';
  END IF;
  IF (SELECT count(*) FROM prd.work_item WHERE key IN ('D-87', 'D-88')) <> 2 THEN
    RAISE EXCEPTION '种子丢失: D-87/D-88';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM prd.work_item w JOIN prd.release r ON r.id = w.blocked_release_id
    WHERE w.key = 'D-88' AND w.severity = '致命' AND w.status = '修复中' AND r.key = 'v2.4.0'
  ) THEN
    RAISE EXCEPTION '种子丢失: D-88(致命·修复中·挂v2.4.0)';
  END IF;
  IF (SELECT count(*) FROM prd.work_item WHERE type = 'requirement' AND key IN ('REQ-1', 'REQ-2', 'REQ-3')) <> 3 THEN
    RAISE EXCEPTION '种子丢失: REQ-1~3';
  END IF;
  IF (SELECT count(*) FROM prd.requirement_review rr
      JOIN prd.work_item w ON w.id = rr.requirement_id WHERE w.key = 'REQ-2' AND rr.result = 'rejected') = 0 THEN
    RAISE EXCEPTION '种子丢失: REQ-2 驳回评审记录';
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 9) 各表计数（人工核对；期望值见行尾注释）
-- ---------------------------------------------------------------------------
SELECT 'prd.work_item(defect)'    AS tbl, count(*) AS cnt FROM prd.work_item WHERE type = 'defect'      -- 期望 2（D-87/D-88）
UNION ALL
SELECT 'prd.work_item(requirement)',     count(*) FROM prd.work_item WHERE type = 'requirement'  -- 期望 3（REQ-1~3）
UNION ALL
SELECT 'prd.release',                    count(*) FROM prd.release                               -- 期望 1（仅 v2.4.0）
UNION ALL
SELECT 'prd.sprint',                     count(*) FROM prd.sprint                                -- 期望 2（S-1/S-2）
UNION ALL
SELECT 'prd.strategic_goal',             count(*) FROM prd.strategic_goal                        -- 期望 ≥1（G-1）
UNION ALL
SELECT 'prd.roadmap_item',               count(*) FROM prd.roadmap_item                          -- 期望 ≥1（RM-1）
UNION ALL
SELECT 'prd.product',                    count(*) FROM prd.product                               -- 期望 1（p1）
UNION ALL
SELECT 'prd.component',                  count(*) FROM prd.component                             -- 期望 2（pipeline-engine/collab-service）
UNION ALL
SELECT 'prd.requirement_review',         count(*) FROM prd.requirement_review                    -- 期望 4
UNION ALL
SELECT 'prd.work_item_link',             count(*) FROM prd.work_item_link                        -- 期望 0
UNION ALL
SELECT 'prd.conflict_snapshot',          count(*) FROM prd.conflict_snapshot                     -- 期望 0（引用待删项已清）
UNION ALL
SELECT 'collab.conversation',            count(*) FROM collab.conversation                       -- 期望 0
UNION ALL
SELECT 'collab.message',                 count(*) FROM collab.message                            -- 期望 0
UNION ALL
SELECT 'collab.notification',            count(*) FROM collab.notification                       -- 期望 0
UNION ALL
SELECT 'eng.commit_work_item',           count(*) FROM eng.commit_work_item                      -- 期望 0
UNION ALL
SELECT 'eng.worktree_report',            count(*) FROM eng.worktree_report                       -- 期望 0
UNION ALL
SELECT 'eng.pipeline_run',               count(*) FROM eng.pipeline_run;                         -- 期望 0

-- ================= UT-1 修复：门禁真相重算（reset 复位后必须与工作项一致） =================
-- v2.4.0 种子含 D-88（致命·修复中·挂本版本）→ 门禁必须为 blocked，状态回 coding
UPDATE prd.release r SET
  blocked = true,
  status = 'coding',
  blocked_defect_ids = COALESCE((
    SELECT array_agg(wi.id) FROM prd.work_item wi
    WHERE wi.blocked_release_id = r.id
      AND wi.severity IN ('致命','严重')
      AND wi.status NOT IN ('已关闭','回归通过')
  ), '{}')
WHERE r.key = 'v2.4.0';

COMMIT;
