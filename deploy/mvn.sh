#!/usr/bin/env bash
# Git Bash 下直调 Maven（本机 mvn 包装脚本损坏的绕行方案）
#
# 症状: Git Bash 里执行 mvn 报 "找不到或无法加载主类
#       org.codehaus.plexus.classworlds.launcher.Launcher"。
# 原因: C:/UserProgram/Maven/bin/mvn 的 sh 脚本在 Git Bash 下解析 MAVEN_HOME 失败，
#       导致 -classpath 里的 boot jar 为空。本脚本绕过包装脚本，
#       直接以 classworlds Launcher 启动 Maven（与 mvn 脚本最终行为一致）。
#
# 用法: bash deploy/mvn.sh <maven 参数...>
#   例: bash deploy/mvn.sh -f server/pom.xml -B -ntp package
# 注意: maven.home 为本机安装路径（可用 TEAMONE_MVN_HOME 覆盖），仅供本地开发；
#       CI（Gitea Actions）内直接使用镜像自带 mvn，不经过本脚本。
#                                                          —— Ivan Yang, 2026-09-11
set -euo pipefail

MVN_HOME="${TEAMONE_MVN_HOME:-C:/UserProgram/Maven}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../server" && pwd -W 2>/dev/null || echo "$SCRIPT_DIR/server")"
BOOT_JAR="$(ls "$MVN_HOME"/boot/plexus-classworlds-*.jar | head -1)"

exec java -Dmaven.multiModuleProjectDirectory="$SERVER_DIR" \
  -Dmaven.home="$MVN_HOME" \
  -Dclassworlds.conf="$MVN_HOME/bin/m2.conf" \
  -classpath "$BOOT_JAR" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
