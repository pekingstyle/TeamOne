#!/usr/bin/env bash
# TeamOne 私有化纯离线交付包打包脚本（V-18）
#
# 用法（发版/构建机上执行）：
#   bash deploy/pack-offline.sh [版本号，缺省 v1.0.0]
# 产物：
#   release/teamone-offline-{version}.tar.gz
#   release/teamone-offline-{version}.tar.gz.sha256
#                                                        —— Ivan Yang, 2026-09-13
set -euo pipefail

VERSION="${1:-v1.0.0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$MONO_DIR/release"
STAGING_DIR="$RELEASE_DIR/teamone-offline-$VERSION"

echo "=========================================================="
echo " 开始制作 TeamOne 离线交付包: $VERSION"
echo "=========================================================="

mkdir -p "$RELEASE_DIR"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"

# 1. 声明并检查运行镜像清单
IMAGES=(
  "postgres:18-alpine"
  "valkey/valkey:8-alpine"
  "minio/minio:latest"
  "nginx:1.27-alpine"
  "prom/prometheus:latest"
)

echo "[1/4] 验证基础 Docker 镜像..."
for img in "${IMAGES[@]}"; do
  if ! docker image inspect "$img" >/dev/null 2>&1; then
    echo "[i] 镜像 $img 本地未发现，正在尝试拉取..."
    docker pull "$img"
  else
    echo "[ok] 镜像就绪: $img"
  fi
done

# 若本地已打好 teamone-server:latest 镜像，也一并加入离线列表
if docker image inspect teamone-server:latest >/dev/null 2>&1; then
  IMAGES+=("teamone-server:latest")
  echo "[ok] 发现本地核心服务端镜像: teamone-server:latest"
fi

# 2. 导出所有 Docker 镜像为单个压缩包
echo "[2/4] 正在导出全量离线 Docker 镜像 (docker save | gzip)..."
docker save "${IMAGES[@]}" | gzip > "$STAGING_DIR/images.tar.gz"
echo "[ok] 镜像导出完成: $(du -h "$STAGING_DIR/images.tar.gz" | cut -f1)"

# 3. 复制部署配置文件与引导脚本
echo "[3/4] 打包部署配置与一键安装脚本..."
cp "$SCRIPT_DIR/docker-compose.prod.yml" "$STAGING_DIR/docker-compose.yml"
cp "$SCRIPT_DIR/.env.example" "$STAGING_DIR/.env.example"
cp "$SCRIPT_DIR/install.sh" "$STAGING_DIR/install.sh"
cp "$SCRIPT_DIR/backup-db.sh" "$STAGING_DIR/backup-db.sh"
cp "$SCRIPT_DIR/restore-db.sh" "$STAGING_DIR/restore-db.sh"
chmod +x "$STAGING_DIR"/*.sh

mkdir -p "$STAGING_DIR/nginx"
cp -r "$SCRIPT_DIR/nginx"/* "$STAGING_DIR/nginx/" 2>/dev/null || true

mkdir -p "$STAGING_DIR/prometheus"
cp -r "$SCRIPT_DIR/prometheus"/* "$STAGING_DIR/prometheus/" 2>/dev/null || true

# 4. 生成最终离线交付压缩包
echo "[4/4] 压缩生成最终发布包..."
TAR_FILE="$RELEASE_DIR/teamone-offline-$VERSION.tar.gz"
tar -czf "$TAR_FILE" -C "$RELEASE_DIR" "teamone-offline-$VERSION"
rm -rf "$STAGING_DIR"

# 生成 SHA256 校验和
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$RELEASE_DIR" && sha256sum "$(basename "$TAR_FILE")" > "$(basename "$TAR_FILE").sha256")
fi

echo "=========================================================="
echo "[SUCCESS] TeamOne 私有化纯离线交付包制作成功！"
echo "交付包路径: $TAR_FILE"
echo "包文件大小: $(du -h "$TAR_FILE" | cut -f1)"
if [ -f "$TAR_FILE.sha256" ]; then
  echo "校验和: $(cat "$TAR_FILE.sha256")"
fi
echo "=========================================================="
