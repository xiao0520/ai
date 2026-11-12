#!/usr/bin/env bash
set -Eeuo pipefail
PACKAGES=/opt/mjn-ai/packages
TOOLS=/opt/mjn-ai/tools
cd "$PACKAGES"
sha256sum -c mjn-python-runtime.tar.gz.sha256
docker load -i mjn-python-runtime.tar.gz
"$TOOLS/python3.14" "$TOOLS/upgrade-python-runtime.py"
