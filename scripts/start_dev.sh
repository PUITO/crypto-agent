#!/usr/bin/env bash
# 开发环境：启动全部微服务（单进程，日志在 logs/）
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

echo "=== Crypto Agent DEV start ==="
echo "ROOT=$ROOT"
echo "PYTHONPATH=$PYTHONPATH"

ONLY="${1:-}"

if [ -n "$ONLY" ]; then
  found=0
  for spec in "${SERVICES[@]}"; do
    IFS=':' read -r name dir port <<<"$spec"
    if [ "$name" = "$ONLY" ] || [ "$dir" = "services/$ONLY/app" ]; then
      start_one "$name" "$dir" "$port" "dev"
      found=1
      break
    fi
  done
  if [ "$found" -eq 0 ]; then
    echo "Unknown service: $ONLY"
    echo "Available:"
    for spec in "${SERVICES[@]}"; do
      IFS=':' read -r name _ _ <<<"$spec"
      echo "  $name"
    done
    exit 1
  fi
else
  for spec in "${SERVICES[@]}"; do
    IFS=':' read -r name dir port <<<"$spec"
    start_one "$name" "$dir" "$port" "dev"
  done
fi

echo ""
echo "=== Status ==="
"${SCRIPT_DIR}/status.sh" || true
echo ""
echo "Gateway:  http://127.0.0.1:8000/docs"
echo "Ops:      http://127.0.0.1:8008/docs"
echo "Frontend: cd frontend && npm run dev   # or static: python3 -m http.server 5500 --directory frontend"
echo "Demo:     http://127.0.0.1:5500/public-demo.html"
