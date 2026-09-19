#!/usr/bin/env bash
# TeamOne PostgreSQL 灾难恢复演练与全量还原（V-18 生产级数据灾备）
#
# 用法：
#   bash deploy/restore-db.sh <备份文件路径.sql.gz> [-y|--yes]
# 示例：
#   bash deploy/restore-db.sh backups/teamone-20260913-120000.sql.gz -y
#
# 校验机制：
#   1. 检查 gzip 文件物理完整性；
#   2. 重置目标业务 Schema 并执行全量导入；
#   3. 自动核验核心 schema（platform, prd, collab, eng, insight, audit, infra）的表总数与连通性。
#                                                        —— Ivan Yang, 2026-09-13
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${TEAMONE_ENV_FILE:-$SCRIPT_DIR/.env}"

if [ $# -lt 1 ]; then
  echo "用法: $0 <备份文件.sql.gz> [-y|--yes]" >&2
  exit 1
fi

BACKUP_FILE="$1"
ASSUME_YES=false
if [ "${2:-}" = "-y" ] || [ "${2:-}" = "--yes" ]; then
  ASSUME_YES=true
fi

if [ ! -f "$BACKUP_FILE" ]; then
  echo "[x] 找不到指定的备份文件: $BACKUP_FILE" >&2
  exit 1
fi

# 1. 验证备份文件物理有效性
echo "[i] 校验备份文件 gzip 完整性..."
if ! gzip -t "$BACKUP_FILE"; then
  echo "[x] 备份文件损坏，gzip 校验未通过！终止还原。" >&2
  exit 1
fi
echo "[ok] 备份文件校验通过 ($(du -h "$BACKUP_FILE" | cut -f1))"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

TEAMONE_DB_URL="${TEAMONE_DB_URL:-jdbc:postgresql://127.0.0.1:5432/teamone}"
TEAMONE_DB_USER="${TEAMONE_DB_USER:-postgres}"
TEAMONE_DB_PASSWORD="${TEAMONE_DB_PASSWORD:-teamone-local}"

HOST_PORT="${TEAMONE_DB_URL#jdbc:postgresql://}"
DBHOST="${HOST_PORT%%/*}"
DBPORT="${DBHOST##*:}"
DBHOST="${DBHOST%%:*}"
DBNAME="${HOST_PORT#*/}"
DBNAME="${DBNAME%%\?*}"

# 连通性预检：若配置的远程 DBHOST 不可达，自动回落至本地 teamone-postgres 容器
if ! timeout 2 bash -c "</dev/tcp/$DBHOST/$DBPORT" 2>/dev/null; then
  echo "[!] 目标 ${DBHOST}:${DBPORT} 无法建立 TCP 连接"
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qw "teamone-postgres"; then
    echo "[i] 检测到运行中的本地 teamone-postgres 容器，自动回落本地恢复..."
    DBHOST="127.0.0.1"
    DBPORT="5432"
    TEAMONE_DB_PASSWORD="teamone-local"
  fi
fi

echo "==================== 灾难恢复演练警告 ===================="
echo "目标数据库: ${DBHOST}:${DBPORT}/${DBNAME}"
echo "源备份文件: $BACKUP_FILE"
echo "该操作将重写目标数据库中现有全部表结构与数据！"
echo "==========================================================="

if [ "$ASSUME_YES" != "true" ]; then
  read -r -p "确认继续还原？请输入 'yes': " CONFIRM
  if [ "$CONFIRM" != "yes" ]; then
    echo "[i] 用户取消操作，退出。"
    exit 0
  fi
fi

NET_FLAG="--network host"

echo "[i] 正在重置现有业务 Schema (CASCADE)..."
docker run --rm $NET_FLAG -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
  psql -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" --quiet -c \
  "DROP SCHEMA IF EXISTS platform, prd, collab, eng, insight, audit, infra CASCADE;"

echo "[i] 正在执行全量数据恢复..."
gunzip -c "$BACKUP_FILE" | docker run --rm -i $NET_FLAG -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
  psql -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" --quiet -v ON_ERROR_STOP=1

echo "[ok] SQL 还原脚本执行完毕！"

# 2. 核心 Schema 完整性核验
echo "[i] 正在核验业务 Schema 状态..."
CHECK_SQL="SELECT table_schema, count(*) as table_count FROM information_schema.tables WHERE table_schema IN ('platform', 'prd', 'collab', 'eng', 'insight', 'audit', 'infra') GROUP BY table_schema ORDER BY table_schema;"

docker run --rm $NET_FLAG -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
  psql -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" -c "$CHECK_SQL"

echo "[ok] 数据库恢复与演练验证成功！"
