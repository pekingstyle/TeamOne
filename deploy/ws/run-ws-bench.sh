#!/usr/bin/env bash
# =============================================================================
# TeamOne Spike S-4 WebSocket 高并发连接密度自动化压测引导脚本
#
# 功能：
#   1. 自动登录获取真实 JWT Token；
#   2. 编译 deploy/ws/WsBenchmark.java；
#   3. 执行指定规模的高并发长连接压测并输出基准测试报告。
#
# 用法：
#   bash deploy/ws/run-ws-bench.sh [目标连接数] [批次步长] [保活时长秒数]
# 示例：
#   bash deploy/ws/run-ws-bench.sh 500 50 10
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
TARGET_CONNS="${1:-500}"
BATCH_SIZE="${2:-50}"
HOLD_SECS="${3:-10}"
SERVER_BASE="${TEAMONE_API_BASE:-http://127.0.0.1:8080}"
WS_URL="${TEAMONE_WS_URL:-ws://127.0.0.1:8080/ws}"

echo "================================================================="
echo "        TeamOne Spike S-4 WebSocket 高并发基准测试自动化运行器   "
echo "================================================================="

# 1. 登录获取 JWT Access Token
echo "[步骤 1/3] 向 $SERVER_BASE 请求获取 JWT 访问令牌..."
LOGIN_RES="$(curl -s -X POST "$SERVER_BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@123"}')"

TOKEN="$(echo "$LOGIN_RES" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4 || true)"

if [ -z "$TOKEN" ]; then
  echo "[x] 错误：获取登录 Token 失败！响应体：" >&2
  echo "$LOGIN_RES" >&2
  exit 1
fi
echo "[ok] 成功获取有效认证 Token (前缀: ${TOKEN:0:20}...)"

# 2. 编译压测客户端
echo "[步骤 2/3] 检查并编译 WsBenchmark.java 压测客户端..."
BIN_DIR="$MONO_DIR/tmp/ws-bench-bin"
mkdir -p "$BIN_DIR"
if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 -d "$BIN_DIR" "$SCRIPT_DIR/WsBenchmark.java"
  echo "[ok] javac 编译完成。"
elif [ -f "$BIN_DIR/deploy/ws/WsBenchmark.class" ]; then
  echo "[ok] 使用已编译的 WsBenchmark.class。"
else
  docker run --rm -v "$MONO_DIR:/app" -w /app maven:3.9-eclipse-temurin-17 \
    javac -encoding UTF-8 -d "tmp/ws-bench-bin" "deploy/ws/WsBenchmark.java"
  echo "[ok] 容器内 javac 编译完成。"
fi

# 3. 运行压测
echo "[步骤 3/3] 启动高并发 WebSocket 压测 (目标: $TARGET_CONNS 长连接)..."
java -cp "$BIN_DIR" deploy.ws.WsBenchmark "$WS_URL" "$TOKEN" "$TARGET_CONNS" "$BATCH_SIZE" "$HOLD_SECS"
