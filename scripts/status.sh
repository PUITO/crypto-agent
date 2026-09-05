#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

echo "Service status:"
for spec in "${SERVICES[@]}"; do
  IFS=':' read -r name _ port <<<"$spec"
  status_one "$name" "$port"
done
