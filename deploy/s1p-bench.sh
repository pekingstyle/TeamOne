#!/usr/bin/env bash
# S-1' 预评估脚本（07 决策 §3 R2 / §1 §10：git 命令在大仓库/blame 场景的响应上限评估）
#
# 场景：合成 5 万提交 / 2 千文件的线性历史（git fast-import 流），对自研内核（GitPort）
#       M1 期最重的三个读命令计时——git log -100 / git blame -l <hot> / git diff --stat HEAD~50..HEAD，
#       各跑 3 次取中位（date +%s%N 纳秒计时），输出矩阵与阈值判定（log<500ms、blame<2s、diff<3s）。
#
# 用法（Windows 宿主）: MSYS_NO_PATHCONV=1 wsl -d Ubuntu-24.04 -u root bash /mnt/c/.../deploy/s1p-bench.sh
# 环境: WSL 原生 /tmp（ext4）建临时仓库，避免 /mnt/c（9p）拖累计时；结束自动清理。
# 规模可用环境变量覆盖: COMMITS=50000 FILES=2000
#                                                      —— 全栈开发工程师④a, 2026-09-12
set -euo pipefail

COMMITS="${COMMITS:-50000}"
FILES="${FILES:-2000}"

command -v git >/dev/null 2>&1 || { echo "[x] git 不可用（需在 WSL 内运行）" >&2; exit 1; }
command -v awk >/dev/null 2>&1 || { echo "[x] awk 不可用" >&2; exit 1; }

WORK="$(mktemp -d /tmp/s1p-bench.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
REPO="$WORK/bench"

echo "[i] S-1' 预评估：commits=$COMMITS files=$FILES workdir=$WORK"
echo "[i] git $(git --version | cut -d' ' -f3)"

git init -q -b main "$REPO"
git -C "$REPO" config user.name  "s1p-bench"
git -C "$REPO" config user.email "bench@teamone.local"

# ---------- 1) 生成 fast-import 流（awk 单趟，ASCII 内容；每提交轮换一个文件 + 固定热点文件） ----------
STREAM="$WORK/stream.dump"
echo "[i] 生成 fast-import 流..."
awk -v N="$COMMITS" -v F="$FILES" 'BEGIN{
  ts = 1760000000;
  # 提交 1：初始 F 个文件
  print "commit refs/heads/main";
  print "mark :1";
  print "author s1p-bench <bench@teamone.local> " ts " +0000";
  print "committer s1p-bench <bench@teamone.local> " ts " +0000";
  msg = "initial: create " F " files";
  print "data " length(msg); print msg;
  for (f = 1; f <= F; f++) {
    p = "src/file_" sprintf("%04d", f) ".txt";
    c = "static header line\nfile " f " v1\n";
    printf "M 100644 inline %s\ndata %d\n%s\n", p, length(c), c;
  }
  # 提交 2..N：轮换文件 + 热点文件（内容整体替换，迫使 blame 走完整历史）
  for (i = 2; i <= N; i++) {
    ts++;
    print "commit refs/heads/main";
    print "mark :" i;
    print "author s1p-bench <bench@teamone.local> " ts " +0000";
    print "committer s1p-bench <bench@teamone.local> " ts " +0000";
    msg = "commit " i ": rotate files";
    print "data " length(msg); print msg;
    f = (i % F) + 1;
    p = "src/file_" sprintf("%04d", f) ".txt";
    c = "static header line\nfile " f " rev " i "\n";
    printf "M 100644 inline %s\ndata %d\n%s\n", p, length(c), c;
    c2 = "teamone s1p benchmark hot file\nrev " i "\n";
    printf "M 100644 inline src/_hot.txt\ndata %d\n%s\n", length(c2), c2;
  }
}' > "$STREAM"
echo "[i] 流大小: $(du -h "$STREAM" | cut -f1)"

# ---------- 2) 导入（几分钟内） ----------
echo "[i] git fast-import 导入中..."
git -C "$REPO" fast-import --quiet < "$STREAM"
git -C "$REPO" reset -q --hard   # 物化工作区（blame 需要）
rm -f "$STREAM"

NC="$(git -C "$REPO" rev-list --count HEAD)"
NF="$(git -C "$REPO" ls-tree -r --name-only HEAD | wc -l)"
echo "[i] 导入完成: commits=$NC files=$NF"
[ "$NC" -eq "$COMMITS" ] || { echo "[x] 提交数不符: $NC != $COMMITS" >&2; exit 1; }

# ---------- 3) 计时（各 3 次取中位，date +%s%N） ----------
runs() { # $@=command；打印 "r1 r2 r3"
  local r t
  for r in 1 2 3; do
    s=$(date +%s%N); "$@" >/dev/null 2>&1; e=$(date +%s%N)
    t=$(( (e - s) / 1000000 ))
    if [ "$r" -eq 1 ]; then printf '%s' "$t"; else printf ' %s' "$t"; fi
  done
  echo
}
judge() { # $1=实测ms $2=阈值ms
  if [ "$1" -lt "$2" ]; then echo "PASS"; else echo "FAIL"; fi
}

cd "$REPO"
echo
echo "================ S-1' 预评估矩阵（$NC commits / $NF files） ================"
printf '%-34s %8s %8s %8s %9s %9s %7s\n' "命令" "run1" "run2" "run3" "中位(ms)" "阈值(ms)" "判定"
row() { # $1=显示名 $2=阈值 $3...=命令
  local name="$1" limit="$2"; shift 2
  local rr med v
  rr="$(runs "$@")"
  med="$(printf '%s\n' $rr | sort -n | sed -n '2p')"
  v="$(judge "$med" "$limit")"
  printf '%-34s %8s %8s %8s %9s %9s %7s\n' "$name" $rr "$med" "$limit" "$v"
  LAST_ROW="$name|$rr|$med|$limit|$v"
}
row "git log -100"                500  git log -100
LOG_ROW="$LAST_ROW"
row "git blame -l src/_hot.txt"  2000  git blame -l src/_hot.txt
BLAME_ROW="$LAST_ROW"
row "git diff --stat HEAD~50..HEAD" 3000  git diff --stat HEAD~50..HEAD
DIFF_ROW="$LAST_ROW"
echo "=============================================================================="

echo
echo "[i] 结论行（供 07 §3 R2 回填）："
echo "$LOG_ROW"   | awk -F'|' '{printf "  log=%s ms (runs %s, 阈值 %s, %s)\n", $3, $2, $4, $5}'
echo "$BLAME_ROW" | awk -F'|' '{printf "  blame=%s ms (runs %s, 阈值 %s, %s)\n", $3, $2, $4, $5}'
echo "$DIFF_ROW"  | awk -F'|' '{printf "  diff=%s ms (runs %s, 阈值 %s, %s)\n", $3, $2, $4, $5}'

echo
echo "[i] 清理临时目录: $WORK"
