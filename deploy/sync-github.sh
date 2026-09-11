#!/usr/bin/env bash
# monorepo → GitHub 过滤同步（pekingstyle/TeamOne）
#
# 同步范围: web/ + server/ + deploy/ + README.github.md/README.github.en.md
#           镜像侧落为: web/ server/ deploy/ README.md README_EN.md（与 monorepo 同构）
# 永不同步: docs/（设计文档）、.workbuddy/、backups/、deploy/.env（及一切密钥）
# 镜像清理: 删除原型时代摊在仓库根目录的旧前端文件（已归入 web/），仅首次迁移时实际生效
# 基线策略: 以 GitHub main 为基线（先 clone 再叠加），保留远端历史，只做前进提交
# 认证: git -c credential.helper=wincred（本机 credential.helper 多值冲突的绕行）
#
# 用法: bash deploy/sync-github.sh
#                                                      —— Ivan Yang, 2026-09-11
set -euo pipefail

MONO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GH_URL="${TEAMONE_GITHUB_URL:-https://github.com/pekingstyle/TeamOne.git}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[i] 基线: $GH_URL"
git -c credential.helper=wincred clone -q "$GH_URL" "$TMP"

echo "[i] 提取 monorepo 内容: web/ server/ deploy/ + GitHub 版 README（monorepo $(git -C "$MONO" rev-parse --short HEAD)）"
git -C "$MONO" archive HEAD web server deploy README.github.md README.github.en.md | tar -xf - -C "$TMP"
mv "$TMP/README.github.md" "$TMP/README.md"
mv "$TMP/README.github.en.md" "$TMP/README_EN.md"

cd "$TMP"
# 旧布局清理：原型时代的前端文件曾在仓库根目录（现已归入 web/）
for p in src public docs index.html package.json package-lock.json \
         tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts .oxlintrc.json; do
  git rm -rq --ignore-unmatch "$p" 2>/dev/null || true
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

if [ -z "$(git status --porcelain)" ]; then
  echo "[ok] GitHub 已是最新，无变更"
  exit 0
fi

git add -A
git commit -q -m "sync: 全栈代码同步（web/ + server/ + deploy/ + README）——设计文档不入库（monorepo $(git -C "$MONO" rev-parse --short HEAD)）"
git -c credential.helper=wincred push "$GH_URL" main
echo "[ok] 已推送。远端校验:"
git ls-remote "$GH_URL" refs/heads/main
