#!/usr/bin/env bash
# One-command release deployment. Old releases and dependencies are retained.
set -Eeuo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PYTHON=/opt/mjn-ai/tools/python3.14
[[ -x "$PYTHON" ]] || { echo "Missing Python runtime: $PYTHON" >&2; exit 1; }
if [[ ${1:-} == --list || ${1:-} == --help || ${1:-} == -h ]]; then
  exec "$PYTHON" "$SCRIPT_DIR/deploy-release.py" "$@"
fi
[[ $(id -u) == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
umask 027
mkdir -p /var/log/mjn-ai/ops
LOG=/var/log/mjn-ai/ops/deploy-$(date +%Y%m%d-%H%M)-$$.log
"$PYTHON" -u "$SCRIPT_DIR/deploy-release.py" "$@" 2>&1 | tee "$LOG"
