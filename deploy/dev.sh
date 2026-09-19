# TeamOne M0 一键起（Windows Git Bash）
# 用法: bash deploy/dev.sh [build|run|stop]
set -e
cd "$(dirname "$0")/.."

case "${1:-run}" in
  build)
    (cd server && mvn -q -DskipTests package)
    echo "[ok] server jar -> server/teamone-app/target/teamone-app.jar"
    ;;
  run)
    set -a; source deploy/.env; set +a
    (cd server && mvn -q -DskipTests package && java -jar teamone-app/target/teamone-app.jar) &
    echo "[ok] server starting on :${SERVER_PORT:-8080} (log: server/app.log)"
    ;;
  stop)
    taskkill //F //IM java.exe 2>/dev/null || true
    echo "[ok] stopped"
    ;;
  *) echo "usage: dev.sh [build|run|stop]"; exit 1 ;;
esac
