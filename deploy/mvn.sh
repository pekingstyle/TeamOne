#!/usr/bin/env bash
# Git Bash/受限 shell 下直调 Maven（本机 mvn 包装脚本损坏的绕行方案）
#
# 背景: 本机存在两种 bash 血统（Git Bash 与 WSL 风格路径视角）与两种 java
#       （Windows GraalVM / Linux），路径风格需按 java 实际血统自适应，
#       否则报 "找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher"。
#
# 用法: bash deploy/mvn.sh <maven 参数...>
#   例: bash deploy/mvn.sh -f server/pom.xml -B -ntp package
# 覆盖: TEAMONE_MVN_HOME（Maven 安装路径）、TEAMONE_JAVA（java 可执行文件）
# 注: 常规机器/CI 直接使用 mvn 即可，无需本脚本。
#                                                      —— Ivan Yang, 2026-09-11
set -euo pipefail

# --- 选取 JDK：显式指定 > 本机 Windows GraalVM（glob）> PATH 中的 java ---
# 背景: 受限 shell 里 PATH 的 java 可能是 WSL 旧 JDK（不支持 release 17）。
JAVACMD=""
if [ -n "${TEAMONE_JAVA:-}" ] && [ -x "${TEAMONE_JAVA}" ]; then
  JAVACMD="${TEAMONE_JAVA}"
else
  shopt -s nullglob
  for jdk in C:/UserProgram/JDK/*/bin/java.exe /mnt/c/UserProgram/JDK/*/bin/java.exe; do
    if [ -x "$jdk" ]; then JAVACMD="$jdk"; break; fi
  done
fi
[ -z "$JAVACMD" ] && JAVACMD="$(command -v java || echo java)"

# --- 判定 java 血统（决定路径风格）---
JVM_WIN=1
case "$JAVACMD" in
  *.exe) JVM_WIN=1 ;;
  /mnt/*|/c/*|/usr/*|/opt/*|/bin/*) JVM_WIN=0 ;;
esac

# shell 视角 → java 视角
to_java_path() {
  if [ "$JVM_WIN" = 1 ]; then
    case "$1" in
      /mnt/c/*) printf 'C:%s' "${1#/mnt/c}" ;;
      /c/*)     printf 'C:%s' "${1#/c}" ;;
      *)        printf '%s' "$1" ;;
    esac
  else
    case "$1" in
      C:/*) printf '/mnt/c%s' "${1#C:}" ;;
      *)    printf '%s' "$1" ;;
    esac
  fi
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../server" && pwd)"

# --- 定位 Maven 安装（兼容两种路径视角）---
MVN_HOME=""
for cand in "${TEAMONE_MVN_HOME:-C:/UserProgram/Maven}" "/mnt/c/UserProgram/Maven" "/c/UserProgram/Maven"; do
  if [ -d "$cand/boot" ]; then MVN_HOME="$cand"; break; fi
done
if [ -z "$MVN_HOME" ]; then
  echo "[x] 未找到 Maven 安装目录（可用 TEAMONE_MVN_HOME 指定）" >&2
  exit 1
fi

# --- 纯 bash 通配定位 boot jar（不依赖外部命令）---
shopt -s nullglob
_jars=("$MVN_HOME"/boot/plexus-classworlds-*.jar)
if [ ${#_jars[@]} -eq 0 ]; then
  echo "[x] 未找到 classworlds boot jar：$MVN_HOME/boot/" >&2
  exit 1
fi
BOOT_JAR="${_jars[0]}"

exec "$JAVACMD" \
  -Dmaven.multiModuleProjectDirectory="$(to_java_path "$SERVER_DIR")" \
  -Dmaven.home="$(to_java_path "$MVN_HOME")" \
  -Dclassworlds.conf="$(to_java_path "$MVN_HOME")/bin/m2.conf" \
  -classpath "$(to_java_path "$BOOT_JAR")" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
