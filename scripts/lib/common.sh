#!/usr/bin/env bash
# 公共函数：被 start_dev / start_prod / stop_all 引用

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ROOT
export PYTHONPATH="${ROOT}/libs:${PYTHONPATH:-}"

LOG_DIR="${ROOT}/logs"
PID_DIR="${LOG_DIR}/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

# name:dir:port
SERVICES=(
  "log-service:services/log/app:8009"
  "config-service:services/config/app:8002"
  "data-service:services/data/app:8001"
  "plugin-service:services/plugin/app:8003"
  "backtest-service:services/backtest/app:8004"
  "chart-service:services/chart/app:8005"
  "agent-service:services/agent/app:8006"
  "multi-agent-service:services/multi_agent/app:8007"
  "ops-service:services/ops/app:8008"
  "sync-service:services/sync/app:8010"
  "notify-service:services/notify/app:8011"
  "gateway:services/gateway/app:8000"
)

# shellcheck disable=SC1091
if [ -f "${ROOT}/.env" ]; then
  set -a
  # shellcheck source=/dev/null
  source "${ROOT}/.env"
  set +a
fi

pid_file() {
  echo "${PID_DIR}/$1.pid"
}

is_running() {
  local name=$1
  local pf
  pf=$(pid_file "$name")
  if [ -f "$pf" ]; then
    local pid
    pid=$(cat "$pf" 2>/dev/null || true)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
  fi
  return 1
}

wait_port() {
  local port=$1
  local retries=${2:-30}
  local i=0
  while [ $i -lt "$retries" ]; do
    if python3 -c "import socket;s=socket.socket();s.settimeout(0.3);s.connect(('127.0.0.1',$port));s.close()" 2>/dev/null; then
      return 0
    fi
    sleep 0.3
    i=$((i + 1))
  done
  return 1
}

start_one() {
  local name=$1
  local dir=$2
  local port=$3
  local mode=${4:-dev} # dev | prod

  if is_running "$name"; then
    echo "[skip] $name already running (pid $(cat "$(pid_file "$name")"))"
    return 0
  fi

  local app_dir="${ROOT}/${dir}"
  if [ ! -d "$app_dir" ]; then
    echo "[error] missing dir: $app_dir"
    return 1
  fi

  local log_file="${LOG_DIR}/${name}.log"
  echo "[start] $name :$port ($mode)"

  cd "$app_dir" || return 1

  if [ "$mode" = "prod" ]; then
    # 生产：多 worker、无 reload
    local workers=${UVICORN_WORKERS:-2}
    nohup python3 -m uvicorn main:app \
      --host 0.0.0.0 \
      --port "$port" \
      --workers "$workers" \
      --log-level info \
      --no-access-log \
      >>"$log_file" 2>&1 &
  else
    # 开发：单进程，便于调试；不使用 reload 以免 PID 管理混乱
    nohup python3 -c "
import sys
sys.path.insert(0, '${ROOT}/libs')
import uvicorn
from main import app
uvicorn.run(app, host='0.0.0.0', port=${port}, log_level='info')
" >>"$log_file" 2>&1 &
  fi

  echo $! >"$(pid_file "$name")"
  if wait_port "$port" 40; then
    echo "[ok]   $name ready on :$port (pid $(cat "$(pid_file "$name")"))"
  else
    echo "[warn] $name started but port $port not ready yet — check $log_file"
  fi
}

stop_one() {
  local name=$1
  local pf
  pf=$(pid_file "$name")
  if [ ! -f "$pf" ]; then
    echo "[skip] $name no pid file"
    return 0
  fi
  local pid
  pid=$(cat "$pf" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "[stop] $name pid=$pid"
    kill "$pid" 2>/dev/null || true
    sleep 0.5
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
  else
    echo "[skip] $name not running"
  fi
  rm -f "$pf"
}

status_one() {
  local name=$1
  local port=$2
  if is_running "$name"; then
    local health="?"
    if python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:${port}/health', timeout=1)" 2>/dev/null; then
      health="healthy"
    else
      health="up-no-health"
    fi
    printf "%-22s pid=%-7s port=%-5s %s\n" "$name" "$(cat "$(pid_file "$name")")" "$port" "$health"
  else
    printf "%-22s %-12s port=%-5s\n" "$name" "stopped" "$port"
  fi
}
