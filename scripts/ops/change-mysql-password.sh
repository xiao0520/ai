#!/usr/bin/env bash
# Change the MySQL password used by the deployed Java service.
set -Eeuo pipefail
umask 077

CONFIG_ENV=${MJN_CONFIG_ENV:-/etc/mjn-ai/config.env}
if [[ -r "$CONFIG_ENV" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$CONFIG_ENV"
  set +a
fi
MYSQL_BIN=${MJN_MYSQL_BIN:-mysql}
MYSQL_HOST=${RUOYI_MYSQL_HOST:-127.0.0.1}
MYSQL_PORT=${RUOYI_MYSQL_PORT:-51206}
MYSQL_USER=${RUOYI_MYSQL_USERNAME:-root}
AICTL=/opt/mjn-ai/tools/mjn-aictl.sh
PYTHON_BIN=/opt/mjn-ai/tools/python3.14
BACKUP_DIR=''
STAGE=preflight
TEMP_FILES=()

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
cleanup() {
  local file
  for file in "${TEMP_FILES[@]-}"; do
    [[ -e "$file" ]] || continue
    if command -v shred >/dev/null; then shred -u "$file" || rm -f "$file"; else rm -f "$file"; fi
  done
}
on_error() {
  local rc=$?
  trap - ERR
  echo
  log "FAILED at stage: $STAGE (exit $rc)"
  [[ -n "$BACKUP_DIR" ]] && log "Configuration backup: $BACKUP_DIR"
  log 'No automatic rollback was performed.'
  exit "$rc"
}
trap cleanup EXIT
trap on_error ERR

write_client_file() {
  local file=$1 password=$2 escaped
  escaped=${password//\\/\\\\}
  escaped=${escaped//\"/\\\"}
  printf '[client]\nuser="%s"\npassword="%s"\nhost="%s"\nport=%s\nprotocol=tcp\n' \
    "$MYSQL_USER" "$escaped" "$MYSQL_HOST" "$MYSQL_PORT" > "$file"
  chmod 600 "$file"
}

[[ $(id -u) == 0 ]] || die 'Run with root privileges.'
[[ -t 0 ]] || die 'Run this script directly in an interactive terminal.'
for file in "$CONFIG_ENV" "$AICTL" "$PYTHON_BIN"; do [[ -f "$file" ]] || die "Missing file: $file"; done
for command in "$MYSQL_BIN" mktemp chmod cp grep; do command -v "$command" >/dev/null || die "Missing command: $command"; done

printf 'Current MySQL password for %s: ' "$MYSQL_USER"
IFS= read -rs CURRENT_PASSWORD
echo
printf 'New MySQL password: '
IFS= read -rs NEW_PASSWORD
echo
printf 'Repeat new MySQL password: '
IFS= read -rs NEW_PASSWORD_CONFIRM
echo

[[ "$NEW_PASSWORD" == "$NEW_PASSWORD_CONFIRM" ]] || die 'The two new passwords do not match.'
[[ "$NEW_PASSWORD" != *"'"* && "$NEW_PASSWORD" != *'\'* ]] || die "Do not use single quote (') or backslash (\\)."
[[ "$NEW_PASSWORD" != "$CURRENT_PASSWORD" ]] || die 'The new password must differ from the current password.'

OLD_CNF=$(mktemp /tmp/mjn-mysql-old.XXXXXX)
NEW_CNF=$(mktemp /tmp/mjn-mysql-new.XXXXXX)
NEW_SECRET=$(mktemp /tmp/mjn-mysql-new-secret.XXXXXX)
ALTER_SQL=$(mktemp /tmp/mjn-mysql-alter.XXXXXX)
TEMP_FILES=("$OLD_CNF" "$NEW_CNF" "$NEW_SECRET" "$ALTER_SQL")
printf '%s' "$NEW_PASSWORD" > "$NEW_SECRET"
write_client_file "$OLD_CNF" "$CURRENT_PASSWORD"
write_client_file "$NEW_CNF" "$NEW_PASSWORD"
unset CURRENT_PASSWORD NEW_PASSWORD NEW_PASSWORD_CONFIRM

STAGE='verify current password'
CURRENT_ACCOUNT=$("$MYSQL_BIN" --defaults-extra-file="$OLD_CNF" --batch --skip-column-names \
  -e 'SELECT CURRENT_USER();')
[[ "$CURRENT_ACCOUNT" == *@* ]] || die 'Unable to determine the authenticated MySQL account.'
ACCOUNT_USER=${CURRENT_ACCOUNT%@*}
ACCOUNT_HOST=${CURRENT_ACCOUNT#*@}
[[ "$ACCOUNT_USER" == "$MYSQL_USER" ]] || die "Authenticated as unexpected account: $CURRENT_ACCOUNT"
[[ "$ACCOUNT_USER" != *"'"* && "$ACCOUNT_HOST" != *"'"* ]] || die 'Unsupported quote in MySQL account name.'

NEW_VALUE=$(cat "$NEW_SECRET")
printf "ALTER USER '%s'@'%s' IDENTIFIED BY '%s';\n" "$ACCOUNT_USER" "$ACCOUNT_HOST" "$NEW_VALUE" > "$ALTER_SQL"
unset NEW_VALUE

BACKUP_DIR=/opt/mjn-ai/backups/mysql-password-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"
cp -a "$CONFIG_ENV" "$BACKUP_DIR/config.env"
log "Configuration backup: $BACKUP_DIR"

STAGE='change MySQL password'
log "Changing password for MySQL account: $CURRENT_ACCOUNT"
"$MYSQL_BIN" --defaults-extra-file="$OLD_CNF" < "$ALTER_SQL"

STAGE='verify new MySQL password'
"$MYSQL_BIN" --defaults-extra-file="$NEW_CNF" --batch --skip-column-names -e 'SELECT 1;' | grep -qx '1'
log 'New MySQL password verified.'

STAGE='update shared environment configuration'
"$PYTHON_BIN" - "$CONFIG_ENV" "$NEW_SECRET" <<'PY'
from pathlib import Path
import re
import sys

config_path = Path(sys.argv[1])
password = Path(sys.argv[2]).read_text(encoding="utf-8")
lines = config_path.read_text(encoding="utf-8").splitlines(keepends=True)
matches = [
    index
    for index, line in enumerate(lines)
    if re.match(r"^RUOYI_MYSQL_PASSWORD=", line)
]
if len(matches) != 1:
    raise SystemExit("RUOYI_MYSQL_PASSWORD must occur exactly once in config.env")
index = matches[0]
ending = "\n" if lines[index].endswith("\n") else ""
lines[index] = f"RUOYI_MYSQL_PASSWORD='{password}'{ending}"
config_path.write_text("".join(lines), encoding="utf-8")
PY
chmod --reference="$BACKUP_DIR/config.env" "$CONFIG_ENV"
chown --reference="$BACKUP_DIR/config.env" "$CONFIG_ENV"

STAGE='restart Java'
bash "$AICTL" restart java

STAGE='complete'
trap - ERR
log 'SUCCESS: MySQL password and /etc/mjn-ai/config.env were updated.'
log "Configuration backup retained at $BACKUP_DIR"
