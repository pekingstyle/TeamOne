#!/usr/bin/env bash
# =============================================================================
# TeamOne 数据库核心大表按月分区自动化运维脚本（Spike S-3）
#
# 功能：
#   1. 初始化运维函数与统计视图（init）
#   2. 自动化预建当前月及未来 N 个月分区（ensure [months_ahead]）
#   3. 查看当前所有分区状态与行数估计（list）
#   4. 解除历史过期分区挂载并导出压缩归档（archive <partition_name> [out_dir]）
#
# 用法：
#   bash deploy/db-partition-maintenance.sh init
#   bash deploy/db-partition-maintenance.sh ensure 3
#   bash deploy/db-partition-maintenance.sh list
#   bash deploy/db-partition-maintenance.sh archive message_2025m01 ./backups/partitions
#
# 容错：自动尝试远程目标，失败回落本地运行中的 teamone-postgres 容器。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${TEAMONE_ENV_FILE:-$SCRIPT_DIR/.env}"
SQL_FILE="$SCRIPT_DIR/db-partition-maintenance.sql"

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

# 连通性预检与本地回落
if ! timeout 2 bash -c "</dev/tcp/$DBHOST/$DBPORT" 2>/dev/null; then
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qw "teamone-postgres"; then
    echo "[i] 目标 $DBHOST 不可达，自动回落本地 teamone-postgres 容器 (127.0.0.1:5432)..."
    DBHOST="127.0.0.1"
    DBPORT="5432"
    TEAMONE_DB_PASSWORD="teamone-local"
  fi
fi

# 封装 psql 与 pg_dump 执行器（优先使用本地已有的 postgres 容器或客户端）
run_psql() {
  local sql_cmd="$1"
  docker run --rm -i --network host -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
    psql -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" -X -q -c "$sql_cmd"
}

run_psql_file() {
  local file_path="$1"
  docker run --rm -i --network host -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
    psql -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" -X -q < "$file_path"
}

run_pg_dump_table() {
  local table_name="$1"
  local dest_file="$2"
  docker run --rm -i --network host -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
    pg_dump -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" -d "$DBNAME" --table="collab.$table_name" --no-owner \
    | gzip > "$dest_file"
}

ACTION="${1:-list}"

case "$ACTION" in
  init)
    echo "[i] 正在初始化分区维护函数与视图 ($SQL_FILE)..."
    run_psql_file "$SQL_FILE"
    echo "[ok] 分区维护函数及视图部署就绪。"
    ;;

  ensure)
    MONTHS="${2:-3}"
    echo "[i] 正在巡检并自动预建未来 $MONTHS 个月的分区..."
    # 保证函数存在
    run_psql_file "$SQL_FILE" >/dev/null 2>&1 || true
    run_psql "SELECT * FROM collab.maintain_message_partitions($MONTHS);"
    echo "[ok] 分区预建检测完成。"
    ;;

  list)
    echo "[i] 当前 collab.message 分区拓扑与状态："
    run_psql_file "$SQL_FILE" >/dev/null 2>&1 || true
    run_psql "SELECT partition_name, partition_bound, total_size, live_tuples FROM collab.v_message_partition_stats;"
    ;;

  archive)
    PART_NAME="${2:-}"
    OUT_DIR="${3:-$MONO_DIR/backups/partitions}"
    if [ -z "$PART_NAME" ]; then
      echo "[x] 错误：必须指定要归档的分区名，例如: message_2026m09" >&2
      exit 1
    fi
    CLEAN_NAME="${PART_NAME#collab.}"
    mkdir -p "$OUT_DIR"
    STAMP="$(date +%Y%m%d-%H%M%S)"
    TARGET_ARCHIVE="$OUT_DIR/${CLEAN_NAME}_${STAMP}.sql.gz"

    echo "[1/3] 正在从父表解耦分区 (DETACH PARTITION): collab.$CLEAN_NAME ..."
    run_psql "SELECT collab.detach_message_partition('$CLEAN_NAME');"

    echo "[2/3] 正在导出解耦后的数据并压缩: -> $TARGET_ARCHIVE ..."
    run_pg_dump_table "$CLEAN_NAME" "$TARGET_ARCHIVE"

    if [ ! -s "$TARGET_ARCHIVE" ] || ! gzip -t "$TARGET_ARCHIVE"; then
      echo "[x] 导出归档失败或文件损坏: $TARGET_ARCHIVE" >&2
      exit 1
    fi
    ARCHIVE_SIZE="$(du -h "$TARGET_ARCHIVE" | cut -f1)"
    echo "[ok] 分区归档成功: $TARGET_ARCHIVE (大小: $ARCHIVE_SIZE)"

    echo "[3/3] 建议：验证备份完整后，可在数据库中安全执行清理: DROP TABLE collab.$CLEAN_NAME;"
    ;;

  *)
    echo "用法: $0 {init|ensure [months]|list|archive <partition_name> [output_dir]}"
    exit 1
    ;;
esac
