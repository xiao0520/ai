#!/usr/bin/env bash
# One-time migration of the deployed MJN-AI stack to the approved ports.
set -Eeuo pipefail
umask 027

MYSQL_CONFIG=/etc/my.cnf
REDIS_CONFIG=/etc/mjn-ai/redis.conf
PYTHON_UNIT=/etc/systemd/system/mjn-ai-python.service
CONFIG_ENV=/etc/mjn-ai/config.env
NGINX_CONFIG=/etc/mjn-ai/nginx/nginx.conf
AICTL=/opt/mjn-ai/tools/mjn-aictl.sh
BACKUP_READY=0
BACKUP_DIR=''

usage() {
  cat <<'HELP'
Usage:
  bash change-ports.sh --dry-run
  bash change-ports.sh --apply

Changes the deployed services:
  Nginx web 80 -> 50080
  Nginx admin 8080 -> 58080
  Java 6039 -> 56039
  Python 8000 -> 58000
  Redis 6379 -> 56379
  MySQL 3306 -> 51206

--dry-run checks the expected files and values without changing services.
--apply backs up configurations, applies the ports, restarts all services,
and automatically restores the old configurations if any step fails.
HELP
}

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
require_file() { [[ -f "$1" ]] || die "Missing file: $1"; }
expect_old_or_new() {
  local file=$1 old=$2 new=$3 label=$4
  if grep -Fq -- "$old" "$file" || grep -Fq -- "$new" "$file"; then
    log "CHECK: $label"
  else
    die "Expected value not found for $label in $file"
  fi
}
replace_old_or_keep_new() {
  local file=$1 old=$2 new=$3 label=$4
  if grep -Fq -- "$old" "$file"; then
    sed -i "s|$old|$new|g" "$file"
    log "CHANGED: $label"
  elif grep -Fq -- "$new" "$file"; then
    log "UNCHANGED: $label already uses the new value"
  else
    die "Expected value not found for $label in $file"
  fi
}
rollback() {
  local rc=$?
  trap - ERR
  echo
  log "FAILED with exit $rc"
  if [[ "$BACKUP_READY" == 1 ]]; then
    log "Restoring configurations from $BACKUP_DIR"
    cp -a "$BACKUP_DIR/my.cnf" "$MYSQL_CONFIG"
    cp -a "$BACKUP_DIR/redis.conf" "$REDIS_CONFIG"
    cp -a "$BACKUP_DIR/mjn-ai-python.service" "$PYTHON_UNIT"
    cp -a "$BACKUP_DIR/config.env" "$CONFIG_ENV"
    cp -a "$BACKUP_DIR/nginx.conf" "$NGINX_CONFIG"
    systemctl daemon-reload || true
    systemctl restart mysqld || true
    systemctl restart mjn-redis || true
    systemctl restart mjn-ai-python || true
    systemctl restart mjn-ai-java || true
    systemctl restart mjn-nginx || true
    log "Rollback attempted. Backup retained at $BACKUP_DIR"
  fi
  exit "$rc"
}
trap rollback ERR

case "${1:-}" in
  --dry-run) MODE=dry-run ;;
  --apply) MODE=apply ;;
  *) usage; exit 2 ;;
esac
(( $# == 1 )) || { usage; exit 2; }

for file in "$MYSQL_CONFIG" "$REDIS_CONFIG" "$PYTHON_UNIT" "$CONFIG_ENV" "$NGINX_CONFIG" "$AICTL"; do
  require_file "$file"
done
for command in grep sed cp mkdir systemctl; do
  command -v "$command" >/dev/null || die "Missing command: $command"
done

if grep -Eq '^[[:space:]]*port[[:space:]]*=[[:space:]]*(3306|51206)([[:space:]]|$)' "$MYSQL_CONFIG"; then
  log 'CHECK: MySQL port line'
else
  grep -q '^\[mysqld\]' "$MYSQL_CONFIG" || die "Missing [mysqld] section in $MYSQL_CONFIG"
  log 'CHECK: MySQL port will be inserted under the first [mysqld] section'
fi
expect_old_or_new "$REDIS_CONFIG" 'port 6379' 'port 56379' 'Redis 6379 -> 56379'
expect_old_or_new "$PYTHON_UNIT" '--port 8000' '--port 58000' 'Python 8000 -> 58000'
expect_old_or_new "$CONFIG_ENV" 'RUOYI_JAVA_PORT=6039' 'RUOYI_JAVA_PORT=56039' 'Java 6039 -> 56039'
expect_old_or_new "$CONFIG_ENV" 'RUOYI_MYSQL_PORT=3306' 'RUOYI_MYSQL_PORT=51206' 'Java MySQL connection 3306 -> 51206'
expect_old_or_new "$CONFIG_ENV" 'RUOYI_REDIS_PORT=6379' 'RUOYI_REDIS_PORT=56379' 'Java Redis connection 6379 -> 56379'
expect_old_or_new "$CONFIG_ENV" '127.0.0.1:8000' '127.0.0.1:58000' 'Java Python URL 8000 -> 58000'
expect_old_or_new "$NGINX_CONFIG" 'listen 80;' 'listen 50080;' 'Nginx web 80 -> 50080'
expect_old_or_new "$NGINX_CONFIG" 'listen 8080;' 'listen 58080;' 'Nginx admin 8080 -> 58080'
expect_old_or_new "$NGINX_CONFIG" '127.0.0.1:6039' '127.0.0.1:56039' 'Nginx Java proxy 6039 -> 56039'

if [[ "$MODE" == dry-run ]]; then
  log 'SUCCESS: port migration preflight passed; no files or services were changed.'
  exit 0
fi

[[ $(id -u) == 0 ]] || die 'Run --apply with root privileges.'
LOG_DIR=/var/log/mjn-ai/ops
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/$(date +%Y%m%d-%H%M%S)-$$-change-ports.log"
exec > >(tee -a "$LOG_FILE") 2>&1
log "Operation log: $LOG_FILE"

BACKUP_DIR=/opt/mjn-ai/backups/ports-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"
cp -a "$MYSQL_CONFIG" "$BACKUP_DIR/my.cnf"
cp -a "$REDIS_CONFIG" "$BACKUP_DIR/redis.conf"
cp -a "$PYTHON_UNIT" "$BACKUP_DIR/mjn-ai-python.service"
cp -a "$CONFIG_ENV" "$BACKUP_DIR/config.env"
cp -a "$NGINX_CONFIG" "$BACKUP_DIR/nginx.conf"
BACKUP_READY=1
log "Configuration backup: $BACKUP_DIR"

bash "$AICTL" stop apps

if grep -Eq '^[[:space:]]*port[[:space:]]*=[[:space:]]*3306([[:space:]]|$)' "$MYSQL_CONFIG"; then
  sed -Ei 's/^[[:space:]]*port[[:space:]]*=[[:space:]]*3306[[:space:]]*$/port=51206/' "$MYSQL_CONFIG"
elif ! grep -Eq '^[[:space:]]*port[[:space:]]*=[[:space:]]*51206([[:space:]]|$)' "$MYSQL_CONFIG"; then
  sed -i '0,/^\[mysqld\]$/s//[mysqld]\nport=51206/' "$MYSQL_CONFIG"
fi

replace_old_or_keep_new "$REDIS_CONFIG" 'port 6379' 'port 56379' 'Redis port'
replace_old_or_keep_new "$PYTHON_UNIT" '--port 8000' '--port 58000' 'Python port'
replace_old_or_keep_new "$CONFIG_ENV" 'RUOYI_JAVA_PORT=6039' 'RUOYI_JAVA_PORT=56039' 'Java port'
replace_old_or_keep_new "$CONFIG_ENV" 'RUOYI_MYSQL_PORT=3306' 'RUOYI_MYSQL_PORT=51206' 'Java MySQL connection'
replace_old_or_keep_new "$CONFIG_ENV" 'RUOYI_REDIS_PORT=6379' 'RUOYI_REDIS_PORT=56379' 'Java Redis connection'
replace_old_or_keep_new "$CONFIG_ENV" '127.0.0.1:8000' '127.0.0.1:58000' 'Java Python URL'
replace_old_or_keep_new "$NGINX_CONFIG" 'listen 80;' 'listen 50080;' 'Nginx web port'
replace_old_or_keep_new "$NGINX_CONFIG" 'listen 8080;' 'listen 58080;' 'Nginx admin port'
replace_old_or_keep_new "$NGINX_CONFIG" '127.0.0.1:6039' '127.0.0.1:56039' 'Nginx Java proxy'

/opt/mjn-ai/nginx/sbin/nginx -t -c "$NGINX_CONFIG"
systemctl daemon-reload
bash "$AICTL" restart all

trap - ERR
log 'SUCCESS: all deployment ports were changed and all services passed health checks.'
log "Backup retained at $BACKUP_DIR"
log 'Open TCP 50080 and 58080 in the Alibaba Cloud security group if external access is required.'
