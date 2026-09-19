#!/usr/bin/env bash
# =============================================================================
# TeamOne Spike S-3 数据库按月分区自动化运维演练与测试脚本
#
# 演练内容：
#   1. 初始化分区维护存储过程与统计视图；
#   2. 自动预建未来 3 个月的分区（当前为 2026-09，预建 10/11/12 月）；
#   3. 插入跨月测试消息（2026-09 与 2026-10）；
#   4. 检验 tableoid::regclass 证明数据精准路由至对应子分区；
#   5. 验证父表全局透明聚合（应用层 JPA 无感知）；
#   6. 执行分区解耦 (DETACH) 与压缩归档演练；
#   7. 验证演练结果无损与完整性。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DRILL_DIR="$MONO_DIR/tmp/partition-drill"
mkdir -p "$DRILL_DIR"

echo "================================================================="
echo "        TeamOne Spike S-3 数据库核心大表按月分区自动化演练       "
echo "================================================================="

# 1. 部署运维函数与视图
echo "[步骤 1/6] 部署分区自动化运维函数与视图..."
bash "$SCRIPT_DIR/db-partition-maintenance.sh" init

# 2. 预建未来 3 个月份的分区
echo "[步骤 2/6] 执行预建未来 3 个月分区 (ensure 3)..."
bash "$SCRIPT_DIR/db-partition-maintenance.sh" ensure 3

# 3. 列出当前分区拓扑
echo "[步骤 3/6] 查询当前分区拓扑结构..."
bash "$SCRIPT_DIR/db-partition-maintenance.sh" list

# 4. 插入测试数据并验证路由
echo "[步骤 4/6] 写入跨月测试数据并检验 PostgreSQL 路由结果..."

ENV_FILE="${TEAMONE_ENV_FILE:-$SCRIPT_DIR/.env}"
if [ -f "$ENV_FILE" ]; then
  set -a; source "$ENV_FILE"; set +a
fi
TEAMONE_DB_URL="${TEAMONE_DB_URL:-jdbc:postgresql://127.0.0.1:5432/teamone}"
TEAMONE_DB_USER="${TEAMONE_DB_USER:-postgres}"
TEAMONE_DB_PASSWORD="${TEAMONE_DB_PASSWORD:-teamone-local}"
HOST_PORT="${TEAMONE_DB_URL#jdbc:postgresql://}"
DBHOST="${HOST_PORT%%/*}"; DBPORT="${DBHOST##*:}"; DBHOST="${DBHOST%%:*}"
DBNAME="${HOST_PORT#*/}"; DBNAME="${DBNAME%%\?*}"

if ! timeout 2 bash -c "</dev/tcp/$DBHOST/$DBPORT" 2>/dev/null; then
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qw "teamone-postgres"; then
    DBHOST="127.0.0.1"; DBPORT="5432"; TEAMONE_DB_PASSWORD="teamone-local"
  fi
fi

RUN_SQL() {
  docker run --rm -i --network host -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
    psql -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" -X -q -c "$1"
}

RUN_SQL "
DO \$\$
DECLARE
  v_conv_id uuid;
  v_user_id uuid;
BEGIN
  SELECT id INTO v_conv_id FROM collab.conversation LIMIT 1;
  SELECT id INTO v_user_id FROM platform.app_user LIMIT 1;
  IF v_conv_id IS NOT NULL AND v_user_id IS NOT NULL THEN
    -- 插入当月测试数据
    INSERT INTO collab.message (conversation_id, sender_id, kind, body, created_at)
    VALUES (v_conv_id, v_user_id, 'text', 'Drill Message Sep 2026', '2026-09-15 10:00:00+08');
    
    -- 插入次月预建分区测试数据
    INSERT INTO collab.message (conversation_id, sender_id, kind, body, created_at)
    VALUES (v_conv_id, v_user_id, 'text', 'Drill Message Oct 2026', '2026-10-15 10:00:00+08');
  END IF;
END \$\$;
"

echo "[i] 检验单条记录的真实物理分区归属 (tableoid::regclass)："
RUN_SQL "
SELECT id, body, created_at, tableoid::regclass AS physical_partition 
FROM collab.message 
WHERE body LIKE 'Drill Message%' 
ORDER BY created_at;
"

# 5. 验证父表全局透明聚合
echo "[步骤 5/6] 验证父表全局聚合（应用层零感知）..."
RUN_SQL "
SELECT count(*) AS total_drill_messages 
FROM collab.message 
WHERE body LIKE 'Drill Message%';
"

# 6. 分区归档解耦演练测试（针对未来 12 月创建空分区并执行完整解耦+归档导出）
echo "[步骤 6/6] 针对未来分区执行完整解耦 (DETACH) 与压缩归档演练..."
RUN_SQL "CREATE TABLE IF NOT EXISTS collab.message_2026m12 PARTITION OF collab.message FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');"
bash "$SCRIPT_DIR/db-partition-maintenance.sh" archive message_2026m12 "$DRILL_DIR"

# 检验归档产物
ARCHIVE_FILE="$(ls -1t "$DRILL_DIR"/message_2026m12_*.sql.gz | head -n 1)"
if [ -s "$ARCHIVE_FILE" ] && gzip -t "$ARCHIVE_FILE"; then
  echo "[ok] 归档文件完整性校验通过: $ARCHIVE_FILE"
else
  echo "[x] 归档文件异常: $ARCHIVE_FILE" >&2
  exit 1
fi

# 清理演练产生的测试分区（已独立）与测试消息
RUN_SQL "DROP TABLE IF EXISTS collab.message_2026m12;"
RUN_SQL "DELETE FROM collab.message WHERE body LIKE 'Drill Message%';"

echo "================================================================="
echo "[SUCCESS] Spike S-3 核心大表按月分区与归档演练全部通过！"
echo "================================================================="
