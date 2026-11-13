#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.local/bin:$PATH"
TMUX_BIN="$(command -v tmux || echo /opt/homebrew/bin/tmux)"
CLOUDFLARED_BIN="$(command -v cloudflared || echo /opt/homebrew/bin/cloudflared)"
PYTHON_BIN="$PROJECT_DIR/.venv/bin/python"
SERVER_SESSION="haile-bot-v3"
TUNNEL_SESSION="haile-bot-tunnel"
PORT="8010"
RELAY_PORT="17844"
CLOUDFLARE_CONFIG="$PROJECT_DIR/ops/haile-bot-cloudflared.yml"
EDGE_RELAY_SCRIPT="$PROJECT_DIR/ops/cf_edge_relay.py"
FORCE_RESTART="${1:-}"

log() {
  printf '[haile-bot] %s\n' "$1"
}

require_file() {
  local path="$1"
  local label="$2"
  if [ ! -e "$path" ]; then
    log "$label 不存在: $path"
    exit 1
  fi
}

require_cmd() {
  local path="$1"
  local label="$2"
  if [ ! -x "$path" ]; then
    log "$label 不可执行: $path"
    exit 1
  fi
}

require_file "$PROJECT_DIR/server.py" "server.py"
require_cmd "$TMUX_BIN" "tmux"
require_cmd "$PYTHON_BIN" "Python"

mkdir -p "$PROJECT_DIR/tmp"

session_exists() {
  "$TMUX_BIN" has-session -t "$1" 2>/dev/null
}

port_listening() {
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
}

relay_listening() {
  lsof -nP -iTCP:"$RELAY_PORT" -sTCP:LISTEN >/dev/null 2>&1
}

if [ "$FORCE_RESTART" = "--restart" ] || [ "$FORCE_RESTART" = "-r" ]; then
  "$TMUX_BIN" kill-session -t "$SERVER_SESSION" 2>/dev/null || true
  "$TMUX_BIN" kill-session -t "$TUNNEL_SESSION" 2>/dev/null || true
  pkill -f "ops/cf_edge_relay.py" 2>/dev/null || true
  sleep 1
fi

start_server() {
  if session_exists "$SERVER_SESSION" && port_listening; then
    return
  fi
  "$TMUX_BIN" kill-session -t "$SERVER_SESSION" 2>/dev/null || true
  "$TMUX_BIN" new-session -d -s "$SERVER_SESSION" \
    "cd \"$PROJECT_DIR\" && while true; do HAILE_PORT=$PORT \"$PYTHON_BIN\" server.py; sleep 2; done"
}

start_tunnel() {
  require_file "$EDGE_RELAY_SCRIPT" "cf_edge_relay.py"
  require_cmd "$CLOUDFLARED_BIN" "cloudflared"
  require_file "$CLOUDFLARE_CONFIG" "Cloudflare 配置"
  local cred_file
  cred_file="$(awk -F': *' '/^credentials-file:/ {print $2}' "$CLOUDFLARE_CONFIG" | head -n 1)"
  if [ -n "$cred_file" ] && [ ! -f "$cred_file" ]; then
    return
  fi
  if session_exists "$TUNNEL_SESSION" && relay_listening; then
    return
  fi
  "$TMUX_BIN" kill-session -t "$TUNNEL_SESSION" 2>/dev/null || true
  pkill -f "ops/cf_edge_relay.py" 2>/dev/null || true
  "$TMUX_BIN" new-session -d -s "$TUNNEL_SESSION" \
    "cd \"$PROJECT_DIR\" && trap 'kill 0' INT TERM EXIT; \"$PYTHON_BIN\" \"$EDGE_RELAY_SCRIPT\" & sleep 0.5; while true; do \"$CLOUDFLARED_BIN\" tunnel --no-autoupdate --config \"$CLOUDFLARE_CONFIG\" run; sleep 2; done"
}

start_server
if [ "${HAILE_ENABLE_TUNNEL:-0}" = "1" ]; then
  start_tunnel
fi

for _ in {1..15}; do
  if port_listening; then
    break
  fi
  sleep 1
done

if ! port_listening; then
  log "本地 8010 端口未成功拉起"
  exit 1
fi

for _ in {1..10}; do
  if curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null; then
    exit 0
  fi
  sleep 1
done

log "本地服务已监听，但健康检查未通过"
exit 1
