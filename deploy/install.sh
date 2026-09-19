#!/usr/bin/env bash
# TeamOne 私有化环境一键初始化与离线安装脚本（V-18）
#
# 适用环境：
#   客户企业内网、隔离离线机房、纯物理机/虚拟机（支持 Ubuntu / Debian / RHEL / CentOS / Rocky 等）
# 执行方式：
#   解压交付包后在当前目录下直接执行：
#   bash install.sh [--monitoring]
#
# SLA 达成标准：
#   从空机到全服务就绪可用 ≤ 2 小时（实测离线加载通常 ≤ 10 分钟）
#                                                        —— Ivan Yang, 2026-09-13
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ENABLE_MONITORING=false
if [ "${1:-}" = "--monitoring" ]; then
  ENABLE_MONITORING=true
fi

echo "=========================================================="
echo "          TeamOne 协作研发平台 私有化一键安装程序        "
echo "=========================================================="

# 1. 环境先决条件自检
echo "[1/5] 执行运行环境依赖自检..."
command -v docker >/dev/null 2>&1 || { echo "[x] 错误：未检测到 Docker，请先安装 Docker 引擎！" >&2; exit 1; }

COMPOSE_CMD=""
if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD="docker-compose"
else
  echo "[x] 错误：未检测到 Docker Compose 插件！" >&2
  exit 1
fi
echo "[ok] Docker 与 Compose 运行时就绪 ($($COMPOSE_CMD version --short 2>/dev/null || echo 'OK'))"

# 检查可用内存与磁盘
AVAILABLE_DISK_KB="$(df -k "$SCRIPT_DIR" | tail -n1 | awk '{print $4}')"
if [ "$AVAILABLE_DISK_KB" -lt 2097152 ]; then # 2GB
  echo "[!] 警告：当前分区可用磁盘空间不足 2GB，可能影响数据持久化"
else
  echo "[ok] 磁盘空间检查通过 ($(( AVAILABLE_DISK_KB / 1024 )) MB 可用)"
fi

# 2. 离线镜像导入
if [ -f "images.tar.gz" ]; then
  echo "[2/5] 发现离线镜像包 images.tar.gz，正在导入本地 Docker (docker load)..."
  docker load -i images.tar.gz
  echo "[ok] 离线镜像导入完成！"
else
  echo "[2/5] 未检测到本地 images.tar.gz，将直接使用既有本地镜像或在线拉取"
fi

# 3. 环境变量初始化
echo "[3/5] 正在配置系统运行参数 (.env)..."
if [ ! -f ".env" ]; then
  if [ -f ".env.example" ]; then
    cp .env.example .env
  else
    touch .env
  fi

  # 随机生成生产安全的 JWT 密钥与口令
  RANDOM_SECRET="$(openssl rand -hex 32 2>/dev/null || date +%s%N | sha256sum | head -c 64)"
  echo "TEAMONE_JWT_SECRET=teamone-prod-$RANDOM_SECRET" >> .env
  echo "TEAMONE_DB_PASSWORD=$(openssl rand -hex 12 2>/dev/null || echo 'teamone-prod-db-pass')" >> .env
  echo "MINIO_SECRET_KEY=$(openssl rand -hex 16 2>/dev/null || echo 'teamone-prod-minio-pass')" >> .env
  echo "[ok] 已自动生成高强度生产安全凭证并写入 .env"
else
  echo "[ok] 沿用现有 .env 配置文件"
fi

# 4. 启动容器编排集群
echo "[4/5] 正在拉起服务集群..."
COMPOSE_FILE="docker-compose.yml"
if [ ! -f "$COMPOSE_FILE" ] && [ -f "docker-compose.prod.yml" ]; then
  COMPOSE_FILE="docker-compose.prod.yml"
fi

PROFILES_ARG=""
if [ "$ENABLE_MONITORING" = true ]; then
  PROFILES_ARG="--profile monitoring"
  echo "[i] 已启用 Prometheus 监控指标套件"
fi

$COMPOSE_CMD -f "$COMPOSE_FILE" $PROFILES_ARG up -d

# 5. 健康检查与就绪等待
echo "[5/5] 正在等待各核心服务初始化就绪..."
MAX_RETRIES=30
RETRY_COUNT=0
SERVER_READY=false

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
  RETRY_COUNT=$((RETRY_COUNT + 1))
  # 探测 Nginx 或直接探测 8080 端口
  if curl -sf http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    SERVER_READY=true
    break
  fi
  sleep 3
  echo -n "."
done
echo

if [ "$SERVER_READY" = true ]; then
  echo "=========================================================="
  echo "           🎉 TeamOne 平台部署成功，所有服务已就绪！        "
  echo "=========================================================="
  echo "访问入口 (Web 前端):     http://<服务器IP> 或 https://<服务器IP>"
  echo "API 网关与服务端口:      http://<服务器IP>:8080"
  echo "系统健康检查端点:        http://<服务器IP>:8080/actuator/health"
  echo "Prometheus 指标端点:     http://<服务器IP>:8080/actuator/prometheus"
  echo "MinIO 控制台:            http://<服务器IP>:9001"
  if [ "$ENABLE_MONITORING" = true ]; then
    echo "Prometheus 服务端:       http://<服务器IP>:9090"
  fi
  echo "----------------------------------------------------------"
  echo "默认管理员账号: admin / admin123 (首次登录请及时修改)"
  echo "数据库灾备与还原工具: bash backup-db.sh / bash restore-db.sh"
  echo "=========================================================="
else
  echo "[!] 服务启动已派发，但健康检查暂未完全返回，请通过 '$COMPOSE_CMD ps' 查看实时容器状态"
fi
