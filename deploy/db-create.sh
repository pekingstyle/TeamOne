#!/usr/bin/env bash
# 在 WSL 内执行（凭据从 deploy/.env 读取，不写死在本脚本）：
#   bash deploy/db-create.sh
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${1:-$SCRIPT_DIR/.env}"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

HOST_PORT="${TEAMONE_DB_URL#jdbc:postgresql://}"   # host:port/dbname
DBHOST="${HOST_PORT%%/*}"
DBPORT="${DBHOST##*:}"
DBHOST="${DBHOST%%:*}"
DBNAME="${HOST_PORT#*/}"
DBNAME="${DBNAME%%\?*}"

psql_exec() {
  docker run --rm -i -e PGPASSWORD="$TEAMONE_DB_PASSWORD" postgres:18-alpine \
    psql -h "$DBHOST" -p "$DBPORT" -U "$TEAMONE_DB_USER" "$@"
}

echo "[i] target ${DBHOST}:${DBPORT}/${DBNAME}"
psql_exec -d postgres -tAc "SHOW server_version;" | head -1

if psql_exec -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${DBNAME}'" | grep -q 1; then
  echo "[ok] database ${DBNAME} already exists"
else
  psql_exec -d postgres -c "CREATE DATABASE ${DBNAME}"
  echo "[ok] database ${DBNAME} created"
fi
