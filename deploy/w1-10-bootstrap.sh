#!/usr/bin/env bash
# W1-10 容器侧一键引导（在 WSL 内执行；幂等，可重复跑）
#
# 用法（Windows 侧）:
#   wsl -d Ubuntu-24.04 bash /mnt/c/Users/afire/Workspace/Electron/TeamOne/deploy/w1-10-bootstrap.sh
# 或 WSL 内:
#   bash /mnt/c/Users/afire/Workspace/Electron/TeamOne/deploy/w1-10-bootstrap.sh
#
# 做什么: 起 Valkey/Gitea（host 网络，适配 WSL mirrored 模式）→ 建 admin/org/repo
#         → 生成 runner 注册 token 写入 deploy/.env → 注册并启动 act_runner
#           （config 强制 container.network=host，job 容器经 localhost 访问 Gitea）
#         → Gitea/Valkey 端口与宿主 :8080 连通性预检 → 状态汇总
# 密钥纪律: admin 口令与 runner token 只写 deploy/.env（gitignored）
#                                                 —— Ivan Yang, 2026-09-11
set -euo pipefail

ROOT="/mnt/c/Users/afire/Workspace/Electron/TeamOne"
COMPOSE="docker compose -f $ROOT/deploy/docker-compose.yml"
ENVF="$ROOT/deploy/.env"
ADMIN_USER="teamone-admin"
ADMIN_PASS="${GITEA_ADMIN_PASSWORD:-TeamOne@Admin123}"

step() { echo; echo "===== $1 ====="; }

step "1/7 Valkey + Gitea 启动（host 网络）"
$COMPOSE up -d valkey
$COMPOSE --profile eng up -d gitea

step "2/7 等待 Gitea 就绪（最长 60s）"
code="000"
for _ in $(seq 1 30); do
  code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 3 http://localhost:3000/api/healthz || echo 000)"
  [ "$code" = "200" ] && break
  sleep 2
done
if [ "$code" != "200" ]; then echo "[x] Gitea 未就绪（healthz=$code），查 docker logs teamone-gitea"; exit 1; fi
echo "[ok] Gitea healthz=200"

step "3/7 管理员账号（幂等）"
if docker exec --user git teamone-gitea gitea admin user list 2>/dev/null | grep -qw "$ADMIN_USER"; then
  echo "[ok] $ADMIN_USER 已存在"
else
  docker exec --user git teamone-gitea gitea admin user create --admin \
    --username "$ADMIN_USER" --password "$ADMIN_PASS" \
    --email "${ADMIN_USER}@teamone.local" --must-change-password=false
fi

step "4/7 org=teamone + repo=teamone（幂等）"
code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -u "$ADMIN_USER:$ADMIN_PASS" -X POST \
  http://localhost:3000/api/v1/orgs -H 'Content-Type: application/json' \
  -d '{"username":"teamone"}')"
echo "[i] org create http=$code（201=新建 / 422=已存在）"
code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -u "$ADMIN_USER:$ADMIN_PASS" -X POST \
  http://localhost:3000/api/v1/orgs/teamone/repos -H 'Content-Type: application/json' \
  -d '{"name":"teamone","private":true,"auto_init":false}')"
echo "[i] repo create http=$code（201=新建 / 409=已存在）"

step "5/7 act_runner 注册 + 启动（幂等：已注册则跳过）"
if docker ps --format '{{.Names}}' | grep -q '^teamone-runner$'; then
  echo "[ok] teamone-runner 已在运行"
else
  docker rm -f teamone-runner >/dev/null 2>&1 || true
  TOKEN="$(docker exec --user git teamone-gitea gitea actions generate-runner-token)"
  if grep -q '^GITEA_RUNNER_TOKEN=' "$ENVF" 2>/dev/null; then
    sed -i "s|^GITEA_RUNNER_TOKEN=.*|GITEA_RUNNER_TOKEN=$TOKEN|" "$ENVF"
  else
    printf '\nGITEA_RUNNER_TOKEN=%s\n' "$TOKEN" >> "$ENVF"
  fi
  # 生成默认 config：job 容器强制 host 网络（否则 job 内 localhost:3000 指向自身，checkout 失败）
  docker run --rm -v teamone_runnerdata:/data gitea/act_runner:latest generate-config > /tmp/runner-config.yaml
  sed -i 's/^  network: ""/  network: "host"/' /tmp/runner-config.yaml
  grep -A1 '^container:' /tmp/runner-config.yaml | head -2
  docker run --rm -v teamone_runnerdata:/data -v /tmp/runner-config.yaml:/data/config.yaml:ro \
    gitea/act_runner:latest register --no-serve --config /data/config.yaml \
    --instance http://localhost:3000 --token "$TOKEN" --name teamone-runner
  docker run -d --name teamone-runner --restart unless-stopped \
    -v teamone_runnerdata:/data \
    -v /var/run/docker.sock:/var/run/docker.sock \
    gitea/act_runner:latest daemon --config /data/config.yaml
  sleep 3
  docker logs teamone-runner 2>&1 | tail -3
fi

step "6/7 连通性预检（mirrored 模式：localhost 双向互通）"
echo -n "[i] WSL → Windows server :8080  → "
curl -s --noproxy '*' -o /dev/null -w 'health=%{http_code}\n' --max-time 5 http://localhost:8080/actuator/health || echo "000（server 未启动或防火墙）"
echo -n "[i] 容器 → 宿主 :8080           → "
docker exec teamone-gitea curl -s -o /dev/null -w 'health=%{http_code}\n' --max-time 5 http://localhost:8080/actuator/health || echo "000"
echo -n "[i] Windows → Gitea :3000       → 上一轮第 2 步 healthz=$code（Windows 侧请自行 curl 验证）"

step "7/7 状态汇总"
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E "teamone" || true
echo
echo "[done] 容器侧就绪。下一步在 Windows Git Bash：git push（token URL）→ CI 首跑。"
