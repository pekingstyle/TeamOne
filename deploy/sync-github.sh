#!/usr/bin/env bash
# monorepo → GitHub 过滤同步（pekingstyle/TeamOne）
#
# 同步范围: web/ + server/ + deploy/ + README.github.md/README.github.en.md
#           镜像侧落为: web/ server/ deploy/ README.md README_EN.md（与 monorepo 同构）
# 永不同步: docs/（设计文档）、.workbuddy/、backups/、deploy/.env（及一切密钥）
# 镜像清理: 删除原型时代摊在仓库根目录的旧前端文件（已归入 web/），仅首次迁移时实际生效
# 基线策略: 以 GitHub main 为基线（先 clone 再叠加），保留远端历史，只做前进提交
#
# 认证与署名（两个不同概念，见 §说明）:
#   · 认证 = 推送凭据 → Windows 凭据管理器（wincred helper）。脚本显式指定 helper 绝对路径，
#     规避受限 shell 下 git/helper 解析不确定的问题。
#   · 署名 = commit author（user.name/user.email）→ 必须来自 git config；
#     受限 shell 里 HOME 异常会读不到全局配置，故显式 -c 传入（优先沿用 monorepo 配置）。
#
# 用法: bash deploy/sync-github.sh
#                                                      —— Ivan Yang, 2026-09-11
set -euo pipefail

MONO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GH_URL="${TEAMONE_GITHUB_URL:-https://github.com/pekingstyle/TeamOne.git}"
# 显式指定可执行体，避免 shell 环境差异导致解析到不带 helper 的 git
GIT="${TEAMONE_GIT:-C:/Program Files/Git/cmd/git.exe}"
[ -x "$GIT" ] || GIT="git"
HELPER="${TEAMONE_CRED_HELPER:-C:/Program Files/Git/mingw64/libexec/git-core/git-credential-wincred.exe}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[i] git=$GIT helper=$HELPER"
echo "[i] 基线: $GH_URL"
"$GIT" -c credential.helper="$HELPER" clone -q "$GH_URL" "$TMP"

echo "[i] 提取 monorepo 内容: web/ server/ deploy/ .gitattributes + GitHub 版 README（monorepo $("$GIT" -C "$MONO" rev-parse --short HEAD)）"
# .gitattributes 必须同步：否则镜像侧 checkout 按全局 autocrlf 转 CRLF，
# 与 monorepo 的 LF（*.sh eol=lf）不一致 → 每次同步都会产生假变更（幂等性破坏）
"$GIT" -C "$MONO" archive HEAD web server deploy .gitattributes README.github.md README.github.en.md | tar -xf - -C "$TMP"
mv "$TMP/README.github.md" "$TMP/README.md"
mv "$TMP/README.github.en.md" "$TMP/README_EN.md"

cd "$TMP"
# 旧布局/旧产物清理（幂等：不存在则忽略）
#   ① 原型时代摊在根目录的前端文件（已归入 web/）
#   ② Gitea 时代产物（D-2026-09-11-B 起废弃）
for p in src public docs index.html package.json package-lock.json \
         tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts .oxlintrc.json \
         .gitea deploy/w1-10-bootstrap.sh deploy/w1-10-runner.sh deploy/runner-config.yaml; do
  "$GIT" rm -rq --ignore-unmatch "$p" 2>/dev/null || true
done

# 追加后端/部署忽略项（保留 web/.gitignore 自身规则）
grep -q 'server/\*\*/target/' .gitignore 2>/dev/null || cat >> .gitignore <<'EOF'

# ---- backend / deploy（sync-github 维护）----
server/**/target/
logs/
deploy/.env
.env
!.env.example
.workbuddy/
backups/
EOF

if [ -z "$("$GIT" status --porcelain)" ]; then
  echo "[ok] GitHub 已是最新，无变更"
  exit 0
fi

"$GIT" add -A
# 署名（commit author）：用 GitHub 惯用身份作为默认，可用环境变量覆盖。
# 注意：不要读本机全局 gitconfig——该机器全局身份是 Gitee 别名（Admin/…gitee.com），
#       曾导致 GitHub 提交署名错乱（e459ef9）；也不要兜底成占位名（会造成署名再变）。
GIT_NAME="${TEAMONE_GIT_NAME:-afire.yang}"
GIT_EMAIL="${TEAMONE_GIT_EMAIL:-afire521@hotmail.com}"
echo "[i] 署名: $GIT_NAME <$GIT_EMAIL>"
"$GIT" -c user.name="$GIT_NAME" -c user.email="$GIT_EMAIL" \
  commit -q -m "sync: 全栈代码同步（web/ + server/ + deploy/ + README）——设计文档不入库（monorepo $("$GIT" -C "$MONO" rev-parse --short HEAD)）"
"$GIT" -c credential.helper="$HELPER" push "$GH_URL" main
echo "[ok] 已推送。远端校验:"
"$GIT" ls-remote "$GH_URL" refs/heads/main
