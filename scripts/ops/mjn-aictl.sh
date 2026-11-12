#!/usr/bin/env bash
# MJN-AI service operations. This script never builds, copies or switches releases.
set -Eeuo pipefail
umask 027
CONFIG_ENV=${MJN_CONFIG_ENV:-/etc/mjn-ai/config.env}
if [[ -r "$CONFIG_ENV" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$CONFIG_ENV"
  set +a
fi
NGINX=${MJN_NGINX_BIN:-/opt/mjn-ai/nginx/sbin/nginx}
PY_UNIT=mjn-ai-python
JAVA_UNIT=mjn-ai-java
WEB_UNIT=mjn-nginx
REDIS_UNIT=mjn-redis
DB_UNIT=mysqld
PY_PORT=${MJN_PYTHON_PORT:-${MJN_PY_PORT:-58000}}
JAVA_PORT=${RUOYI_JAVA_PORT:-${MJN_JAVA_PORT:-56039}}
WEB_PORT=${MJN_WEB_PORT:-50080}
ADMIN_PORT=${MJN_ADMIN_PORT:-58080}
REDIS_PORT=${RUOYI_REDIS_PORT:-${MJN_REDIS_PORT:-56379}}
DB_PORT=${RUOYI_MYSQL_PORT:-${MJN_MYSQL_PORT:-${MJN_DB_PORT:-51206}}}
ACTION=${1:-help}
CURRENT_UNIT=''
DRY_RUN=0
if (( $# > 0 )); then shift; fi
if (( $# > 0 )) && [[ ${!#} == --dry-run ]]; then
  DRY_RUN=1
  set -- "${@:1:$(($# - 1))}"
fi
TARGET_LABEL="$*"
usage() {
  cat <<'HELP'
Usage: bash mjn-aictl.sh ACTION [TARGET ...] [--dry-run]
  restart java|python|web|admin|frontend [more targets ...]
  reload  web|admin|frontend
  start   java|python|web|admin|frontend [more targets ...]
  stop    java|python|web|admin|frontend [more targets ...]
  restart|start|stop apps|all
  status
  check
  ports
  help
Custom combinations contain only Java, Python and the two frontends.
web/admin/frontend share Nginx and are deduplicated to one operation.
apps = Python + Java + Nginx; MySQL and Redis stay running.
all  = MySQL + Redis + Python + Java + Nginx (maintenance downtime).
frontend = Nginx; it does not build or upload frontend files.
--dry-run prints the plan without changing services or checking readiness.
HELP
}
case "$ACTION" in
  help|-h|--help) (( $# == 0 )) || { usage; exit 2; }; usage; exit 0 ;;
  status|check|ports) (( $# == 0 )) || { usage; exit 2; } ;;
  restart|start|stop) (( $# > 0 )) || { usage; exit 2; } ;;
  reload) (( $# == 1 )) && [[ ${1:-} == web || ${1:-} == admin || ${1:-} == frontend ]] || { usage; exit 2; } ;;
  *) usage; exit 2 ;;
esac
for target in "$@"; do
  case "$target" in python|java|web|admin|frontend|apps|all) ;; *) usage; exit 2 ;; esac
done
if (( $# > 1 )); then
  for target in "$@"; do
    [[ "$target" != apps && "$target" != all ]] || { usage; exit 2; }
  done
fi
log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
run() {
  printf '+ '; printf '%q ' "$@"; printf '\n'
  if [[ "$DRY_RUN" == 0 ]]; then "$@"; fi
}
fail_report() {
  local rc=$?
  trap - ERR
  printf '\nOperation failed (exit %s), stage/service: %s\n' "$rc" "${CURRENT_UNIT:-preflight}" >&2
  if [[ -n "$CURRENT_UNIT" ]]; then
    systemctl status "$CURRENT_UNIT" --no-pager -l >&2 || true
    journalctl -u "$CURRENT_UNIT" -n 60 --no-pager >&2 || true
  fi
  printf 'Remaining steps were stopped. Inspect the logs before retrying.\n' >&2
  exit "$rc"
}
tcp_ready() {
  local unit=$1 port=$2 seconds=$3
  CURRENT_UNIT=$unit
  if [[ "$DRY_RUN" == 1 ]]; then log "CHECK $unit 127.0.0.1:$port (up to ${seconds}s)"; return; fi
  local deadline=$((SECONDS + seconds))
  log "Waiting for $unit: 127.0.0.1:$port"
  while (( SECONDS < deadline )); do
    if systemctl is-active --quiet "$unit" && timeout 2 bash -c "</dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      log "$unit TCP check passed"
      return 0
    fi
    if systemctl is-failed --quiet "$unit"; then return 1; fi
    sleep 2
  done
  log "Timed out waiting for $unit"
  return 1
}
http_ready() {
  local unit=$1 url=$2 seconds=$3 body=${4:-}
  CURRENT_UNIT=$unit
  if [[ "$DRY_RUN" == 1 ]]; then log "CHECK $unit $url (up to ${seconds}s)"; return; fi
  local deadline=$((SECONDS + seconds)) response
  log "Waiting for $unit: $url"
  while (( SECONDS < deadline )); do
    if systemctl is-active --quiet "$unit"; then
      if response=$(curl -fsS --connect-timeout 1 --max-time 3 "$url" 2>/dev/null); then
        if [[ -z "$body" || "$response" == *"$body"* ]]; then
          # Recheck service state after the HTTP response.
          if systemctl is-active --quiet "$unit"; then log "$unit HTTP check passed"; return 0; fi
        fi
      fi
    fi
    if systemctl is-failed --quiet "$unit"; then return 1; fi
    sleep 2
  done
  log "Timed out waiting for $unit"
  return 1
}
check_unit() {
  local unit=$1
  case "$unit" in
    "$DB_UNIT") tcp_ready "$unit" "$DB_PORT" 90 ;;
    "$REDIS_UNIT") tcp_ready "$unit" "$REDIS_PORT" 60 ;;
    "$PY_UNIT") http_ready "$unit" "http://127.0.0.1:${PY_PORT}/health" 60 '"status":"ok"' ;;
    "$JAVA_UNIT") http_ready "$unit" "http://127.0.0.1:${JAVA_PORT}/" 180 'ruoyi-ai' ;;
    "$WEB_UNIT")
      http_ready "$unit" "http://127.0.0.1:${WEB_PORT}/" 30
      http_ready "$unit" "http://127.0.0.1:${ADMIN_PORT}/" 30
      http_ready "$unit" "http://127.0.0.1:${WEB_PORT}/prod-api/" 30 'ruoyi-ai'
      http_ready "$unit" "http://127.0.0.1:${ADMIN_PORT}/api/" 30 'ruoyi-ai'
      ;;
    *) CURRENT_UNIT=$unit; run systemctl is-active --quiet "$unit" ;;
  esac
}
operate_unit() {
  local operation=$1 unit=$2
  CURRENT_UNIT=$unit
  # CentOS 7 may retain a previous start-rate-limit state. Clear it before
  # start/reload so a maintenance stop cannot block the following operation.
  if [[ "$operation" != stop ]]; then
    run systemctl reset-failed "$unit"
  fi
  run systemctl "$operation" "$unit"
  if [[ "$operation" != stop ]]; then check_unit "$unit"; fi
}
declare -A SELECTED=()
select_unit() { SELECTED["$1"]=1; }
select_target() {
  case "$1" in
    python) select_unit "$PY_UNIT" ;;
    java) select_unit "$JAVA_UNIT" ;;
    web|admin|frontend) select_unit "$WEB_UNIT" ;;
    apps)
      select_unit "$PY_UNIT"
      select_unit "$JAVA_UNIT"
      select_unit "$WEB_UNIT"
      ;;
    all)
      select_unit "$DB_UNIT"
      select_unit "$REDIS_UNIT"
      select_unit "$PY_UNIT"
      select_unit "$JAVA_UNIT"
      select_unit "$WEB_UNIT"
      ;;
  esac
}
is_selected() { [[ ${SELECTED[$1]:-0} == 1 ]]; }
START_ORDER=("$DB_UNIT" "$REDIS_UNIT" "$PY_UNIT" "$JAVA_UNIT" "$WEB_UNIT")
STOP_ORDER=("$WEB_UNIT" "$JAVA_UNIT" "$PY_UNIT" "$REDIS_UNIT" "$DB_UNIT")
selected_units() {
  local units=() unit
  for unit in "${START_ORDER[@]}"; do
    if is_selected "$unit"; then units+=("$unit"); fi
  done
  printf '%s' "${units[*]}"
}
if [[ "$ACTION" == ports ]]; then
  printf '%-12s %s\n' 'Nginx web' "$WEB_PORT" 'Nginx admin' "$ADMIN_PORT" 'Java' "$JAVA_PORT" 'Python' "$PY_PORT" 'Redis' "$REDIS_PORT" 'MySQL' "$DB_PORT"
  exit 0
fi
if [[ "$ACTION" == status ]]; then
  result=0
  for unit in "${START_ORDER[@]}"; do
    printf '\n=== %s ===\n' "$unit"
    systemctl status "$unit" --no-pager -l || result=1
  done
  exit "$result"
fi
if [[ "$ACTION" == check ]]; then
  trap fail_report ERR
  for unit in "${START_ORDER[@]}"; do check_unit "$unit"; done
  log 'SUCCESS: all basic checks passed. Login/model calls are not covered.'
  exit 0
fi
if [[ "$DRY_RUN" == 0 ]]; then
  [[ $(id -u) == 0 ]] || { echo 'Run with root privileges.' >&2; exit 1; }
  for command in systemctl journalctl curl flock tee timeout; do command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }; done
  STATE_DIR=${MJN_OPS_STATE_DIR:-/run/mjn-ai-ops}
  LOG_DIR=${MJN_OPS_LOG_DIR:-/var/log/mjn-ai/ops}
  mkdir -p "$STATE_DIR" "$LOG_DIR"
  exec 9>"$STATE_DIR/control.lock"
  flock -n 9 || { echo 'Another MJN-AI operation is in progress.' >&2; exit 1; }
  TARGET_KEY=${TARGET_LABEL// /-}
  LOG_FILE="$LOG_DIR/$(date +%Y%m%d-%H%M%S)-$$-$ACTION-$TARGET_KEY.log"
  exec > >(tee -a "$LOG_FILE") 2>&1
  trap fail_report ERR
  log "Operation log: $LOG_FILE"
fi
log "Requested: $ACTION $TARGET_LABEL (dry-run=$DRY_RUN)"
for target in "$@"; do select_target "$target"; done
log "Resolved services: $(selected_units)"
# Validate Nginx before stopping or changing any selected services.
if [[ "$ACTION" != stop ]] && is_selected "$WEB_UNIT"; then
  CURRENT_UNIT=$WEB_UNIT
  run "$NGINX" -t
fi
case "$ACTION" in
  reload) operate_unit reload "$WEB_UNIT" ;;
  start)
    for unit in "${START_ORDER[@]}"; do
      if is_selected "$unit"; then operate_unit start "$unit"; fi
    done
    ;;
  stop)
    for unit in "${STOP_ORDER[@]}"; do
      if is_selected "$unit"; then operate_unit stop "$unit"; fi
    done
    ;;
  restart)
    for unit in "${STOP_ORDER[@]}"; do
      if is_selected "$unit"; then operate_unit stop "$unit"; fi
    done
    for unit in "${START_ORDER[@]}"; do
      if is_selected "$unit"; then operate_unit start "$unit"; fi
    done
    ;;
esac
CURRENT_UNIT=''
log "SUCCESS: completed $ACTION $TARGET_LABEL"
