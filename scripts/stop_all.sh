#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

echo "=== Stopping all services ==="
# reverse order
for ((i = ${#SERVICES[@]} - 1; i >= 0; i--)); do
  IFS=':' read -r name _ _ <<<"${SERVICES[$i]}"
  stop_one "$name"
done
echo "Done."
