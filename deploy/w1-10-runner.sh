#!/usr/bin/env bash
# W1-10 第 5-7 步：act_runner 注册/启动 + 连通性预检（幂等）
# 关键教训：gitea/act_runner 镜像入口是 run.sh（吞掉 CLI 参数走交互注册），
#          必须 --entrypoint act_runner 直调二进制；config 固化进数据卷。
set -euo pipefail

ROOT="/mnt/c/Users/afire/Workspace/Electron/TeamOne"
ENVF="$ROOT/deploy/.env"

echo "===== 5/7 act_runner 注册 + 启动 ====="
if docker ps --format '{{.Names}}' | grep -q '^teamone-runner$'; then
  echo "[ok] teamone-runner 已在运行"
else
  docker rm -f teamone-runner >/dev/null 2>&1 || true

  TOKEN="$(docker exec --user git teamone-gitea gitea actions generate-runner-token)"
  echo "[i] token acquired (len=${#TOKEN})"
  if grep -q '^GITEA_RUNNER_TOKEN=' "$ENVF" 2>/dev/null; then
    sed -i "s|^GITEA_RUNNER_TOKEN=.*|GITEA_RUNNER_TOKEN=$TOKEN|" "$ENVF"
  else
    printf '\nGITEA_RUNNER_TOKEN=%s\n' "$TOKEN" >> "$ENVF"
  fi

  # 干净 config 固化进数据卷（/data/config.yaml，内含 runner.file=/data/.runner 与
  # container.network=host）。注意：act_runner 镜像一律 --entrypoint act_runner，
  # 否则 run.sh 会吞掉参数走交互注册死循环。
  docker run --rm -i -v teamone_runnerdata:/data busybox sh -c 'cat > /data/config.yaml' \
    < "$ROOT/deploy/runner-config.yaml"
  echo "[i] config in volume:"
  docker run --rm -v teamone_runnerdata:/data busybox grep -E 'file: /data/.runner|network: "host"' /data/config.yaml

  echo "[i] registering..."
  docker run --rm --network host --entrypoint act_runner -v teamone_runnerdata:/data \
    gitea/act_runner:latest register --config /data/config.yaml \
    --instance http://localhost:3000 --token "$TOKEN" --name teamone-runner

  echo "[i] starting daemon..."
  docker run -d --name teamone-runner --restart unless-stopped \
    --network host \
    --entrypoint act_runner \
    -v teamone_runnerdata:/data \
    -v /var/run/docker.sock:/var/run/docker.sock \
    gitea/act_runner:latest daemon --config /data/config.yaml
  sleep 4
  docker logs teamone-runner 2>&1 | tail -3
fi

echo
echo "===== 6/7 连通性预检（mirrored 模式） ====="
echo -n "[i] WSL → Windows server :8080  → "
curl -s --noproxy '*' -o /dev/null -w 'health=%{http_code}\n' --max-time 5 http://localhost:8080/actuator/health || echo "000（server 未启动）"
echo -n "[i] gitea 容器 → 宿主 :8080     → "
docker exec teamone-gitea curl -s -o /dev/null -w 'health=%{http_code}\n' --max-time 5 http://localhost:8080/actuator/health || echo "000"
echo -n "[i] WSL → valkey :6379          → "
(echo -n > /dev/tcp/127.0.0.1/6379) 2>/dev/null && echo "tcp=通" || echo "tcp=不通"

echo
echo "===== 7/7 状态汇总 ====="
docker ps --format '{{.Names}}\t{{.Status}}' | grep teamone
echo
echo "[done] 容器侧就绪。下一步（Windows Git Bash）：git push → CI 首跑。"
