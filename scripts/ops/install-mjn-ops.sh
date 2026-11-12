#!/usr/bin/env bash
set -Eeuo pipefail

[[ $(id -u) == 0 ]] || { echo 'Run with root privileges.' >&2; exit 1; }

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

CONFIG_ENV=${MJN_CONFIG_ENV:-/etc/mjn-ai/config.env}
OPS_FILES=(
  install-mjn-ops.sh mjn-aictl.sh change-ports.sh change-mysql-password.sh
  restart-java.sh restart-python.sh restart-frontend.sh reload-frontend.sh
  start-combo.sh restart-combo.sh stop-combo.sh
  restart-apps.sh restart-all.sh deploy-all.sh
  deploy-java.sh deploy-python.sh deploy-web.sh deploy-admin.sh
)

sha256sum -c mjn-ops-SHA256SUMS
for script in "${OPS_FILES[@]}"; do bash -n "$script"; done
[[ -f "$CONFIG_ENV" ]] || {
  echo "Missing production environment file: $CONFIG_ENV" >&2
  echo "Create it from $SCRIPT_DIR/config.env.example before installing the systemd drop-ins." >&2
  exit 1
}

chown root:root "${OPS_FILES[@]}" deploy-release.py config.env.example mjn-ops-SHA256SUMS
chmod 750 "${OPS_FILES[@]}"
chmod 640 deploy-release.py config.env.example mjn-ops-SHA256SUMS
chown root:root "$CONFIG_ENV"
chmod 600 "$CONFIG_ENV"

BACKUP_DIR=/opt/mjn-ai/backups/systemd-$(date +%Y%m%d-%H%M%S)
BACKUP_READY=0
TEMP_FILES=()

cleanup() {
  local file
  for file in "${TEMP_FILES[@]-}"; do rm -f "$file"; done
}
trap cleanup EXIT

install_dropin() {
  local service=$1 name=$2 content=$3
  local directory=/etc/systemd/system/${service}.service.d
  local target=$directory/$name
  local temporary
  temporary=$(mktemp /tmp/mjn-systemd-dropin.XXXXXX)
  TEMP_FILES+=("$temporary")
  printf '%s
' "$content" > "$temporary"
  install -d -m 755 "$directory"
  if [[ -e "$target" ]] && ! cmp -s "$temporary" "$target"; then
    if [[ "$BACKUP_READY" == 0 ]]; then
      install -d -m 700 "$BACKUP_DIR"
      BACKUP_READY=1
    fi
    cp -p "$target" "$BACKUP_DIR/${service}-${name}"
  fi
  install -o root -g root -m 644 "$temporary" "$target"
}

CONFIG_DROPIN='[Service]
EnvironmentFile=
EnvironmentFile=/etc/mjn-ai/config.env'
install_dropin mjn-ai-java 10-config-env.conf "$CONFIG_DROPIN"
install_dropin mjn-ai-python 10-config-env.conf "$CONFIG_DROPIN"
install_dropin mjn-ai-java 20-success-exit-status.conf '[Service]
SuccessExitStatus=143 SIGTERM'

systemctl daemon-reload
systemctl reset-failed mjn-ai-java
systemctl reset-failed mjn-ai-python
systemctl cat mjn-ai-java --no-pager | grep -F 'EnvironmentFile=/etc/mjn-ai/config.env'
systemctl cat mjn-ai-python --no-pager | grep -F 'EnvironmentFile=/etc/mjn-ai/config.env'

echo 'SUCCESS: operation scripts and shared environment configuration installed.'
if [[ "$BACKUP_READY" == 1 ]]; then echo "Previous drop-ins backed up to $BACKUP_DIR"; fi
echo 'No service was restarted.'