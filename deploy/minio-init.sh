#!/usr/bin/env bash
# MinIO bucket 初始化（INC-2 T-5）：读 .env 的 MINIO_*（缺省与 compose 缺省对齐），
# mc alias set + mc mb --ignore-existing，幂等可重放。
# 用法（WSL 内）: bash deploy/minio-init.sh
set -euo pipefail
cd "$(dirname "$0")"

# .env 可选（MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY/MINIO_BUCKET）
if [ -f .env ]; then
  # shellcheck disable=SC1091
  set -a; source .env; set +a
fi

ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:9000}"
ACCESS_KEY="${MINIO_ACCESS_KEY:-teamone}"
SECRET_KEY="${MINIO_SECRET_KEY:-teamone-dev-minio}"
BUCKET="${MINIO_BUCKET:-teamone-dev}"

# mc 单二进制（容器内常驻，宿主机按需取）
if ! command -v mc >/dev/null 2>&1; then
  echo "[mc] not found, pulling minio/mc image ..."
  exec docker run --rm --network host minio/mc sh -c "
    mc alias set local '$ENDPOINT' '$ACCESS_KEY' '$SECRET_KEY' &&
    mc mb --ignore-existing local/'$BUCKET' &&
    mc ls local"
fi

mc alias set local "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY"
mc mb --ignore-existing "local/$BUCKET"
mc ls local
echo "[ok] bucket ready: $BUCKET @ $ENDPOINT"
