#!/usr/bin/env bash
# 自签开发证书生成（INC-2 T-6）：CN=teamone.local，SAN 覆盖 localhost/LAN IP/127.0.0.1，365 天
# 产物：deploy/nginx/tls/teamone.{crt,key}（tls/ 已 gitignore——私钥严禁入库，红线 7）
# 用法: bash deploy/nginx/gen-dev-cert.sh [LAN_IP]   # 缺省 192.168.2.101（本机 LAN IP）
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash 会把 -subj 的 /CN=... 改写成 Windows 路径——本脚本内禁用（其他环境无副作用）
export MSYS_NO_PATHCONV=1

LAN_IP="${1:-192.168.2.101}"
TLS_DIR="tls"
CRT="$TLS_DIR/teamone.crt"
KEY="$TLS_DIR/teamone.key"

mkdir -p "$TLS_DIR"
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout "$KEY" -out "$CRT" \
  -subj "/CN=teamone.local/O=TeamOne Dev" \
  -addext "subjectAltName=DNS:localhost,DNS:teamone.local,IP:$LAN_IP,IP:127.0.0.1"

echo "[ok] self-signed cert -> $CRT / $KEY (SAN: DNS:localhost, DNS:teamone.local, IP:$LAN_IP, IP:127.0.0.1)"
echo "     客户端须忽略证书校验（curl -k / 测试客户端 rejectUnauthorized:false 等价）"
