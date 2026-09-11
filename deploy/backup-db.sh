#!/usr/bin/env bash
# 外部 PostgreSQL 每日快照（M1-W1 顺手项；pgBackRest 每日全量 + WAL/PITR 留 M3 交付）
#
# 用法（WSL 内执行，凭据从 deploy/.env 读取，不写死在本脚本）：
#   bash /mnt/c/Users/afire/Workspace/Electron/TeamOne/deploy/backup-db.sh [输出目录]
# 计划任务建议（WSL crontab）：
#   30 2 * * * bash /mnt/c/Users/afire/Workspace/Electron/TeamOne/deploy/backup-db.sh
#
# 保留策略：输出目录内最近 14 份，更早自动清理。
# 密码只经环境变量进入一次性容器，不落盘、不出现在 ps 参数之外的任何位置。
#                                                        —— Ivan Yang, 2026-09-11
set -euo pipefail

ENV_FILE="${TEAMONE_ENV_FILE:-/mnt/c/Users/afire/Workspace/Electron/TeamOne/deploy/.env}"
OUT_DIR="${1:-/mnt/c/Users/afire/Workspace/Electron/TeamOne/backups}"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# 解析 jdbc:postgresql://host:port/dbname?params
HOST_PORT="${TEAMONE_DB_URL#jdbc:postgresql://}"
DBHOST="${HOST_PORT%%/*}"
DBPORT="${DBHOST##*:}"
DBHOST="${DBHOST%%:*}"
DBNAME="${HOST_PORT#*/}"
DBNAME="${DBNAME%%\?*}"

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$OUT_DIR/teamone-$STAMP.sql.gz"

echo "[i] pg_dump ${DBHOST}:${DBPORT}/${DBNAME} -> $FILE"
docker run --rm -i -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
  pg_dump -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" --no-owner \
  | gzip > "$FILE"

echo "[ok] $(du -h "$FILE" | cut -f1) $(basename "$FILE")"

# 保留最近 14 份
ls -1t "$OUT_DIR"/teamone-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
