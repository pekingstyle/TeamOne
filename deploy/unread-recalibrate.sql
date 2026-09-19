-- 未读数校准脚本（INC-2 T-3）：deploy/unread-recalibrate.sql
--
-- 未读真相 = SQL 差值：collab.conversation.last_message_id
--            - collab.user_conversation_cursor.last_read_message_id（只前进不回退）。
-- Valkey unread:{uid} Hash（field=convId, value=unread）仅缓存加速，宕机 WARN 降级不补写；
-- 故缓存可能因进程重启/降级而漂移——本脚本每日对账（M3 挂 pg_cron 或运维定时），
-- 以 DB 真相重灌缓存。Valkey 完全不可用不影响正确性：GET /conversations 的 unreadCount
-- 恒走 SQL 差值，重灌仅为 WS/前端加速层兜底。
--
-- 用法（WSL 内，镜像模式下宿主 6379 直达）:
--   docker exec -i teamone-postgres psql -U postgres -d teamone < deploy/unread-recalibrate.sql
--   再由应用侧（或 redis-cli）按下方结果集重灌 unread:{uid}
--
-- 幂等：可重复执行；游标只由 read 帧 GREATEST 推进，本脚本不写 cursor（只读对账）。

-- ① 校准结果集：全量用户×会话的未读数（含 cursor 缺行=全未读）
SELECT m.user_id,
       m.conversation_id,
       GREATEST(COALESCE(c.last_message_id, 0) - COALESCE(cur.last_read_message_id, 0), 0) AS unread
FROM collab.conversation_member m
JOIN collab.conversation c        ON c.id = m.conversation_id
LEFT JOIN collab.user_conversation_cursor cur
       ON cur.user_id = m.user_id AND cur.conversation_id = m.conversation_id;

-- ② 游标越界自检（应恒为 0 行；出现即说明 read 越界校验被绕过——红线 2 告警口）
SELECT 'cursor_out_of_bounds' AS check_item, cur.user_id, cur.conversation_id
FROM collab.user_conversation_cursor cur
JOIN collab.conversation c ON c.id = cur.conversation_id
WHERE cur.last_read_message_id > COALESCE(c.last_message_id, 0);
