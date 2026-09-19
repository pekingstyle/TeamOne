#!/usr/bin/env bash
# =============================================================================
# TeamOne Spring Boot 3.5 + JDK 17 CDS 预热训练与归档生成工具（Spike S-5）
#
# 原理（JEP 310 / Application Class Data Sharing）：
#   1. 使用 Spring Boot 3.3+ 官方推荐的 extract 结构解压 fat-jar；
#   2. 执行带 -XX:ArchiveClassesAtExit=application.jsa 的训练跑；
#   3. 通过 -Dspring.context.exit=onRefresh 使 Spring Boot 在上下文装配完成时干净退出；
#   4. 生成持久化 application.jsa 共享归档文件（约 12MB+），实现类元数据零解析秒级 mmap 载入。
#
# 用法：
#   bash deploy/cds-train.sh [jar_path] [output_dir]
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_FILE="${1:-$MONO_DIR/server/teamone-app/target/teamone-app.jar}"
OUT_DIR="${2:-$MONO_DIR/deploy/cds}"

if [ ! -f "$JAR_FILE" ]; then
  echo "[!] 未找到目标 jar 包: $JAR_FILE"
  echo "[i] 正在执行 Maven 构建打包..."
  (cd "$MONO_DIR/server" && mvn -DskipTests package)
fi

echo "================================================================="
echo "       TeamOne Spike S-5 CDS（类数据共享）预热训练构建工具      "
echo "================================================================="

mkdir -p "$OUT_DIR"
EXTRACT_DIR="$OUT_DIR/extracted"
rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"

echo "[步骤 1/3] 解压 Fat-JAR 结构至 CDS 运行目录: $EXTRACT_DIR ..."
java -Djarmode=tools -jar "$JAR_FILE" extract --destination "$EXTRACT_DIR"

echo "[步骤 2/3] 执行 Spring Boot 上下文预热训练并导出类元数据归档 (application.jsa)..."
cd "$EXTRACT_DIR"

# 临时训练配置（使用随机可用端口避免 8080 冲突，开启 onRefresh 快速退出）
export SERVER_PORT="0"
export TEAMONE_DB_URL="${TEAMONE_DB_URL:-jdbc:postgresql://127.0.0.1:5432/teamone}"
export TEAMONE_DB_USER="${TEAMONE_DB_USER:-postgres}"
export TEAMONE_DB_PASSWORD="${TEAMONE_DB_PASSWORD:-teamone-local}"
export TEAMONE_REFRESH_STORE="jpa"

# 连通性预检
HOST_PORT="${TEAMONE_DB_URL#jdbc:postgresql://}"
DBHOST="${HOST_PORT%%/*}"; DBPORT="${DBHOST##*:}"; DBHOST="${DBHOST%%:*}"
if ! timeout 2 bash -c "</dev/tcp/$DBHOST/$DBPORT" 2>/dev/null; then
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qw "teamone-postgres"; then
    export TEAMONE_DB_URL="jdbc:postgresql://127.0.0.1:5432/teamone"
    export TEAMONE_DB_PASSWORD="teamone-local"
  fi
fi

# 启动训练跑并在后台监控完成
rm -f application.jsa train.log
java -XX:ArchiveClassesAtExit=application.jsa \
     -Dspring.context.exit=onRefresh \
     -Dteamone.refresh-store=jpa \
     -jar teamone-app.jar > train.log 2>&1 &
TRAIN_PID=$!

echo "[i] 训练进程 PID: $TRAIN_PID，正在捕获类加载元数据..."
for i in $(seq 1 60); do
  if [ -s application.jsa ] && [ ! -d "/proc/$TRAIN_PID" ]; then
    break
  fi
  # 若日志已显示装配完成，给进程 3 秒优雅退出写盘时间
  if grep -q "Started TeamOneApplication\|Root WebApplicationContext: initialization completed" train.log 2>/dev/null; then
    sleep 3
    kill -15 "$TRAIN_PID" 2>/dev/null || true
    wait "$TRAIN_PID" 2>/dev/null || true
    break
  fi
  sleep 1
done

echo "[步骤 3/3] 校验生成的 CDS 归档产物..."
if [ ! -s application.jsa ]; then
  echo "[x] 错误：application.jsa 生成失败！排查日志如下：" >&2
  tail -n 30 train.log >&2
  exit 1
fi

JSA_SIZE="$(du -h application.jsa | cut -f1)"
echo "[ok] CDS 类元数据归档构建成功: $EXTRACT_DIR/application.jsa (大小: $JSA_SIZE)"
echo "================================================================="
echo "[SUCCESS] Spike S-5 预热归档准备就绪！可通过 bash deploy/cds-run.sh 极速启动。"
echo "================================================================="
