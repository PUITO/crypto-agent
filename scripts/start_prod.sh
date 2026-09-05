#!/usr/bin/env bash
# 生产环境启动：多 worker、读 .env、无 reload
# 用法: ./scripts/start_prod.sh
# 可选环境变量:
#   UVICORN_WORKERS=4
#   ENVIRONMENT=production
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

export ENVIRONMENT="${ENVIRONMENT:-production}"
export DEBUG="${DEBUG:-false}"
export LOG_LEVEL="${LOG_LEVEL:-INFO}"
# 生产建议打开远程日志
export LOG_REMOTE_ENABLED="${LOG_REMOTE_ENABLED:-true}"

if [ ! -f "${ROOT}/.env" ]; then
  echo "[warn] ${ROOT}/.env not found — using defaults + process env"
  if [ -f "${ROOT}/.env.example" ]; then
    echo "       copy: cp .env.example .env && edit secrets"
  fi
fi

echo "=== Crypto Agent PROD start ==="
echo "ENVIRONMENT=$ENVIRONMENT UVICORN_WORKERS=${UVICORN_WORKERS:-2}"

for spec in "${SERVICES[@]}"; do
  IFS=':' read -r name dir port <<<"$spec"
  # gateway / ops 建议单 worker，避免多进程状态分裂
  if [ "$name" = "gateway" ] || [ "$name" = "ops-service" ] || [ "$name" = "config-service" ] || [ "$name" = "log-service" ]; then
    UVICORN_WORKERS=1 start_one "$name" "$dir" "$port" "prod"
  else
    start_one "$name" "$dir" "$port" "prod"
  fi
done

echo ""
"${SCRIPT_DIR}/status.sh" || true
echo ""
echo "Health aggregate: curl -s http://127.0.0.1:8000/api/v1/health/all | jq ."
