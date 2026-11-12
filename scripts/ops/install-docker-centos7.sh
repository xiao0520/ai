#!/usr/bin/env bash
set -Eeuo pipefail

DOCKER_VERSION="26.1.4"
CONTAINERD_VERSION="1.6.33"
BUILDX_VERSION="0.14.1"
COMPOSE_VERSION="2.27.1"
BASE_DIR="/opt/mjn-ai"
PACKAGE_DIR="$BASE_DIR/packages/docker-centos7-${DOCKER_VERSION}"
DATA_DIR="$BASE_DIR/docker-data"
RPM_BASE_URLS=(
  "https://download.docker.com/linux/centos/7/x86_64/stable/Packages"
  "https://mirrors.aliyun.com/docker-ce/linux/centos/7/x86_64/stable/Packages"
)
GPG_URLS=(
  "https://download.docker.com/linux/centos/gpg"
  "https://mirrors.aliyun.com/docker-ce/linux/centos/gpg"
)
DOCKER_GPG_FINGERPRINT="060A61C51B558A7F742B77AAC52FEB6B621E9F35"

RPM_NAMES=(
  "containerd.io-${CONTAINERD_VERSION}-3.1.el7.x86_64.rpm"
  "docker-ce-cli-${DOCKER_VERSION}-1.el7.x86_64.rpm"
  "docker-ce-${DOCKER_VERSION}-1.el7.x86_64.rpm"
  "docker-ce-rootless-extras-${DOCKER_VERSION}-1.el7.x86_64.rpm"
  "docker-buildx-plugin-${BUILDX_VERSION}-1.el7.x86_64.rpm"
  "docker-compose-plugin-${COMPOSE_VERSION}-1.el7.x86_64.rpm"
)

if [[ $EUID -ne 0 ]]; then
  echo "请使用 root 执行。" >&2
  exit 1
fi

download_file() {
  local target="$1"
  shift
  local url attempt
  for url in "$@"; do
    for attempt in 1 2 3; do
      echo "下载 $url（第 $attempt 次）"
      if curl -fL --connect-timeout 20 --max-time 600 "$url" -o "$target.part"; then
        mv "$target.part" "$target"
        return 0
      fi
      rm -f "$target.part"
      sleep 2
    done
  done
  echo "所有下载地址均失败：$target" >&2
  return 1
}

if ! grep -q '^VERSION_ID="\?7' /etc/os-release; then
  echo "此脚本只适用于 CentOS 7。" >&2
  exit 1
fi

if ! modprobe overlay; then
  echo "当前内核无法加载 overlay 模块，停止安装。" >&2
  exit 1
fi
grep -qw overlay /proc/filesystems || {
  echo "当前内核不支持 overlay 文件系统，停止安装。" >&2
  exit 1
}

MOUNT_TARGET="$(findmnt -no TARGET -T "$BASE_DIR")"
FILESYSTEM="$(findmnt -no FSTYPE -T "$BASE_DIR")"
if [[ "$FILESYSTEM" == "xfs" ]]; then
  if ! xfs_info "$MOUNT_TARGET" 2>/dev/null | grep -q 'ftype=1'; then
    echo "Docker 数据目录所在 XFS 文件系统不是 ftype=1，不能安全使用 overlay2。" >&2
    exit 1
  fi
fi

mkdir -p "$PACKAGE_DIR" "$DATA_DIR" /etc/docker /etc/modules-load.d /etc/sysctl.d
chmod 0711 "$DATA_DIR"

printf 'overlay\nbr_netfilter\n' > /etc/modules-load.d/mjn-docker.conf
modprobe br_netfilter || true

cat >/etc/sysctl.d/99-mjn-docker.conf <<'EOF'
net.ipv4.ip_forward = 1
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
EOF
sysctl --system >/dev/null

if ! rpm -q container-selinux >/dev/null 2>&1; then
  echo "安装 Docker 依赖 container-selinux"
  yum install -y container-selinux
fi

if [[ ! -s "$PACKAGE_DIR/docker-centos.gpg" ]]; then
  download_file "$PACKAGE_DIR/docker-centos.gpg" "${GPG_URLS[@]}"
fi
GPG_BIN="$(command -v gpg2 || command -v gpg || true)"
if [[ -z "$GPG_BIN" ]]; then
  echo "未找到 gpg/gpg2，无法验证 Docker GPG 公钥指纹。" >&2
  exit 1
fi
GPG_TEMP_DIR="$(mktemp -d)"
chmod 0700 "$GPG_TEMP_DIR"
if ! $GPG_BIN --homedir "$GPG_TEMP_DIR" --batch --import "$PACKAGE_DIR/docker-centos.gpg" >/dev/null 2>&1; then
  rm -rf "$GPG_TEMP_DIR"
  echo "Docker GPG 公钥文件无法导入，停止安装。" >&2
  exit 1
fi
ACTUAL_FINGERPRINT="$($GPG_BIN --homedir "$GPG_TEMP_DIR" --batch --with-colons --fingerprint | awk -F: '$1 == "fpr" {print $10; exit}')"
rm -rf "$GPG_TEMP_DIR"
if [[ "$ACTUAL_FINGERPRINT" != "$DOCKER_GPG_FINGERPRINT" ]]; then
  echo "Docker GPG 公钥指纹不匹配，停止安装。" >&2
  exit 1
fi
rpm --import "$PACKAGE_DIR/docker-centos.gpg"

RPM_PATHS=()
for rpm_name in "${RPM_NAMES[@]}"; do
  rpm_path="$PACKAGE_DIR/$rpm_name"
  if [[ ! -f "$rpm_path" ]]; then
    rpm_urls=()
    for rpm_base_url in "${RPM_BASE_URLS[@]}"; do
      rpm_urls+=("$rpm_base_url/$rpm_name")
    done
    download_file "$rpm_path" "${rpm_urls[@]}"
  fi
  rpmkeys --checksig "$rpm_path" | grep -Eq ' OK$' || {
    echo "RPM 签名校验失败：$rpm_path" >&2
    exit 1
  }
  RPM_PATHS+=("$rpm_path")
done

echo "安装固定版本 Docker CE ${DOCKER_VERSION}"
yum install -y "${RPM_PATHS[@]}"

if [[ -f /etc/docker/daemon.json ]]; then
  cp -p /etc/docker/daemon.json "/etc/docker/daemon.json.bak-$(date +%Y%m%d-%H%M)"
fi

cat >/etc/docker/daemon.json <<EOF
{
  "data-root": "$DATA_DIR",
  "storage-driver": "overlay2",
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "20m",
    "max-file": "5"
  },
  "live-restore": true
}
EOF

systemctl daemon-reload
systemctl enable --now docker

for _ in $(seq 1 30); do
  if timeout 5 docker info >/dev/null 2>&1; then
    echo "Docker 安装成功"
    docker version --format 'Engine: {{.Server.Version}}'
    docker compose version
    docker info --format 'Storage: {{.Driver}}; Root: {{.DockerRootDir}}'
    systemctl --no-pager --full status docker | sed -n '1,10p'
    exit 0
  fi
  printf '.'
  sleep 1
done
printf '\n'

echo "Docker 启动检查失败，请查看：journalctl -u docker -n 100 --no-pager" >&2
systemctl --no-pager --full status docker || true
exit 1
