#!/usr/bin/env bash
# Read-only inventory. No service changes, pulls, builds or database writes.
set -euo pipefail
if [[ ${EUID} -ne 0 ]]; then
  echo "请使用 root 执行，以便读取实际服务路径。" >&2
  exit 1
fi
if [[ -x /opt/mjn-ai/tools/python3.14 ]]; then
  PYTHON=/opt/mjn-ai/tools/python3.14
elif command -v python3 >/dev/null 2>&1; then
  PYTHON=$(command -v python3)
else
  echo "未找到 Python 3；请发送 command -v python3 的输出。" >&2
  exit 1
fi
exec "$PYTHON" - <<'MJN_PYTHON'
"""Collect only allowlisted migration metadata; never print credentials."""
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
from urllib.parse import urlsplit

BASE = Path("/opt/mjn-ai")
UNITS = ("mysqld", "mjn-redis", "mjn-kafka", "mjn-ai-java", "mjn-ai-python", "mjn-nginx", "docker")
DB_VARIABLES = (
    "version", "port", "datadir", "socket", "lower_case_table_names",
    "character_set_server", "collation_server", "innodb_fast_shutdown",
    "innodb_data_home_dir", "innodb_data_file_path", "innodb_log_group_home_dir",
    "innodb_undo_directory", "log_bin_basename", "relay_log_basename",
    "keyring_file_data", "early_plugin_load", "default_table_encryption",
)
REDIS_FIELDS = ("dir", "dbfilename", "appendonly", "appendfilename", "appenddirname", "save")
MYSQL_CONFIG_FIELDS = set(DB_VARIABLES) | {"user", "log_error", "bind_address", "plugin_load", "plugin_load_add"}
KAFKA_FIELDS = {
    "process.roles", "node.id", "log.dirs", "metadata.log.dir",
    "listeners", "advertised.listeners", "controller.quorum.bootstrap.servers",
    "controller.quorum.voters", "controller.listener.names",
    "listener.security.protocol.map", "auto.create.topics.enable",
    "default.replication.factor", "min.insync.replicas",
}

def run(args, env=None, seconds=15):
    try:
        result = subprocess.run(args, env=env, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=seconds, check=False)
        return result.returncode, result.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        # Command stderr can contain credentials; do not include it in the report.
        return -1, ""

def read(path):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""

def env_file(path):
    # Parse literal KEY=VALUE assignments only; never source/evaluate a shell file.
    values = {}
    for line in read(path).splitlines():
        match = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$", line)
        if not match:
            continue
        try:
            tokens = shlex.split(match[2], comments=True, posix=True)
        except ValueError:
            continue
        if len(tokens) <= 1:
            values[match[1]] = tokens[0] if tokens else ""
    return values

def proc_env(pid):
    try:
        parts = Path("/proc/{}/environ".format(pid)).read_bytes().split(b"\0")
        return dict(p.decode("utf-8", "replace").split("=", 1) for p in parts if b"=" in p)
    except OSError:
        return {}

def endpoint(value):
    # Do not disclose credentials embedded in URLs/connection strings.
    return value if re.fullmatch(r"[A-Za-z0-9.\[\]:,_/-]+", value or "") else "[redacted]"

def paths_in(text):
    # Report release files only, never the full command or arbitrary argument values.
    return sorted(set(re.findall(r"/opt/mjn-ai/releases/[A-Za-z0-9._/-]+", text)))

def kv_config(path, allowed, separator="="):
    found = {}
    for raw in read(path).splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", ";", "[")):
            continue
        if separator == "=":
            key, sep, value = line.partition("=")
        else:
            parts = line.split(None, 1)
            key, sep, value = (parts[0], " ", parts[1]) if len(parts) == 2 else (parts[0], "", "")
        key = key.strip()
        if key in allowed and sep:
            # Allowed fields still never expose URI userinfo.
            found[key] = "[redacted]" if "@" in value else value.strip()
    return found

def service_inventory():
    services, private = {}, {}
    for name in UNITS:
        rc, text = run(["systemctl", "show", name, "--no-pager",
                        "-p", "LoadState", "-p", "ActiveState", "-p", "UnitFileState",
                        "-p", "MainPID", "-p", "WorkingDirectory", "-p", "ExecStart"])
        props = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
        pid = props.get("MainPID", "0")
        command = props.get("ExecStart", "")
        path = re.search(r"(?:^|[ {;])path=([^ ;}]+)", command)
        services[name] = {
            "loaded": props.get("LoadState", "unknown"),
            "state": props.get("ActiveState", "unknown"),
            "enabled": props.get("UnitFileState", "unknown"),
            "working_directory": props.get("WorkingDirectory", ""),
            "executable": path[1] if path else "",
            "resolved_executable": str(Path(path[1]).resolve()) if path else "",
            "release_paths": paths_in(command),
        }
        if rc:
            services[name]["query_ok"] = False
        private[name] = {"pid": pid, "environment": proc_env(pid)}
    return services, private

def connection_env(values):
    values = dict(values)
    aliases = {
        "SPRING_DATASOURCE_USERNAME": "RUOYI_MYSQL_USERNAME",
        "SPRING_DATASOURCE_PASSWORD": "RUOYI_MYSQL_PASSWORD",
        "SPRING_DATA_REDIS_HOST": "RUOYI_REDIS_HOST",
        "SPRING_DATA_REDIS_PORT": "RUOYI_REDIS_PORT",
        "SPRING_DATA_REDIS_USERNAME": "RUOYI_REDIS_USERNAME",
        "SPRING_DATA_REDIS_PASSWORD": "RUOYI_REDIS_PASSWORD",
    }
    for source, target in aliases.items():
        if source in values:
            values[target] = values[source]
    jdbc = values.get("SPRING_DATASOURCE_URL", "")
    if jdbc.startswith("jdbc:mysql://"):
        parsed = urlsplit(jdbc[5:])
        if parsed.hostname:
            values["RUOYI_MYSQL_HOST"] = parsed.hostname
        if parsed.port:
            values["RUOYI_MYSQL_PORT"] = str(parsed.port)
    return values

def mysql_inventory(app_env):
    app_env = connection_env(app_env)
    conf = kv_config("/etc/my.cnf", MYSQL_CONFIG_FIELDS)
    conf["include_directives"] = [
        line.strip() for line in read("/etc/my.cnf").splitlines()
        if line.strip().startswith(("!include ", "!includedir "))
    ]
    result = {"config_file": "/etc/my.cnf", "file_settings": conf, "live_query_ok": False}
    client = shutil.which("mysql")
    if not client:
        result["reason"] = "mysql client missing"
        return result
    child = os.environ.copy()
    child["MYSQL_PWD"] = app_env.get("RUOYI_MYSQL_PASSWORD", "")
    sql = "SHOW VARIABLES WHERE Variable_name IN ({});".format(
        ",".join("'" + key + "'" for key in DB_VARIABLES))
    rc, data = run([client, "--no-defaults", "--connect-timeout=5", "--protocol=TCP",
                    "--host=" + app_env.get("RUOYI_MYSQL_HOST", "127.0.0.1"),
                    "--port=" + app_env.get("RUOYI_MYSQL_PORT", "51206"),
                    "--user=" + app_env.get("RUOYI_MYSQL_USERNAME", "root"),
                    "--batch", "--raw", "--skip-column-names", "-e", sql], env=child)
    if rc:
        result["reason"] = "read-only connection failed; no password included"
        return result
    live = {}
    for line in data.splitlines():
        key, sep, value = line.partition("\t")
        if sep and key in DB_VARIABLES:
            live[key] = value
    result.update(live_query_ok=bool(live), live_settings=live)
    return result

def redis_inventory(app_env):
    app_env = connection_env(app_env)
    conf = kv_config("/etc/mjn-ai/redis.conf",
                     set(REDIS_FIELDS) | {"port", "bind", "include", "aclfile", "daemonize"}, " ")
    result = {"config_file": "/etc/mjn-ai/redis.conf", "file_settings": conf, "live_query_ok": False}
    candidates = (shutil.which("redis-cli"), "/opt/mjn-ai/redis/bin/redis-cli",
                  "/opt/mjn-ai/redis/src/redis-cli")
    client = next((p for p in candidates if p and Path(p).is_file()), None)
    if not client:
        return result
    child = os.environ.copy()
    child.pop("REDISCLI_AUTH", None)
    if app_env.get("RUOYI_REDIS_PASSWORD"):
        child["REDISCLI_AUTH"] = app_env["RUOYI_REDIS_PASSWORD"]
    args = [client, "-h", app_env.get("RUOYI_REDIS_HOST", "127.0.0.1"),
            "-p", app_env.get("RUOYI_REDIS_PORT", "56379"), "--raw"]
    if app_env.get("RUOYI_REDIS_USERNAME"):
        args += ["--user", app_env["RUOYI_REDIS_USERNAME"]]
    rc, data = run(args + ["CONFIG", "GET"] + list(REDIS_FIELDS), env=child)
    lines = data.splitlines()
    live = {}
    for i in range(0, len(lines) - 1, 2):
        if lines[i] in REDIS_FIELDS:
            live[lines[i]] = lines[i + 1]
    result.update(live_query_ok=rc == 0 and "dir" in live, live_settings=live)
    rc, data = run(args + ["INFO", "server"], env=child)
    if rc == 0:
        result["version"] = next((s.split(":", 1)[1].strip() for s in data.splitlines()
                                  if s.startswith("redis_version:")), "unknown")
    return result

def container_view(item):
    config = item.get("Config") or {}
    state = item.get("State") or {}
    labels = config.get("Labels") or {}
    return {
        "name": item.get("Name", "").lstrip("/"),
        "image": config.get("Image"), "image_id": item.get("Image"),
        "state": state.get("Status"),
        "restart_policy": (item.get("HostConfig") or {}).get("RestartPolicy"),
        "compose_project": labels.get("com.docker.compose.project"),
        "ports": (item.get("NetworkSettings") or {}).get("Ports"),
        "mounts": [{k: m.get(k) for k in ("Type", "Source", "Destination", "RW")}
                   for m in item.get("Mounts", [])],
    }

def main():
    if os.geteuid() != 0:
        raise SystemExit("root required")
    services, private = service_inventory()
    config = env_file("/etc/mjn-ai/config.env")
    app_env = dict(config)
    app_env.update(private["mjn-ai-java"]["environment"])
    report = {
        "created_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
        "scope": "inventory only; no service changes or database writes",
        "inspection_version": 2,
        "host": {"kernel": os.uname().release, "architecture": os.uname().machine,
                 "os": kv_config("/etc/os-release", {"NAME", "VERSION", "VERSION_ID"})},
        "services": services,
        "config": {"path": "/etc/mjn-ai/config.env", "keys": sorted(config)},
    }
    for key in ("RUOYI_JAVA_PORT", "MJN_PYTHON_PORT", "MJN_WEB_PORT", "MJN_ADMIN_PORT",
                "RUOYI_MYSQL_PORT", "RUOYI_REDIS_PORT", "AI_KAFKA_ENABLED",
                "AI_KAFKA_BOOTSTRAP_SERVERS", "AI_QDRANT_URL", "AI_PYTHON_BASE_URL"):
        if key in config:
            report["config"][key] = endpoint(config[key])
    disk = shutil.disk_usage(str(BASE))
    report["host"]["disk_gib"] = {"total": round(disk.total / 2**30, 1),
                                "free": round(disk.free / 2**30, 1)}
    rc, text = run(["df", "-PT", str(BASE)])
    report["host"]["filesystem"] = text.splitlines()[-1] if rc == 0 else "unknown"
    rc, text = run(["docker", "version", "--format", "{{.Server.Version}}"])
    report["docker"] = {"version": text if rc == 0 else "unavailable"}
    rc, text = run(["docker", "compose", "version", "--short"])
    report["docker"]["compose"] = text if rc == 0 else "unavailable"
    rc, text = run(["docker", "info", "--format", "{{.DockerRootDir}}|{{.Driver}}"])
    report["docker"]["data_root_and_driver"] = text if rc == 0 else "unknown"
    rc, names = run(["docker", "ps", "-a", "--format", "{{.Names}}"])
    report["docker"]["containers"] = []
    if rc == 0 and names:
        rc, data = run(["docker", "inspect"] + names.splitlines())
        if rc == 0:
            report["docker"]["containers"] = [container_view(item) for item in json.loads(data)]
    report["runtime_versions"] = {}
    for unit, flag in (("mjn-ai-java", "-version"), ("mjn-ai-python", "--version"),
                       ("mysqld", "--version"), ("mjn-redis", "--version"),
                       ("mjn-nginx", "-v")):
        binary = services[unit]["executable"]
        if binary and Path(binary).is_file():
            try:
                version = subprocess.run([binary, flag], text=True, stdout=subprocess.PIPE,
                                         stderr=subprocess.PIPE, timeout=10, check=False)
                report["runtime_versions"][unit] = (
                    (version.stdout + version.stderr).strip()
                    if version.returncode == 0 else "version query failed")
            except (OSError, subprocess.TimeoutExpired):
                report["runtime_versions"][unit] = "version query unavailable"
    report["mysql"] = mysql_inventory(app_env)
    report["redis"] = redis_inventory(app_env)
    report["kafka"] = {
        "config_file": "/etc/mjn-ai/kafka/server.properties",
        "settings": kv_config("/etc/mjn-ai/kafka/server.properties", KAFKA_FIELDS),
        "install_target": str((BASE / "kafka").resolve()),
    }
    rc, text = run(["/opt/mjn-ai/nginx/sbin/nginx", "-T", "-c",
                    "/etc/mjn-ai/nginx/nginx.conf"])
    # Never print full Nginx output: custom proxy headers may contain secrets.
    nginx_text = text if rc == 0 else read("/etc/mjn-ai/nginx/nginx.conf")
    report["nginx"] = {
        "config_file": "/etc/mjn-ai/nginx/nginx.conf", "config_test_ok": rc == 0,
        "roots": re.findall(r"(?m)^\s*root\s+([^;\n]+);", nginx_text),
        "listeners": re.findall(r"(?m)^\s*listen\s+([^;\n]+);", nginx_text),
        "include_files": re.findall(r"(?m)^\s*include\s+([^;\n]+);", nginx_text),
    }
    report["review_required"] = []
    for component in ("mysql", "redis"):
        if not report[component]["live_query_ok"]:
            report["review_required"].append(component + ": confirm actual data path and persistence")
    if report["docker"]["version"] == "unavailable":
        report["review_required"].append("Docker is not accessible")
    report["review_required"].append(
        "Validate candidate images on the server kernel before stopping existing services")
    # Exclusive creation preserves every earlier inventory, even within the same minute.
    output_dir = BASE / "packages"
    output_dir.mkdir(parents=True, exist_ok=True)
    stem = "docker-migration-check-" + datetime.datetime.now().strftime("%Y%m%d-%H%M")
    payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    for number in range(1, 10000):
        path = output_dir / (stem + ("" if number == 1 else "-" + str(number)) + ".json")
        try:
            fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            continue
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(payload)
        print(payload, end="")
        print("\n检查完成，报告：" + str(path), flush=True)
        print("服务未停止；数据库、配置、数据和旧版本未修改。")
        return
    raise SystemExit("Too many reports in the same minute")

if __name__ == "__main__":
    main()
MJN_PYTHON
