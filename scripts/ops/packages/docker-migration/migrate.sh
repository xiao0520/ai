#!/usr/bin/env bash
set -euo pipefail
exec /opt/mjn-ai/tools/python3.14 - "$0" "$@" <<'MJN_DOCKER_MIGRATE_PY'
import sys, types
from pathlib import Path
_SCRIPT_PATH = Path(sys.argv.pop(1)).resolve()
_RUNTIME_SOURCE = '"""Start an existing application with refreshed literal environment values."""\nimport json\nimport os\nimport re\nimport socket\nimport subprocess\nimport sys\nimport time\nfrom pathlib import Path\n\n\ndef parse_env(text):\n    """Support literal one-line systemd assignments; never evaluate shell code."""\n    values = {}\n    for number, raw in enumerate(text.splitlines(), 1):\n        line = raw.strip()\n        if not line or line.startswith(("#", ";")):\n            continue\n        match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)\\s*=(.*)", line)\n        if not match:\n            raise ValueError(f"config.env line {number}: expected KEY=VALUE")\n        key, value = match[1], match[2].strip()\n        if value.startswith("\'"):\n            if len(value) < 2 or not value.endswith("\'") or "\'" in value[1:-1]:\n                raise ValueError(f"config.env line {number}: unsupported quoting")\n            value = value[1:-1]\n        elif value.startswith(\'"\'):\n            if len(value) < 2 or not value.endswith(\'"\'):\n                raise ValueError(f"config.env line {number}: incomplete quotes")\n            inner, out, i = value[1:-1], [], 0\n            while i < len(inner):\n                if inner[i] == \'"\':\n                    raise ValueError(f"config.env line {number}: unescaped quote")\n                if inner[i] == "\\\\":\n                    i += 1\n                    if i >= len(inner):\n                        raise ValueError(f"config.env line {number}: trailing escape")\n                    if inner[i] not in (\'\\\\\', \'"\', \'$\', chr(96)):\n                        out.append("\\\\")\n                out.append(inner[i])\n                i += 1\n            value = "".join(out)\n        elif any(c in value for c in ("\'", \'"\', "\\\\")):\n            raise ValueError(f"config.env line {number}: quote the whole value")\n        if "\\x00" in value:\n            raise ValueError(f"config.env line {number}: invalid null byte")\n        values[key] = value\n    return values\n\n\ndef wait_for_ports(ports, seconds=120):\n    deadline = time.monotonic() + seconds\n    for port in ports:\n        while True:\n            try:\n                with socket.create_connection(("127.0.0.1", port), timeout=2):\n                    break\n            except OSError:\n                if time.monotonic() >= deadline:\n                    raise RuntimeError(f"Dependency port {port} is not ready")\n                time.sleep(2)\n\n\ndef main():\n    meta = json.loads(Path(sys.argv[1]).read_text())\n    env = dict(meta["environment"])\n    env.update(parse_env(Path(meta["environment_file"]).read_text()))\n    env["PYTHONDONTWRITEBYTECODE"] = "1"\n    for key in ("NOTIFY_SOCKET", "LISTEN_PID", "LISTEN_FDS", "JOURNAL_STREAM",\n                "INVOCATION_ID", "MAINPID"):\n        env.pop(key, None)\n    os.setgroups(meta["groups"])\n    os.setgid(meta["gid"])\n    os.setuid(meta["uid"])\n    os.chdir(meta["cwd"])\n    command = meta["command"]\n    if "--check" in sys.argv[2:]:\n        if not os.access(command[0], os.X_OK):\n            raise RuntimeError("Application executable is not accessible")\n        probe = [command[0], "-version"] if meta["name"] == "java" else [\n            command[0], "-c", "import fastapi, uvicorn; print(\'Python dependencies OK\')"]\n        subprocess.run(probe, env=env, check=True)\n        return\n    wait_for_ports(meta["wait_ports"])\n    os.execve(command[0], command, env)\n\n\nif __name__ == "__main__":\n    try:\n        main()\n    except Exception as exc:  # noqa: BLE001 - PID 1 must exit on any startup failure.\n        print(f"Application startup failed: {exc}", file=sys.stderr)\n        sys.exit(1)\n\n'
_runtime_module = types.ModuleType('runtime')
exec(compile(_RUNTIME_SOURCE, 'runtime.py', 'exec'), _runtime_module.__dict__)
sys.modules['runtime'] = _runtime_module
"""Migrate the inventoried native services to Docker, retaining data and binaries."""
import argparse
import datetime
import fcntl
import grp
import json
import os
import pwd
import re
import shlex
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import runtime

BASE = Path("/opt/mjn-ai")
ROOT = BASE / "docker"
ACTIVE = ROOT / "active"
PROJECT = "mjn-ai"
ENV_FILE = Path("/etc/mjn-ai/config.env")
UNITS = {
    "mysql": "mysqld", "redis": "mjn-redis", "kafka": "mjn-kafka",
    "python": "mjn-ai-python", "java": "mjn-ai-java", "nginx": "mjn-nginx",
}
NAMES = {key: ("mjn-mysql" if key == "mysql" else value) for key, value in UNITS.items()}
IMAGES = {
    "mysql": "public.ecr.aws/docker/library/mysql:8.0.46",
    "redis": "public.ecr.aws/docker/library/redis:7.2.16-bookworm",
    "kafka": "apache/kafka:4.3.1",
    "nginx": "public.ecr.aws/docker/library/nginx:1.30.4",
    "java": "public.ecr.aws/docker/library/centos:7.9.2009",
    "python": "public.ecr.aws/docker/library/centos:7.9.2009",
}
DATA = {
    "mysql": Path("/var/lib/mysql"), "redis": Path("/var/lib/mjn-redis"),
    "kafka": BASE / "data/kafka", "application": Path("/var/lib/mjn-ai"),
}
APP_LOG_ROOT = Path("/var/log/mjn-ai")
PORTS = (51206, 56379, 59092, 59093, 58000, 56039, 50080, 58080)
PYTHON = str((BASE / "tools/python3.14").resolve())
GUARD = "[Unit]\nConditionPathExists=!/opt/mjn-ai/docker/active\n"
ORDER = ("mysql", "redis", "kafka", "python", "java", "nginx")


def log(message):
    print("[{}] {}".format(datetime.datetime.now(datetime.UTC).astimezone().strftime("%Y-%m-%d %H:%M"), message),
          flush=True)


def run(args, capture=False, env=None, timeout=180, check=True):
    result = subprocess.run([str(x) for x in args], env=env, text=True,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None, timeout=timeout, check=False)
    if check and result.returncode:
        # Never echo command arguments or captured stderr, which may contain secrets.
        raise RuntimeError(f"{Path(str(args[0])).name} 执行失败，退出码 {result.returncode}")
    return result.stdout.strip() if capture else result.returncode


def props(unit):
    result = run(["systemctl", "show", unit, "--no-pager", "-p", "MainPID",
                  "-p", "ActiveState", "-p", "User", "-p", "Group",
                  "-p", "WorkingDirectory", "-p", "Environment",
                  "-p", "EnvironmentFiles", "-p", "UnitFileState"], capture=True)
    return dict(line.split("=", 1) for line in result.splitlines() if "=" in line)


def process_info(unit):
    info = props(unit)
    if info.get("ActiveState") != "active" or not info.get("MainPID", "0").isdigit():
        raise RuntimeError(f"{unit} 必须处于 active 状态，才能捕获当前配置")
    pid = int(info["MainPID"])
    if not pid:
        raise RuntimeError(f"{unit} 没有运行中的进程")
    command = [x.decode() for x in Path(f"/proc/{pid}/cmdline").read_bytes()
               .split(b"\0") if x]
    env = dict(x.decode().split("=", 1) for x in
               Path(f"/proc/{pid}/environ").read_bytes().split(b"\0") if b"=" in x)
    user = pwd.getpwnam(info.get("User") or "root")
    group = grp.getgrnam(info["Group"]).gr_gid if info.get("Group") else user.pw_gid
    return info, command, env, user.pw_uid, group


def write_json(path, value):
    temporary = path.with_name(path.name + ".new")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.chmod(0o600)
    os.replace(temporary, path)


def unique(parent, prefix):
    parent.mkdir(parents=True, exist_ok=True)
    stem = prefix + datetime.datetime.now(datetime.UTC).astimezone().strftime("%Y%m%d-%H%M")
    for index in range(1, 10000):
        path = parent / (stem if index == 1 else stem + "-" + str(index))
        try:
            path.mkdir(mode=0o700)
            return path
        except FileExistsError:
            pass
    raise RuntimeError("本分钟的版本目录过多")


def preserve(source, target):
    source, target = Path(source), Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    run(["cp", "-a", "--", source, target], timeout=300)


def read_active():
    if not ACTIVE.is_file():
        raise RuntimeError("当前没有 Docker 迁移处于启用状态")
    path = Path(ACTIVE.read_text().strip())
    if path.is_symlink() or path.resolve().parent != (ROOT / "runs").resolve():
        raise RuntimeError("无效的迁移状态路径")
    return path


def compose(path, *args, capture=False, timeout=180):
    # This tool runs from a Python heredoc; Compose default TTY allocation
    # fails when stdin is not a terminal. -T works with Compose 2.27.1.
    options = ("-T",) if args and args[0] == "run" else ()
    return run(["docker", "compose", "-p", PROJECT, "-f", path / "compose.json",
                *args[:1], *options, *args[1:]], capture=capture, timeout=timeout)


def inspect(name):
    raw = run(["docker", "inspect", name], capture=True, check=False)
    try:
        return json.loads(raw)[0] if raw else None
    except (ValueError, IndexError):
        return None


def require_owned_containers():
    for name in NAMES.values():
        item = inspect(name)
        if item:
            labels = item["Config"].get("Labels") or {}
            if labels.get("com.docker.compose.project") != PROJECT:
                raise RuntimeError("容器名已被其他项目使用：" + name)
            if item["State"]["Running"]:
                raise RuntimeError("容器已经运行，请先使用 status 检查：" + name)


def account_files(path, service, image_name, account, uid, gid, home=None):
    directory = path / "accounts" / service
    directory.mkdir(parents=True)
    for filename, column in (("passwd", 2), ("group", 2)):
        text = run(["docker", "run", "--pull=never", "--platform", "linux/amd64", "--rm", "--network", "none", "--entrypoint",
                    "cat", image_name, "/etc/" + filename], capture=True)
        rows = [line.split(":") for line in text.splitlines()]
        target_id = uid if filename == "passwd" else gid
        for row in rows:
            if row[0] != account and row[column] == str(target_id):
                raise RuntimeError(f"{service} 的 {filename} 编号与镜像用户冲突")
        selected = next((row for row in rows if row[0] == account), None)
        if selected is None:
            selected = ([account, "x", str(uid), str(gid), "", home or "/tmp", "/sbin/nologin"]
                        if filename == "passwd" else [account, "x", str(gid), ""])
            rows.append(selected)
        else:
            selected[2] = str(target_id)
            if filename == "passwd":
                selected[3] = str(gid)
                if home:
                    selected[5] = home
        output = directory / filename
        output.write_text("\n".join(":".join(row) for row in rows) + "\n")
        output.chmod(0o644)


def mount(source, target=None, readonly=False):
    source = Path(source)
    if not source.exists():
        raise RuntimeError("挂载源不存在：" + str(source))
    return {"type": "bind", "source": str(source),
            "target": str(target or source), "read_only": readonly,
            "bind": {"create_host_path": False}}


def basic_service(service, volumes, uid=None, gid=None):
    value = {
        "image": IMAGES[service], "platform": "linux/amd64", "pull_policy": "never",
        "container_name": NAMES[service], "network_mode": "host",
        "restart": "unless-stopped", "stop_grace_period": "120s",
        "hostname": socket.gethostname(),
        "extra_hosts": [socket.gethostname() + ":127.0.0.1"],
        "logging": {"driver": "json-file", "options": {"max-size": "10m", "max-file": "3"}},
        "volumes": volumes,
        "ulimits": {"nofile": {"soft": 65535, "hard": 65535}},
    }
    if uid is not None:
        value["user"] = f"{uid}:{gid}"
    return value


def db_settings():
    cfg = runtime.parse_env(ENV_FILE.read_text())
    user = cfg.get("SPRING_DATASOURCE_USERNAME", cfg.get("RUOYI_MYSQL_USERNAME", "root"))
    env = os.environ.copy()
    env["MYSQL_PWD"] = cfg.get("SPRING_DATASOURCE_PASSWORD", cfg.get("RUOYI_MYSQL_PASSWORD", ""))
    return user, env


def mysql_query(sql):
    user, env = db_settings()
    return run(["mysql", "--no-defaults", "--connect-timeout=3", "--protocol=TCP",
                "-h127.0.0.1", "-P51206", "--user=" + user, "--batch", "--raw",
                "--skip-column-names", "-e", sql], capture=True, env=env, timeout=10)



def allowed_application_log_link(component, item):
    """Only the application logs link may point into the mounted log tree."""
    if component != "application" or item != DATA["application"] / "main-service/logs":
        return False
    target = item.resolve()
    # Reject dangling links and redirected log roots: the container must see the
    # same absolute destination as the host through its existing bind mount.
    return (APP_LOG_ROOT.resolve() == APP_LOG_ROOT
            and target.is_relative_to(APP_LOG_ROOT) and item.is_dir())


def validate_data_paths():
    settings = mysql_query("SHOW VARIABLES WHERE Variable_name IN "
        "('version','datadir','innodb_data_home_dir','innodb_log_group_home_dir',"
        "'innodb_undo_directory','log_bin_basename','relay_log_basename',"
        "'default_table_encryption','secure_file_priv');")
    values = dict(line.split("\t", 1) for line in settings.splitlines() if "\t" in line)
    if not values.get("version", "").startswith("8.0.46"):
        raise RuntimeError("MySQL 版本与离线镜像 8.0.46 不一致")
    if Path(values.get("datadir", "/")).resolve() != DATA["mysql"]:
        raise RuntimeError("MySQL 数据目录发生变化")
    if values.get("default_table_encryption") not in ("OFF", ""):
        raise RuntimeError("检测到 MySQL 加密表，需要迁移密钥配置")
    for key in ("innodb_data_home_dir", "innodb_log_group_home_dir", "innodb_undo_directory",
                "log_bin_basename", "relay_log_basename"):
        val = values.get(key, "")
        if val and Path(val).is_absolute() and not Path(val).is_relative_to(DATA["mysql"]):
            raise RuntimeError("MySQL 包含外部存储路径：" + key)
    if not (DATA["mysql"] / "mysql").is_dir():
        raise RuntimeError("现有 MySQL 系统库目录缺失；禁止初始化新库")
    if not (DATA["kafka"] / "meta.properties").is_file():
        raise RuntimeError("Kafka 元数据文件缺失；禁止重新格式化")
    for component, path in DATA.items():
        if not path.is_dir() or path.is_symlink():
            raise RuntimeError("数据目录缺失或是软链接：" + str(path))
        for parent, dirs, files in os.walk(path):
            for name in dirs + files:
                item = Path(parent) / name
                if item.is_symlink() and not item.resolve().is_relative_to(path):
                    if allowed_application_log_link(component, item):
                        continue
                    raise RuntimeError("数据目录包含未挂载的外部软链接：" + str(item))
    secure = values.get("secure_file_priv", "")
    return secure if secure and secure != "NULL" else None


def app_metadata(name, info):
    settings, command, process_env, uid, gid = info
    files = settings.get("EnvironmentFiles", settings.get("EnvironmentFile", ""))
    if str(ENV_FILE) not in files:
        raise RuntimeError(name + " 未加载共用 config.env")
    environment = {k: v for k, v in process_env.items()
                   if k in ("PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "TZ",
                            "PYTHONPATH", "JAVA_HOME")}
    for entry in shlex.split(settings.get("Environment", "")):
        if "=" in entry:
            key, value = entry.split("=", 1)
            environment[key] = value
    cfg = runtime.parse_env(ENV_FILE.read_text())
    # Reject parser ambiguities before stopping anything. An intentionally edited
    # file can be applied after migration, using the shared configuration path.
    mismatched = [k for k, v in cfg.items() if process_env.get(k) != v]
    if mismatched:
        raise RuntimeError(name + " 的 config.env 与运行进程不一致，请先按现有方式重启后重试；键：" +
                           ",".join(mismatched))
    if name == "java":
        command[0] = str(Path(command[0]).resolve())
        if "-jar" not in command:
            raise RuntimeError("Java 当前命令不包含 -jar")
        jar = Path(command[command.index("-jar") + 1])
        if not jar.is_relative_to(BASE / "releases") or not jar.is_file():
            raise RuntimeError("Java JAR 不在保留的发布目录内")
    else:
        if not Path(command[0]).is_relative_to(BASE / "releases"):
            raise RuntimeError("Python 不在保留的发布目录内")
    return {"name": name, "uid": uid, "gid": gid,
            "groups": os.getgrouplist(settings["User"], gid),
            "cwd": settings["WorkingDirectory"], "command": command,
            "environment": environment, "environment_file": str(ENV_FILE),
            "wait_ports": [51206, 56379, 59092] + ([58000] if name == "java" else [])}



def config_field(path, key, separator="="):
    pattern = r"(?m)^\s*" + re.escape(key) + (
        r"\s*=\s*([^#\n]+)" if separator == "=" else r"\s+([^#\n]+)")
    matches = re.findall(pattern, Path(path).read_text())
    return matches[-1].strip().strip('"') if matches else ""


def prepare():
    require_owned_containers()
    if not inspect("mjn-qdrant") or not inspect("mjn-qdrant")["State"]["Running"]:
        raise RuntimeError("现有 Qdrant 容器没有运行")
    infos = {name: process_info(unit) for name, unit in UNITS.items()}
    if not check_once("qdrant"):
        raise RuntimeError("现有 Qdrant API 检查未通过")
    secure = validate_data_paths()
    if re.search(r"(?m)^\s*!include", Path("/etc/my.cnf").read_text()):
        raise RuntimeError("MySQL 包含额外配置目录，需要先处理挂载")
    for path, key, wanted, separator in (
        ("/etc/mjn-ai/redis.conf", "port", "56379", " "),
        ("/etc/mjn-ai/redis.conf", "dir", "/var/lib/mjn-redis", " "),
        ("/etc/mjn-ai/kafka/server.properties", "log.dirs", "/opt/mjn-ai/data/kafka", "="),
    ):
        if config_field(path, key, separator) != wanted:
            raise RuntimeError("配置已变化：" + str(path) + " / " + key)
    for key in ("include", "aclfile", "unixsocket"):
        if config_field("/etc/mjn-ai/redis.conf", key, " "):
            raise RuntimeError("Redis 包含额外文件或 socket 配置：" + key)
    for key in ("metadata.log.dir",):
        if config_field("/etc/mjn-ai/kafka/server.properties", key):
            raise RuntimeError("Kafka 包含额外数据目录：" + key)
    images = {}
    for image_name in set(IMAGES.values()):
        raw = run(["docker", "image", "inspect", image_name], capture=True)
        item = json.loads(raw)[0]
        if item["Os"] != "linux" or item["Architecture"] != "amd64":
            raise RuntimeError("镜像架构不匹配：" + image_name)
        images[image_name] = item["Id"]
    size = sum(int(run(["du", "-sk", p], capture=True, timeout=120).split()[0]) * 1024
               for p in DATA.values())
    if shutil.disk_usage(BASE).free < size * 1.3 + 512 * 1024**2:
        raise RuntimeError("磁盘剩余空间不足以保留停机数据备份")

    path = unique(ROOT / "runs", "")
    backup = unique(BASE / "backups", "docker-migration-")
    log("准备目录：" + str(path))
    log("备份目录：" + str(backup))
    config = path / "config"
    config.mkdir()
    preserve("/etc/my.cnf", config / "my.cnf")
    preserve("/etc/mjn-ai/redis.conf", config / "redis.conf")
    preserve("/etc/mjn-ai/kafka/server.properties", config / "server.properties")
    preserve("/etc/mjn-ai/nginx", config / "nginx")
    preserve("/etc/my.cnf", backup / "etc/my.cnf")
    preserve("/etc/mjn-ai", backup / "etc/mjn-ai")
    if Path("/etc/sysconfig/mysql").exists():
        preserve("/etc/sysconfig/mysql", backup / "etc/sysconfig/mysql")
    for name, unit in UNITS.items():
        unit_file = backup / (unit + ".effective.txt")
        unit_file.write_text(run(["systemctl", "cat", unit, "--no-pager"], capture=True))
        unit_file.chmod(0o600)
    nginx_path = config / "nginx/nginx.conf"
    nginx_text, count = re.subn(r"(?m)^\s*user\s+[^;]+;",
                               "user nginx nginx;", nginx_path.read_text())
    if count != 1 or re.search(r"(?m)^\s*daemon\s+", nginx_text):
        raise RuntimeError("Nginx user/daemon 配置不符合已盘点的结构")
    for included in re.findall(r"(?m)^\s*include\s+([^;\s]+)\s*;", nginx_text):
        if not included.startswith("/etc/mjn-ai/nginx/"):
            raise RuntimeError("Nginx 包含额外配置挂载：" + included)
    for root in re.findall(r"(?m)^\s*root\s+([^;\s]+)\s*;", nginx_text):
        if not Path(root).is_relative_to(BASE / "releases") or not (Path(root) / "index.html").is_file():
            raise RuntimeError("Nginx 前端目录无效")
    nginx_path.write_text(nginx_text)
    nginx_path.chmod(0o644)
    runtime_source = globals().get("_RUNTIME_SOURCE") or Path(runtime.__file__).read_text()
    (path / "runtime.py").write_text(runtime_source)
    (path / "runtime.py").chmod(0o644)
    services = {}

    for name, account in (("mysql", "mysql"), ("redis", "redis"),
                          ("kafka", "appuser"), ("java", "mjn-ai"), ("python", "mjn-ai")):
        info = infos[name]
        uid, gid = info[3:]
        account_files(path, name, IMAGES[name], account, uid, gid,
                      info[2].get("HOME") if name in ("java", "python") else None)

    def accounts(name):
        return [mount(path / "accounts" / name / f, "/etc/" + f, True)
                for f in ("passwd", "group")]

    uid, gid = infos["mysql"][3:]
    mysql_log = Path("/var/log/mysqld.log")
    if not mysql_log.is_file():
        raise RuntimeError("MySQL 日志文件不存在：" + str(mysql_log))
    mysql_volumes = accounts("mysql") + [
        mount(DATA["mysql"]), mount(config / "my.cnf", "/etc/my.cnf", True),
        mount(mysql_log)]
    if secure:
        mysql_volumes.append(mount(secure))
    mysql = basic_service("mysql", mysql_volumes, uid, gid)
    arguments = []
    original = infos["mysql"][1][1:]
    i = 0
    while i < len(original):
        arg = original[i]
        if arg in ("--daemonize", "--daemonize=1", "--daemonize=ON"):
            i += 1
            continue
        if arg == "--defaults-file":
            if i + 1 >= len(original) or original[i + 1] != "/etc/my.cnf":
                raise RuntimeError("MySQL 使用了未盘点的 defaults-file")
            i += 2
            continue
        if arg.startswith("--defaults-file="):
            if arg != "--defaults-file=/etc/my.cnf":
                raise RuntimeError("MySQL 使用了未盘点的 defaults-file")
        elif arg.startswith(("--defaults-extra-file", "--init-file", "--initialize")):
            raise RuntimeError("MySQL 包含不支持直接迁移的启动选项")
        else:
            arguments.append(arg)
        i += 1
    mysql.update(entrypoint=["mysqld"], command=["--defaults-file=/etc/my.cnf"] + arguments,
                 tmpfs=[f"/var/run/mysqld:uid={uid},gid={gid},mode=0755"])
    services["mysql"] = mysql

    uid, gid = infos["redis"][3:]
    redis = basic_service("redis", accounts("redis") + [
        mount(DATA["redis"]), mount(config / "redis.conf", "/etc/mjn-ai/redis.conf", True)], uid, gid)
    redis.update(entrypoint=["redis-server"], command=[
        "/etc/mjn-ai/redis.conf", "--daemonize", "no", "--supervised", "no",
        "--pidfile", "/tmp/redis.pid"], working_dir=str(DATA["redis"]))
    services["redis"] = redis

    uid, gid = infos["kafka"][3:]
    kafka = basic_service("kafka", accounts("kafka") + [
        mount(DATA["kafka"]), mount("/var/log/mjn-ai/kafka"),
        mount(config / "server.properties", "/etc/mjn-ai/kafka/server.properties", True)], uid, gid)
    kafka.update(entrypoint=["/opt/kafka/bin/kafka-server-start.sh"],
                 command=["/etc/mjn-ai/kafka/server.properties"],
                 environment={"KAFKA_HEAP_OPTS": infos["kafka"][2].get(
                     "KAFKA_HEAP_OPTS", "-Xms1g -Xmx1g"), "LOG_DIR": "/var/log/mjn-ai/kafka"})
    services["kafka"] = kafka

    for name in ("python", "java"):
        meta = app_metadata(name, infos[name])
        write_json(config / (name + ".json"), meta)
        volumes = accounts(name) + [
            mount(path / "runtime.py", "/mjn/runtime.py", True),
            mount(config / (name + ".json"), "/mjn/application.json", True),
            mount("/etc/mjn-ai", readonly=True), mount(BASE / "releases", readonly=True),
            mount(BASE / "python", readonly=True), mount(BASE / "tools", readonly=True),
            mount(DATA["application"]), mount(APP_LOG_ROOT)]
        if Path("/etc/pki").is_dir():
            volumes.append(mount("/etc/pki", readonly=True))
        if name == "java":
            jdk = Path(meta["command"][0]).parent.parent
            volumes.append(mount(jdk, readonly=True))
        app = basic_service(name, volumes)
        app.update(entrypoint=[PYTHON, "/mjn/runtime.py", "/mjn/application.json"],
                   working_dir=meta["cwd"],
                   environment={"PYTHONDONTWRITEBYTECODE": "1"})
        services[name] = app
    nginx = basic_service("nginx", [
        mount(config / "nginx", "/etc/mjn-ai/nginx", True),
        mount(BASE / "releases", readonly=True), mount("/var/log/mjn-ai/nginx")])
    nginx.update(entrypoint=["nginx"],
                 command=["-c", "/etc/mjn-ai/nginx/nginx.conf", "-g", "daemon off;"])
    services["nginx"] = nginx
    if Path("/etc/localtime").exists():
        for service in services.values():
            service["volumes"].append(mount("/etc/localtime", readonly=True))
    # Compose interprets dollar signs; every generated value must remain literal.
    def literal(value):
        if isinstance(value, str):
            return value.replace("$", "$$")
        if isinstance(value, list):
            return [literal(x) for x in value]
        if isinstance(value, dict):
            return {k: literal(v) for k, v in value.items()}
        return value
    write_json(path / "compose.json", literal({"services": services}))
    state = {
        "phase": "prepared", "backup": str(backup), "images": images,
        "enabled": {name: infos[name][0].get("UnitFileState", "disabled") for name in UNITS},
    }
    write_json(path / "state.json", state)
    compose(path, "config", "--quiet")
    log("检查应用依赖和实际 Nginx 配置")
    for name in ("python", "java"):
        compose(path, "run", "--rm", "--no-deps", name, "--check")
    compose(path, "run", "--rm", "--no-deps", "nginx", "-t", "-c", "/etc/mjn-ai/nginx/nginx.conf")
    return path


def redis_ready():
    cfg = runtime.parse_env(ENV_FILE.read_text())
    env = os.environ.copy()
    env["REDISCLI_AUTH"] = cfg.get("SPRING_DATA_REDIS_PASSWORD", cfg.get("RUOYI_REDIS_PASSWORD", ""))
    args = [BASE / "redis/bin/redis-cli", "-h", "127.0.0.1", "-p", "56379", "--raw"]
    username = cfg.get("SPRING_DATA_REDIS_USERNAME", cfg.get("RUOYI_REDIS_USERNAME"))
    if username:
        args += ["--user", username]
    return run(args + ["PING"], env=env, capture=True, timeout=5) == "PONG"


def http_ready(url, allow_client_error=False, headers=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(urllib.request.Request(url, headers=headers or {}), timeout=3) as response:
            return 200 <= response.status < 400
    except urllib.error.HTTPError as exc:
        return allow_client_error and 400 <= exc.code < 500
    except (OSError, urllib.error.URLError):
        return False


def check_once(name):
    if name == "mysql":
        return mysql_query("SELECT 1") == "1"
    if name == "redis":
        return redis_ready()
    if name == "kafka":
        return run([BASE / "kafka/bin/kafka-topics.sh", "--bootstrap-server",
                    "127.0.0.1:59092", "--list"], capture=True, timeout=20) is not None
    if name == "python":
        return http_ready("http://127.0.0.1:58000/health")
    if name == "java":
        return http_ready("http://127.0.0.1:56039/", True)
    if name == "nginx":
        return all(http_ready(url, api) for url, api in (
            ("http://127.0.0.1:50080/", False), ("http://127.0.0.1:58080/", False),
            ("http://127.0.0.1:50080/prod-api/", True), ("http://127.0.0.1:58080/api/", True)))
    if name == "qdrant":
        cfg = runtime.parse_env(Path("/etc/mjn-ai/qdrant/qdrant.env").read_text())
        return http_ready("http://127.0.0.1:56333/collections",
                          headers={"api-key": cfg["QDRANT__SERVICE__API_KEY"]})
    raise ValueError("Unknown service")


def wait_ready(name, container=True):
    log("等待 " + name)
    deadline, next_log = time.monotonic() + 150, time.monotonic() + 10
    while time.monotonic() < deadline:
        item = inspect(NAMES.get(name, "mjn-qdrant")) if container else None
        running = item and item["State"]["Running"] if container else True
        try:
            if running and check_once(name):
                log(name + " 检查通过")
                return
        except (RuntimeError, subprocess.TimeoutExpired, OSError):
            pass
        if time.monotonic() >= next_log:
            log(name + " 仍在启动，继续等待")
            next_log = time.monotonic() + 10
        time.sleep(2)
    raise RuntimeError(name + " 启动检查超时")


def save_phase(path, phase):
    state = json.loads((path / "state.json").read_text())
    state["phase"] = phase
    write_json(path / "state.json", state)
    return state



def ensure_guards():
    for unit in UNITS.values():
        directory = Path("/etc/systemd/system") / (unit + ".service.d")
        directory.mkdir(parents=True, exist_ok=True)
        guard = directory / "90-mjn-docker-guard.conf"
        if guard.exists() and guard.read_text() != GUARD:
            raise RuntimeError("已有不同的 Docker 启动保护配置：" + str(guard))
        guard.write_text(GUARD)
        guard.chmod(0o644)
    run(["systemctl", "daemon-reload"])


def assert_native_stopped():
    for unit in UNITS.values():
        current = props(unit)
        if current.get("MainPID", "0") != "0" or current.get("ActiveState") not in ("inactive", "failed"):
            raise RuntimeError("原服务仍未完全停止：" + unit)
    for port in PORTS:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                raise RuntimeError("端口仍被进程占用：" + str(port))
        except OSError:
            pass


def cold_backup(path):
    import hashlib
    backup = Path(json.loads((path / "state.json").read_text())["backup"])
    manifests = {}
    for name, source in DATA.items():
        log("停机备份 " + name)
        target = backup / "data" / name
        if target.exists():
            raise RuntimeError("禁止覆盖已有数据备份：" + str(target))
        preserve(source, target)
        manifest = {}
        for parent, dirs, files in os.walk(source):
            for dirname in dirs:
                original = Path(parent) / dirname
                if original.is_symlink():
                    copied = target / original.relative_to(source)
                    if not copied.is_symlink() or os.readlink(original) != os.readlink(copied):
                        raise RuntimeError("备份目录软链接不一致：" + str(original.relative_to(source)))
            for filename in files:
                original = Path(parent) / filename
                relative = original.relative_to(source)
                copied = target / relative
                if original.is_symlink():
                    if not copied.is_symlink() or os.readlink(original) != os.readlink(copied):
                        raise RuntimeError("备份软链接不一致：" + str(relative))
                    continue
                if not original.is_file() or not copied.is_file():
                    raise RuntimeError("备份含有非普通文件：" + str(relative))
                with original.open("rb") as stream:
                    expected = hashlib.file_digest(stream, "sha256").hexdigest()
                with copied.open("rb") as stream:
                    actual = hashlib.file_digest(stream, "sha256").hexdigest()
                if expected != actual:
                    raise RuntimeError("备份校验不一致：" + str(relative))
                manifest[str(relative)] = expected
        manifests[name] = manifest
    write_json(backup / "data-SHA256.json", manifests)
    (backup / "BACKUP_COMPLETE").write_text("Cold backup and checksums completed.\n")
    log("数据备份与校验完成")


def stop_containers(path):
    for name in reversed(ORDER):
        container = inspect(NAMES[name])
        if not container:
            continue
        if (container["Config"].get("Labels") or {}).get("com.docker.compose.project") != PROJECT:
            raise RuntimeError("拒绝操作其他项目的容器：" + NAMES[name])
        run(["docker", "update", "--restart=no", NAMES[name]], capture=True)
        run(["docker", "stop", "-t", "120", NAMES[name]], capture=True, timeout=150)
        current = inspect(NAMES[name])
        if current and current["State"]["Running"]:
            raise RuntimeError("容器未停止，禁止启动原服务：" + NAMES[name])


def rollback(path):
    log("停止本次迁移的容器，使用当前数据恢复原服务")
    stop_containers(path)
    # Only this marker is removed; releases, images, data and backups are retained.
    if ACTIVE.exists():
        if ACTIVE.read_text().strip() != str(path):
            raise RuntimeError("启用状态属于另一个迁移目录")
        ACTIVE.unlink()
    run(["systemctl", "daemon-reload"])
    state = json.loads((path / "state.json").read_text())
    for name, unit in UNITS.items():
        if state["enabled"][name] == "enabled":
            run(["systemctl", "enable", unit], capture=True)
        elif state["enabled"][name] == "disabled":
            run(["systemctl", "disable", unit], capture=True)
    for name in ORDER:
        run(["systemctl", "reset-failed", UNITS[name]], capture=True, check=False)
        run(["systemctl", "start", UNITS[name]], timeout=180)
        wait_ready(name, container=False)
    save_phase(path, "rolled_back")
    log("已恢复原 systemd 服务，继续使用迁移期间的最新数据；备份和镜像均保留")


def activate(path):
    state = json.loads((path / "state.json").read_text())
    (ROOT / "last").write_text(str(path) + "\n")
    for image, expected in state["images"].items():
        actual = json.loads(run(["docker", "image", "inspect", image], capture=True))[0]["Id"]
        if actual != expected:
            raise RuntimeError("准备期间镜像被替换：" + image)
    ensure_guards()
    save_phase(path, "stopping_native")
    try:
        log("开始切换，应用将短暂不可用")
        for name in reversed(ORDER):
            run(["systemctl", "stop", UNITS[name]], timeout=240)
        assert_native_stopped()
        ACTIVE.write_text(str(path) + "\n")
        ACTIVE.chmod(0o600)
        current = ROOT / "current"
        if current.is_symlink():
            current.unlink()
        elif current.exists():
            raise RuntimeError("current 路径不是软链接，禁止覆盖")
        current.symlink_to(path, target_is_directory=True)
        for name, unit in UNITS.items():
            if state["enabled"][name] == "enabled":
                run(["systemctl", "disable", unit], capture=True)
        save_phase(path, "backing_up")
        cold_backup(path)
        save_phase(path, "starting_docker")
        for name in ORDER:
            compose(path, "up", "-d", "--no-deps", name)
            wait_ready(name)
        wait_ready("qdrant")
        save_phase(path, "active")
        log("SUCCESS：六个原生服务已切换到 Docker，Qdrant 继续使用现有容器")
        log("当前配置：" + str(ROOT / "current"))
        log("保留的停机备份：" + state["backup"])
        log("请在浏览器验证登录、聊天和知识库操作")
    except BaseException:
        log("切换未完成，正在自动恢复原服务")
        try:
            rollback(path)
        except Exception:  # noqa: BLE001 - preserve recovery state at the CLI boundary.
            save_phase(path, "recovery_failed")
            log("自动恢复未完成；保留了现场和备份，请执行 status 并发送结果")
        raise


def status():
    path = read_active() if ACTIVE.exists() else None
    if path:
        log("当前模式：Docker；配置目录：" + str(path))
        compose(path, "ps", "-a")
    else:
        log("当前没有启用 Docker 迁移")
    for name, unit in UNITS.items():
        item = inspect(NAMES[name])
        state = item["State"]["Status"] if item else "not-created"
        native = props(unit).get("ActiveState", "unknown")
        print(f"{name}: docker={state}  systemd={native}")
    item = inspect("mjn-qdrant")
    print("qdrant: " + (item["State"]["Status"] if item else "missing"))


def restart(path, selected):
    expanded = []
    for name in selected:
        if name == "all":
            expanded += list(ORDER) + ["qdrant"]
        elif name == "apps":
            expanded += ["python", "java", "nginx"]
        elif name in ("web", "admin", "frontend"):
            expanded.append("nginx")
        elif name in (*ORDER, "qdrant"):
            expanded.append(name)
        else:
            raise ValueError("不支持的服务名：" + name)
    restart_order = ("mysql", "redis", "kafka", "qdrant", "python", "java", "nginx")
    names = [name for name in restart_order if name in expanded]
    if not names:
        raise ValueError("请指定 java/python/nginx/mysql/redis/kafka/qdrant/apps/all")
    if "all" in selected or "apps" in selected:
        compose(path, "stop", "nginx", "java", "python")
    for name in names:
        if name == "qdrant":
            run(["docker", "restart", "-t", "120", "mjn-qdrant"], timeout=150)
        else:
            compose(path, "restart", name)
        wait_ready(name)
    log("所选容器已重启；Java/Python 在启动时重新读取 config.env")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", default="migrate",
                        choices=("migrate", "rollback", "status", "restart"))
    parser.add_argument("services", nargs="*")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise RuntimeError("请使用 root 执行")
    os.umask(0o077)
    ROOT.mkdir(parents=True, exist_ok=True)
    run(["docker", "info"], capture=True)
    run(["docker", "compose", "version"], capture=True)
    lockdir = Path("/run/mjn-ai-ops")
    lockdir.mkdir(parents=True, exist_ok=True)
    with (lockdir / "control.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        script = globals().get("_SCRIPT_PATH")
        if script:
            installed = BASE / "tools/docker-migrate.sh"
            if Path(script).resolve() != installed.resolve():
                if installed.exists() and installed.read_bytes() != Path(script).read_bytes():
                    history = unique(BASE / "backups", "docker-tool-")
                    preserve(installed, history / installed.name)
                shutil.copy2(script, installed)
                installed.chmod(0o750)
        if args.action == "status":
            status()
        elif args.action == "rollback":
            if ACTIVE.exists():
                target = read_active()
            else:
                target = Path((ROOT / "last").read_text().strip())
                if target.is_symlink() or target.resolve().parent != (ROOT / "runs").resolve():
                    raise RuntimeError("无效的上次迁移目录")
            rollback(target)
        elif args.action == "restart":
            restart(read_active(), args.services)
        else:
            if ACTIVE.exists():
                status()
                raise RuntimeError("已存在启用的迁移；不要重复执行 migrate")
            for name, unit in UNITS.items():
                if props(unit).get("UnitFileState") not in ("enabled", "disabled", "static"):
                    raise RuntimeError("服务启用状态需要单独处理：" + name)
            path = prepare()
            activate(path)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001 - report CLI failure without a secret-bearing traceback.
        log("FAILED: " + str(exc))
        sys.exit(1)


MJN_DOCKER_MIGRATE_PY
