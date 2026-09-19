#!/usr/bin/env bash
# TeamOne PostgreSQL 自动快照与全量冷备（V-18 生产级数据灾备）
#
# 用法（WSL 内执行，凭据优先从 deploy/.env 读取）：
#   bash deploy/backup-db.sh [输出目录]
# 计划任务建议（WSL crontab）：
#   30 2 * * * bash /path/to/TeamOne/deploy/backup-db.sh
#
# 保留策略：输出目录内最近 14 份，更早自动清理。
# 容错机制：主数据库不可达时，自动回落本地已拉起的 teamone-postgres 容器。
#                                                        —— Ivan Yang, 2026-09-13
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${TEAMONE_ENV_FILE:-$SCRIPT_DIR/.env}"
OUT_DIR="${1:-$MONO_DIR/backups}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

TEAMONE_DB_URL="${TEAMONE_DB_URL:-jdbc:postgresql://127.0.0.1:5432/teamone}"
TEAMONE_DB_USER="${TEAMONE_DB_USER:-postgres}"
TEAMONE_DB_PASSWORD="${TEAMONE_DB_PASSWORD:-teamone-local}"

# 解析 jdbc:postgresql://host:port/dbname?params
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
    echo "[i] 检测到运行中的本地 teamone-postgres 容器，自动回落本地备份..."
    DBHOST="127.0.0.1"
    DBPORT="5432"
    TEAMONE_DB_PASSWORD="teamone-local"
  fi
fi

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$OUT_DIR/teamone-$STAMP.sql.gz"

echo "[i] 开始备份 PostgreSQL 数据库: ${DBHOST}:${DBPORT}/${DBNAME} -> $FILE"

NET_FLAG="--network host"
docker run --rm -i $NET_FLAG -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
  pg_dump -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" --no-owner \
  | gzip > "$FILE"

# 完整性自检
if [ ! -s "$FILE" ]; then
  echo "[x] 备份失败：备份文件为空: $FILE" >&2
  rm -f "$FILE"
  exit 1
fi

if ! gzip -t "$FILE"; then
  echo "[x] 备份失败：gzip 完整性校验未通过: $FILE" >&2
  rm -f "$FILE"
  exit 1
fi

SIZE="$(du -h "$FILE" | cut -f1)"
echo "[ok] 数据库备份成功: $FILE (大小: $SIZE)"

# 保留最近 14 份历史备份
ls -1t "$OUT_DIR"/teamone-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
echo "[i] 保留策略执行完毕（保留最近 14 份快照）"
