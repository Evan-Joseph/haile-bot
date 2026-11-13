#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$SCRIPT_DIR/.server.pid"
LOG_FILE="$SCRIPT_DIR/server.log"
OPEN_HOST="${HAILE_OPEN_HOST:-127.0.0.1}"
PORT="${HAILE_PORT:-8010}"
URL="http://${OPEN_HOST}:${PORT}"
PYTHON_BIN="$SCRIPT_DIR/.venv/bin/python"
if [[ ! -x "$PYTHON_BIN" ]]; then
    PYTHON_BIN="python3"
fi

find_server_pid() {
    local pid=""
    local command=""

    pid="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
    if [[ -n "$pid" ]]; then
        command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
        if [[ "$command" == *"server.py"* ]]; then
            echo "$pid"
            return 0
        fi
    fi

    if [[ -f "$PID_FILE" ]]; then
        pid="$(<"$PID_FILE")"
        command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
        if [[ -n "$pid" ]] && [[ "$command" == *"server.py"* ]]; then
            echo "$pid"
            return 0
        fi
        rm -f "$PID_FILE"
    fi

    pid="$(pgrep -f "python3 .*server.py" | head -n 1 || true)"

    if [[ -n "$pid" ]]; then
        echo "$pid"
        return 0
    fi

    return 1
}

wait_until_stopped() {
    local pid="$1"
    local try_count=0

    while kill -0 "$pid" 2>/dev/null; do
        try_count=$((try_count + 1))
        if (( try_count >= 25 )); then
            kill -9 "$pid" 2>/dev/null || true
            break
        fi
        sleep 0.2
    done
}

if server_pid="$(find_server_pid)"; then
    echo "检测到服务正在运行，准备关闭..."
    kill "$server_pid" 2>/dev/null || true
    wait_until_stopped "$server_pid"
    rm -f "$PID_FILE"
    echo "服务已关闭。"
    exit 0
fi

cd "$SCRIPT_DIR"
server_pid="$(
    "$PYTHON_BIN" - "$SCRIPT_DIR" "$LOG_FILE" <<'PY'
import subprocess
import sys

script_dir = sys.argv[1]
log_file = sys.argv[2]

with open(log_file, "ab", buffering=0) as log_handle:
    process = subprocess.Popen(
        [sys.executable, "server.py"],
        cwd=script_dir,
        stdin=subprocess.DEVNULL,
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        close_fds=True,
    )
    print(process.pid)
PY
)"

ready=0
for _ in {1..50}; do
    if curl -fsS "$URL/api/health" >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 0.2
done

if actual_pid="$(find_server_pid)"; then
    echo "$actual_pid" >"$PID_FILE"
else
    echo "$server_pid" >"$PID_FILE"
fi

if [[ "${HAILE_SKIP_OPEN:-0}" != "1" ]]; then
    open "$URL"
fi

if (( ready )); then
    echo "服务已启动：$URL"
else
    echo "服务已启动，页面可能还在加载：$URL"
fi
