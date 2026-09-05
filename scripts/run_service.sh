#!/usr/bin/env bash
# 前台启动单个服务（适合开发/调试）
# 用法: ./scripts/run_service.sh data
#       ./scripts/run_service.sh agent
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

KEY="${1:-}"
if [ -z "$KEY" ]; then
  echo "用法: $0 <service>"
  echo "可选:"
  for spec in "${SERVICES[@]}"; do
    IFS=':' read -r name dir port <<<"$spec"
    short="${dir#services/}"
    short="${short%/app}"
    echo "  $short  ($name :$port)"
  done
  exit 1
fi

for spec in "${SERVICES[@]}"; do
  IFS=':' read -r name dir port <<<"$spec"
  short="${dir#services/}"
  short="${short%/app}"
  if [ "$KEY" = "$name" ] || [ "$KEY" = "$short" ] || [ "$KEY" = "${name%-service}" ]; then
    echo "Foreground: $name on :$port"
    cd "${ROOT}/${dir}"
    export PYTHONPATH="${ROOT}/libs:${PYTHONPATH:-}"
    exec python3 -c "
import sys
sys.path.insert(0, '${ROOT}/libs')
import uvicorn
from main import app
uvicorn.run(app, host='0.0.0.0', port=${port}, log_level='debug')
"
  fi
done

echo "Unknown service: $KEY"
exit 1
