#!/usr/bin/env bash
# =============================================================================
# TeamOne Spring Boot 3.5 + JDK 17 CDS 极速启动脚本（Spike S-5）
#
# 功能：
#   基于 deploy/cds/extracted/application.jsa 预编译归档，
#   通过 JVM -XX:SharedArchiveFile=application.jsa -Xshare:auto 参数启动服务，
#   实现类元数据零解析、秒级冷启动。
#
# 用法：
#   bash deploy/cds-run.sh [端口号]
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CDS_DIR="$SCRIPT_DIR/cds/extracted"
ENV_FILE="${TEAMONE_ENV_FILE:-$SCRIPT_DIR/.env}"

if [ ! -f "$CDS_DIR/application.jsa" ]; then
  echo "[!] 未检测到 CDS 预热归档 ($CDS_DIR/application.jsa)，正在自动执行预热训练..."
  bash "$SCRIPT_DIR/cds-train.sh"
fi

if [ -f "$ENV_FILE" ]; then
  set -a; source "$ENV_FILE"; set +a
fi

PORT="${1:-${SERVER_PORT:-8080}}"
export SERVER_PORT="$PORT"
export TEAMONE_DB_URL="${TEAMONE_DB_URL:-jdbc:postgresql://127.0.0.1:5432/teamone}"
export TEAMONE_DB_USER="${TEAMONE_DB_USER:-postgres}"
export TEAMONE_DB_PASSWORD="${TEAMONE_DB_PASSWORD:-teamone-local}"

echo "[i] 正在以 CDS（类数据共享）加速模式启动 TeamOne 服务端..."
echo "[i] 共享类归档文件: $CDS_DIR/application.jsa"
echo "[i] 监听端口: $SERVER_PORT"

cd "$CDS_DIR"
exec java -XX:SharedArchiveFile=application.jsa \
          -Xshare:auto \
          -jar teamone-app.jar "$@"
