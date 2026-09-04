#!/bin/bash
# 用法: ./scripts/run_service.sh data
# 或:   ./scripts/run_service.sh config

SERVICE=$1

if [ -z "$SERVICE" ]; then
  echo "用法: $0 <service_name>"
  echo "可选: data config plugin backtest chart agent multi_agent gateway"
  exit 1
fi

ROOT=$(cd "$(dirname "$0")/.." && pwd)
export PYTHONPATH="$ROOT/libs:$PYTHONPATH"

cd "$ROOT/services/$SERVICE/app" || exit 1
echo "Starting $SERVICE service..."
python main.py
