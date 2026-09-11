#!/usr/bin/env bash
# 自研 Git 内核引导（D-2026-09-11-B）：建 bare repo + 安装 post-receive hook + 切换本仓库 origin
#
# 用法: bash deploy/git-init-repo.sh [repo-key]      # 默认 teamone/teamone
# 依赖: deploy/.env 中的 TEAMONE_GIT_ROOT、TEAMONE_HOOK_TOKEN
# 说明: 仓库存于 {TEAMONE_GIT_ROOT}/{repo-key}.git（bare，无工作区）；
#       push 后由 post-receive hook 回调 TeamOne（端点 /api/v1/git/hooks/post-receive 属 M1-W2）。
#       hook 文件内含共享口令（本机文件，等同 .env 的密级；勿纳入备份以外的任何分发）。
#                                                      —— Ivan Yang, 2026-09-11
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
# shellcheck disable=SC1090
source "$ROOT/deploy/.env"
set +a

GIT_ROOT="${TEAMONE_GIT_ROOT:?deploy/.env 缺少 TEAMONE_GIT_ROOT}"
HOOK_TOKEN="${TEAMONE_HOOK_TOKEN:?deploy/.env 缺少 TEAMONE_HOOK_TOKEN（post-receive 回调口令）}"
REPO_KEY="${1:-teamone/teamone}"
BARE="$GIT_ROOT/$REPO_KEY.git"

echo "[i] bare repo: $BARE"
mkdir -p "$(dirname "$BARE")"
if [ ! -d "$BARE" ]; then
  git init --bare -b main "$BARE" >/dev/null
  echo "[ok] 已创建"
else
  echo "[ok] 已存在，跳过创建"
fi

cat > "$BARE/hooks/post-receive" <<HOOK
#!/usr/bin/env bash
# TeamOne post-receive hook（由 deploy/git-init-repo.sh 安装，勿手工编辑）
export TEAMONE_HOOK_TOKEN='$HOOK_TOKEN'
export TEAMONE_REPO_KEY='$REPO_KEY'
export TEAMONE_HOOK_URL='${TEAMONE_HOOK_URL:-http://localhost:8080/api/v1/git/hooks/post-receive}'
while read -r oldrev newrev refname; do
  [ "\$refname" = "refs/heads/main" ] || continue
  curl -s -o /dev/null --max-time 5 -X POST "\$TEAMONE_HOOK_URL" \\
    -H "Content-Type: application/json" \\
    -H "X-TeamOne-Hook-Token: \$TEAMONE_HOOK_TOKEN" \\
    -d "{\\"repo\\":\\"\$TEAMONE_REPO_KEY\\",\\"oldRev\\":\\"\$oldrev\\",\\"newRev\\":\\"\$newrev\\",\\"ref\\":\\"\$refname\\"}" \\
    || echo "[hook] TeamOne 回调失败（不阻塞 push；M2 起有定时对账兜底）" >&2
done
HOOK
chmod +x "$BARE/hooks/post-receive"

# 守卫（2026-09-11 教训）：受限/异常 shell 可能损坏含 ':' 的路径，导致裸库被建到错误位置
# （实测曾在本仓库根下生成伪目录并混入提交）。这里强校验，失败即中止并提示。
if [ ! -d "$BARE/refs" ] || [ ! -f "$BARE/HEAD" ]; then
  echo "[x] bare repo 未在预期位置创建：$BARE" >&2
  echo "    如经受限 shell（路径转义异常）执行，请改用原生终端/PowerShell 重跑本脚本。" >&2
  exit 1
fi
STRAY="$(cd "$ROOT" && ls -d C* 2>/dev/null || true)"
if [ -n "$STRAY" ]; then
  echo "[x] 检测到仓库根下的可疑伪目录：$STRAY（路径损坏产物，请删除后重试）" >&2
  exit 1
fi
echo "[ok] post-receive hook 已安装（守卫校验通过）"

cd "$ROOT"
git remote remove gitea >/dev/null 2>&1 || true
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$BARE"
else
  git remote add origin "$BARE"
fi
echo "[ok] origin 已指向自研 Git 内核："
git remote -v

echo
echo "[i] 开发者推送到： $BARE"
echo "    或（跨机/显式协议）: file://$BARE"
