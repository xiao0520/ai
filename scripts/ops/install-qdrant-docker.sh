#!/usr/bin/env bash
set -Eeuo pipefail

QDRANT_VERSION="1.19.1"
HTTP_PORT="56333"
GRPC_PORT="56334"
P2P_PORT="56335"
BASE_DIR="/opt/mjn-ai"
APP_DIR="$BASE_DIR/qdrant"
DATA_DIR="$BASE_DIR/qdrant-data"
CONFIG_DIR="/etc/mjn-ai/qdrant"
ENV_FILE="$CONFIG_DIR/qdrant.env"
COMPOSE_FILE="$APP_DIR/docker-compose.yml"
PROJECT_NAME="mjn-ai-qdrant"
CONTAINER_NAME="mjn-qdrant"
IMAGE="ghcr.io/qdrant/qdrant/qdrant:v${QDRANT_VERSION}"
IMAGE_ARCHIVE="$BASE_DIR/packages/qdrant-v${QDRANT_VERSION}-linux-amd64.tar"

if [[ $EUID -ne 0 ]]; then
  echo "请使用 root 执行。" >&2
  exit 1
fi

if ! systemctl is-active --quiet docker; then
  echo "Docker 未运行，请先启动 Docker。" >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "未找到 docker compose 插件。" >&2
  exit 1
fi

for port in "$HTTP_PORT" "$GRPC_PORT" "$P2P_PORT"; do
  if ss -lnt "sport = :$port" 2>/dev/null | grep -q LISTEN; then
    if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
      echo "端口 $port 已被其他进程占用，停止安装。" >&2
      exit 1
    fi
  fi
done

mkdir -p "$APP_DIR" "$DATA_DIR" "$CONFIG_DIR"
chmod 0700 "$CONFIG_DIR"

if [[ ! -s "$ENV_FILE" ]]; then
  umask 077
  QDRANT_API_KEY="$(openssl rand -hex 32)"
  cat >"$ENV_FILE" <<EOF
QDRANT__SERVICE__API_KEY=${QDRANT_API_KEY}
EOF
fi
chmod 0600 "$ENV_FILE"

if [[ -f "$COMPOSE_FILE" ]]; then
  cp -p "$COMPOSE_FILE" "$COMPOSE_FILE.bak-$(date +%Y%m%d-%H%M)"
fi

cat >"$COMPOSE_FILE" <<EOF
services:
  qdrant:
    image: ${IMAGE}
    container_name: ${CONTAINER_NAME}
    restart: unless-stopped
    env_file:
      - ${ENV_FILE}
    ports:
      - "127.0.0.1:${HTTP_PORT}:6333"
      - "127.0.0.1:${GRPC_PORT}:6334"
      - "127.0.0.1:${P2P_PORT}:6335"
    volumes:
      - ${DATA_DIR}:/qdrant/storage
    ulimits:
      nofile:
        soft: 65535
        hard: 65535
    stop_grace_period: 60s
EOF

chmod 0644 "$COMPOSE_FILE"

if [[ -f "$IMAGE_ARCHIVE" ]]; then
  echo "导入 Qdrant v${QDRANT_VERSION} 离线镜像"
  docker load -i "$IMAGE_ARCHIVE"
elif ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "未找到离线镜像，尝试在线拉取 Qdrant v${QDRANT_VERSION}"
  docker pull "$IMAGE"
fi

docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" up -d qdrant

QDRANT_API_KEY="$(sed -n 's/^QDRANT__SERVICE__API_KEY=//p' "$ENV_FILE")"
if [[ -z "$QDRANT_API_KEY" ]]; then
  echo "Qdrant API Key 配置为空，停止检查。" >&2
  exit 1
fi

echo "等待 Qdrant 启动"
for _ in $(seq 1 40); do
  if curl -fsS --max-time 3 -H "api-key: $QDRANT_API_KEY" "http://127.0.0.1:${HTTP_PORT}/collections" >/dev/null 2>&1; then
    echo "Qdrant v${QDRANT_VERSION} 已启动：http://127.0.0.1:${HTTP_PORT}"
    docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" ps
    ss -lntp | grep -E ":${HTTP_PORT}|:${GRPC_PORT}|:${P2P_PORT}" || true
    echo "API Key 保存在：$ENV_FILE"
    exit 0
  fi
  printf '.'
  sleep 2
done
printf '\n'

echo "Qdrant 启动检查失败，请查看以下日志：" >&2
docker logs --tail 150 "$CONTAINER_NAME" || true
exit 1
