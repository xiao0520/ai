#!/usr/bin/env bash
# Load offline images and check container compatibility without stopping live services.
set -Eeuo pipefail

PACKAGES=/opt/mjn-ai/packages/docker-migration
IMAGES=(
  centos-7.9.2009.tar
  mysql-8.0.46.tar
  redis-7.2.16.tar
  nginx-1.30.4.tar
  kafka-4.3.1.tar
)
MYSQL_CHECK=mjn-migrate-preflight-mysql
REDIS_CHECK=mjn-migrate-preflight-redis

fail() { echo "预检失败：$*" >&2; exit 1; }
cleanup() {
  docker rm -f "$MYSQL_CHECK" "$REDIS_CHECK" >/dev/null 2>&1 || true
}

[[ ${EUID} -eq 0 ]] || fail '请使用 root 执行。'
command -v docker >/dev/null || fail '未安装 Docker。'
docker info >/dev/null 2>&1 || fail 'Docker 服务未运行。'
[[ $(uname -m) == x86_64 ]] || fail '离线镜像仅适用于 x86_64。'
[[ -d "$PACKAGES" ]] || fail "缺少目录 $PACKAGES"
[[ -s "$PACKAGES/SHA256SUMS" ]] || fail '缺少 SHA256SUMS。'
for image in "${IMAGES[@]}"; do
  [[ -s "$PACKAGES/$image" ]] || fail "缺少离线镜像 $image"
done
if docker ps -a --format '{{.Names}}' | grep -Eq "^(${MYSQL_CHECK}|${REDIS_CHECK})$"; then
  fail '存在同名预检容器，请先检查，避免覆盖。'
fi

echo '校验离线镜像文件'
(cd "$PACKAGES" && sha256sum -c SHA256SUMS)
echo '导入离线镜像（不会重启现有服务）'
for image in "${IMAGES[@]}"; do
  docker load -i "$PACKAGES/$image" | tail -n 2
done

CENTOS=public.ecr.aws/docker/library/centos:7.9.2009
MYSQL=public.ecr.aws/docker/library/mysql:8.0.46
REDIS=public.ecr.aws/docker/library/redis:7.2.16-bookworm
NGINX=public.ecr.aws/docker/library/nginx:1.30.4
KAFKA=apache/kafka:4.3.1
for image in "$CENTOS" "$MYSQL" "$REDIS" "$NGINX" "$KAFKA"; do
  [[ $(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}') == linux/amd64 ]] || fail "镜像架构不符：$image"
done

echo '检查 Java 和 Python 能否在 CentOS 7 容器内运行'
[[ -x /usr/lib/jvm/java-17-amazon-corretto/bin/java ]] || fail '未找到盘点报告中的 Java 路径。'
[[ -x /opt/mjn-ai/python/cpython-3.14.7-linux-x86_64-gnu/bin/python3.14 ]] || fail '未找到盘点报告中的 Python 路径。'
docker run --rm --network none --mount type=bind,src=/usr/lib/jvm,dst=/usr/lib/jvm,readonly \
  --entrypoint /usr/lib/jvm/java-17-amazon-corretto/bin/java "$CENTOS" -version
docker run --rm --network none --mount type=bind,src=/opt/mjn-ai/python,dst=/opt/mjn-ai/python,readonly \
  --entrypoint /opt/mjn-ai/python/cpython-3.14.7-linux-x86_64-gnu/bin/python3.14 "$CENTOS" --version

echo '检查 Nginx 和 Kafka 镜像运行时'
docker run --rm --network none --entrypoint nginx "$NGINX" -t
docker run --rm --network none --entrypoint /bin/bash "$KAFKA" -c 'java -version'

trap cleanup EXIT
echo '启动隔离的临时 Redis（不使用现有数据和端口）'
docker run -d --rm --name "$REDIS_CHECK" --network none "$REDIS" \
  redis-server --bind 127.0.0.1 --save '' --appendonly no >/dev/null
for _ in $(seq 1 30); do
  if [[ $(docker exec "$REDIS_CHECK" redis-cli ping 2>/dev/null || true) == PONG ]]; then
    break
  fi
  sleep 1
done
[[ $(docker exec "$REDIS_CHECK" redis-cli ping 2>/dev/null || true) == PONG ]] || fail '临时 Redis 未启动。'

echo '启动隔离的临时 MySQL（数据仅在临时内存文件系统中）'
docker run -d --rm --name "$MYSQL_CHECK" --network none \
  --tmpfs /var/lib/mysql:rw,size=1536m \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes "$MYSQL" >/dev/null
MYSQL_READY=0
for _ in $(seq 1 90); do
  if docker exec "$MYSQL_CHECK" mysqladmin ping -uroot --silent >/dev/null 2>&1; then
    MYSQL_READY=1
    break
  fi
  if ! docker inspect -f '{{.State.Running}}' "$MYSQL_CHECK" 2>/dev/null | grep -qx true; then
    break
  fi
  sleep 1
done
if [[ $MYSQL_READY -ne 1 ]]; then
  docker logs --tail 40 "$MYSQL_CHECK" >&2 || true
  fail '临时 MySQL 未启动。'
fi

echo '预检通过：镜像和基本运行时可启动；现有 systemd 服务、数据库、数据和发布版本均未修改。'
echo '下一步仍需检查实际配置和数据迁移；此脚本不会执行正式切换。'
