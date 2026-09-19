#!/usr/bin/env bash
# TeamOne 自研 CI（D-2026-09-11-B：不复用任何外部 CI 服务）
#
# 用法:
#   bash deploy/ci.sh            # 单测 + ArchUnit（无需 Docker，提交前硬门）
#   bash deploy/ci.sh --full     # 追加集成测试（Testcontainers，需 Docker）
#
# 产出: .ci/<时间戳>/verify.log（M2 起由 eng 域流水线读入库、喂 MR 门禁与 CI 视图）
# 说明: 本机 mvn 包装脚本损坏 → 默认走 deploy/mvn.sh；其他机器可用 TEAMONE_MVN=mvn 覆盖。
#                                                      —— Ivan Yang, 2026-09-11
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/.ci/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOG_DIR"

MVN="${TEAMONE_MVN:-bash $ROOT/deploy/mvn.sh}"
cd "$ROOT/server"          # 以相对路径调用，规避 shell 视角与 java 视角的路径差异
# repackage 跳过：质量门只做编译+测试；可执行 jar 由 dev.sh build 产出。
# （另：后端进程运行时 jar 被 Windows 锁定，repackage 必然失败）
ARGS="-B -ntp -Dspring-boot.repackage.skip=true verify"
if [ "${1:-}" = "--full" ]; then
  ARGS="$ARGS -DskipITs=false"
  echo "[i] 模式: full（含 Testcontainers 集成测试，需 Docker）"
else
  echo "[i] 模式: 基础（单测 + ArchUnit；全量请加 --full）"
fi

set +e
# shellcheck disable=SC2086
$MVN $ARGS 2>&1 | tee "$LOG_DIR/verify.log"
CODE="${PIPESTATUS[0]}"
set -e

if [ "$CODE" -ne 0 ]; then
  echo "[x] CI 未通过（exit=$CODE）—— 日志: $LOG_DIR/verify.log"
  exit "$CODE"
fi
echo "[ok] CI 通过 —— 日志: $LOG_DIR/verify.log"
