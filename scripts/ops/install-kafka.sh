#!/usr/bin/env bash
set -Eeuo pipefail

KAFKA_VERSION="4.3.1"
SCALA_VERSION="2.13"
CLIENT_PORT="59092"
CONTROLLER_PORT="59093"
BASE_DIR="/opt/mjn-ai"
PACKAGE_DIR="$BASE_DIR/packages"
ARCHIVE="kafka_${SCALA_VERSION}-${KAFKA_VERSION}.tgz"
ARCHIVE_PATH="$PACKAGE_DIR/$ARCHIVE"
INSTALL_DIR="$BASE_DIR/kafka_${SCALA_VERSION}-${KAFKA_VERSION}"
CURRENT_LINK="$BASE_DIR/kafka"
DATA_DIR="$BASE_DIR/data/kafka"
LOG_DIR="/var/log/mjn-ai/kafka"
CONFIG_DIR="/etc/mjn-ai/kafka"
CONFIG_FILE="$CONFIG_DIR/server.properties"
SERVICE_FILE="/etc/systemd/system/mjn-kafka.service"
DOWNLOAD_URL="https://downloads.apache.org/kafka/${KAFKA_VERSION}/${ARCHIVE}"
EXPECTED_SHA512="c7d7b2318cb51aa0c61d3246a51c349210073c5c9b754947ef965a439f2f939e8600f204e134a75ac31faf3829c9370960ef7c6a9886c8a1dbf0339a21f4c54c"

if [[ $EUID -ne 0 ]]; then
  echo "请使用 root 执行。" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 Java，Kafka ${KAFKA_VERSION} 需要 Java 17 或更高版本。" >&2
  exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
  echo "当前 Java 版本不满足要求：${JAVA_MAJOR:-未知}" >&2
  exit 1
fi

for port in "$CLIENT_PORT" "$CONTROLLER_PORT"; do
  if ss -lnt "sport = :$port" 2>/dev/null | grep -q LISTEN; then
    if ! systemctl is-active --quiet mjn-kafka 2>/dev/null; then
      echo "端口 $port 已被其他进程占用，停止安装。" >&2
      exit 1
    fi
  fi
done

mkdir -p "$PACKAGE_DIR" "$DATA_DIR" "$LOG_DIR" "$CONFIG_DIR"

if [[ ! -f "$ARCHIVE_PATH" ]]; then
  echo "下载 $ARCHIVE"
  curl -fL --retry 3 --connect-timeout 20 "$DOWNLOAD_URL" -o "$ARCHIVE_PATH.part"
  mv "$ARCHIVE_PATH.part" "$ARCHIVE_PATH"
fi

ACTUAL_SHA512="$(sha512sum "$ARCHIVE_PATH" | awk '{print $1}')"
if [[ "$ACTUAL_SHA512" != "$EXPECTED_SHA512" ]]; then
  echo "Kafka 安装包 SHA-512 校验失败：$ARCHIVE_PATH" >&2
  exit 1
fi
echo "Kafka 安装包校验通过"

if [[ ! -d "$INSTALL_DIR" ]]; then
  tar -xzf "$ARCHIVE_PATH" -C "$BASE_DIR"
fi

if [[ ! -x "$INSTALL_DIR/bin/kafka-storage.sh" ]]; then
  echo "Kafka 安装目录不完整：$INSTALL_DIR" >&2
  exit 1
fi

if [[ -e "$CURRENT_LINK" && ! -L "$CURRENT_LINK" ]]; then
  echo "$CURRENT_LINK 已存在且不是软链接，停止安装，避免覆盖原目录。" >&2
  exit 1
fi
ln -sfn "$INSTALL_DIR" "$CURRENT_LINK"

if ! id mjn-kafka >/dev/null 2>&1; then
  useradd --system --home-dir "$BASE_DIR" --shell /sbin/nologin mjn-kafka
fi

if [[ -f "$CONFIG_FILE" ]]; then
  cp -p "$CONFIG_FILE" "$CONFIG_FILE.bak-$(date +%Y%m%d-%H%M)"
fi

cat >"$CONFIG_FILE" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=127.0.0.1:${CONTROLLER_PORT}
listeners=PLAINTEXT://127.0.0.1:${CLIENT_PORT},CONTROLLER://127.0.0.1:${CONTROLLER_PORT}
advertised.listeners=PLAINTEXT://127.0.0.1:${CLIENT_PORT}
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
log.dirs=${DATA_DIR}
num.network.threads=3
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600
num.partitions=3
num.recovery.threads.per.data.dir=1
default.replication.factor=1
min.insync.replicas=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
auto.create.topics.enable=false
log.retention.hours=168
log.segment.bytes=1073741824
log.retention.check.interval.ms=300000
EOF

chown -R mjn-kafka:mjn-kafka "$DATA_DIR" "$LOG_DIR" "$CONFIG_DIR"
chmod 0750 "$DATA_DIR" "$LOG_DIR" "$CONFIG_DIR"
chmod 0640 "$CONFIG_FILE"

if [[ ! -f "$DATA_DIR/meta.properties" ]]; then
  CLUSTER_ID="$($CURRENT_LINK/bin/kafka-storage.sh random-uuid)"
  runuser -u mjn-kafka -- "$CURRENT_LINK/bin/kafka-storage.sh" format --standalone -t "$CLUSTER_ID" -c "$CONFIG_FILE"
fi

cat >"$SERVICE_FILE" <<EOF
[Unit]
Description=MJN-AI Kafka Broker
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=mjn-kafka
Group=mjn-kafka
Environment="KAFKA_HEAP_OPTS=-Xms1g -Xmx1g"
Environment="LOG_DIR=${LOG_DIR}"
ExecStart=${CURRENT_LINK}/bin/kafka-server-start.sh ${CONFIG_FILE}
ExecStop=${CURRENT_LINK}/bin/kafka-server-stop.sh
Restart=on-failure
RestartSec=5
TimeoutStopSec=120
SuccessExitStatus=143
LimitNOFILE=100000

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now mjn-kafka

echo "等待 Kafka 启动"
for _ in $(seq 1 30); do
  if timeout 5 "$CURRENT_LINK/bin/kafka-topics.sh" --bootstrap-server "127.0.0.1:${CLIENT_PORT}" --list >/dev/null 2>&1; then
    echo "Kafka ${KAFKA_VERSION} 已启动：127.0.0.1:${CLIENT_PORT}"
    systemctl --no-pager --full status mjn-kafka | sed -n '1,12p'
    ss -lntp | grep -E ":${CLIENT_PORT}|:${CONTROLLER_PORT}" || true
    exit 0
  fi
  printf '.'
  sleep 1
done
printf '\n'

echo "Kafka 启动检查失败，请查看：journalctl -u mjn-kafka -n 100 --no-pager" >&2
systemctl --no-pager --full status mjn-kafka || true
exit 1
