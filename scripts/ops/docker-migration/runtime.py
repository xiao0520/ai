"""Start an existing application with refreshed literal environment values."""
import json
import os
import re
import socket
import subprocess
import sys
import time
from pathlib import Path


def parse_env(text):
    """Support literal one-line systemd assignments; never evaluate shell code."""
    values = {}
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)", line)
        if not match:
            raise ValueError(f"config.env line {number}: expected KEY=VALUE")
        key, value = match[1], match[2].strip()
        if value.startswith("'"):
            if len(value) < 2 or not value.endswith("'") or "'" in value[1:-1]:
                raise ValueError(f"config.env line {number}: unsupported quoting")
            value = value[1:-1]
        elif value.startswith('"'):
            if len(value) < 2 or not value.endswith('"'):
                raise ValueError(f"config.env line {number}: incomplete quotes")
            inner, out, i = value[1:-1], [], 0
            while i < len(inner):
                if inner[i] == '"':
                    raise ValueError(f"config.env line {number}: unescaped quote")
                if inner[i] == "\\":
                    i += 1
                    if i >= len(inner):
                        raise ValueError(f"config.env line {number}: trailing escape")
                    if inner[i] not in ('\\', '"', '$', chr(96)):
                        out.append("\\")
                out.append(inner[i])
                i += 1
            value = "".join(out)
        elif any(c in value for c in ("'", '"', "\\")):
            raise ValueError(f"config.env line {number}: quote the whole value")
        if "\x00" in value:
            raise ValueError(f"config.env line {number}: invalid null byte")
        values[key] = value
    return values


def wait_for_ports(ports, seconds=120):
    deadline = time.monotonic() + seconds
    for port in ports:
        while True:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=2):
                    break
            except OSError:
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"Dependency port {port} is not ready")
                time.sleep(2)


def main():
    meta = json.loads(Path(sys.argv[1]).read_text())
    env = dict(meta["environment"])
    env.update(parse_env(Path(meta["environment_file"]).read_text()))
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    for key in ("NOTIFY_SOCKET", "LISTEN_PID", "LISTEN_FDS", "JOURNAL_STREAM",
                "INVOCATION_ID", "MAINPID"):
        env.pop(key, None)
    os.setgroups(meta["groups"])
    os.setgid(meta["gid"])
    os.setuid(meta["uid"])
    os.chdir(meta["cwd"])
    command = meta["command"]
    if "--check" in sys.argv[2:]:
        if not os.access(command[0], os.X_OK):
            raise RuntimeError("Application executable is not accessible")
        probe = [command[0], "-version"] if meta["name"] == "java" else [
            command[0], "-c", "import fastapi, uvicorn; print('Python dependencies OK')"]
        subprocess.run(probe, env=env, check=True)
        return
    wait_for_ports(meta["wait_ports"])
    os.execve(command[0], command, env)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001 - PID 1 must exit on any startup failure.
        print(f"Application startup failed: {exc}", file=sys.stderr)
        sys.exit(1)
