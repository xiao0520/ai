#!/usr/bin/env python3
"""Move only the existing Python Compose service to a glibc-compatible image."""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = Path("/opt/mjn-ai")
ACTIVE = BASE / "docker/active"
IMAGE = "ghcr.io/astral-sh/uv:python3.14-bookworm-slim"
IMAGE_IDS = {
    "sha256:7cf77f594be8042dab6daa9fe326f90962252268b4f120a7f5dccce4d947e6c1",
    "sha256:cd90433e3ff5766cd1cd210610cf2fc411a2718a9ea8ef1b90a41d6ba019b2ff",
}
HEALTH = "http://127.0.0.1:58000/health"


def log(message):
    print(f"[{datetime.datetime.now():%Y-%m-%d %H:%M}] {message}", flush=True)


def run(args, *, capture=False):
    result = subprocess.run([str(part) for part in args], check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else ""


def compose(file, *args):
    return run(["docker", "compose", "-p", "mjn-ai", "-f", file, *args])


def healthy():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(HEALTH, timeout=3) as response:
            return 200 <= response.status < 400
    except (OSError, urllib.error.URLError):
        return False


def wait_python():
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if healthy():
            return
        time.sleep(2)
    raise RuntimeError("Python health check timed out")


def atomic_write(path, data):
    temporary = path.with_name(path.name + f".runtime-{os.getpid()}")
    with temporary.open("xb") as stream:
        stream.write(data)
    os.chmod(temporary, path.stat().st_mode)
    os.replace(temporary, path)


def upgrade():
    if os.geteuid() != 0:
        raise RuntimeError("Run as root")
    if ACTIVE.is_symlink() or not ACTIVE.is_file():
        raise RuntimeError("Docker migration is not active")
    active = Path(ACTIVE.read_text().strip())
    if (active.is_symlink() or active.resolve().parent != (BASE / "docker/runs").resolve()):
        raise RuntimeError("Invalid active Docker run path")
    config = active / "compose.json"
    if config.is_symlink() or not config.is_file():
        raise RuntimeError("Invalid active Compose file")
    before = config.read_bytes()
    spec = json.loads(before)
    python = spec["services"]["python"]
    current = python["image"]
    if current not in (IMAGE, "public.ecr.aws/docker/library/centos:7.9.2009"):
        raise RuntimeError(f"Unexpected current Python image: {current}")
    actual_id = run(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], capture=True)
    if actual_id not in IMAGE_IDS:
        raise RuntimeError("Python image is missing or differs from the verified offline archive")
    os_arch = run(["docker", "image", "inspect", IMAGE,
                   "--format", "{{.Os}}/{{.Architecture}}"], capture=True)
    if os_arch != "linux/amd64":
        raise RuntimeError(f"Unexpected Python image platform: {os_arch}")
    if current == IMAGE:
        log("Python container already uses the verified image; no change needed.")
        return

    spec["services"]["python"]["image"] = IMAGE
    candidate = json.dumps(spec, ensure_ascii=False, indent=2).encode() + b"\n"
    backup = BASE / "backups" / ("python-runtime-" + datetime.datetime.now().strftime("%Y%m%d-%H%M"))
    backup.parent.mkdir(parents=True, exist_ok=True)
    for number in range(1, 1000):
        destination = backup if number == 1 else Path(str(backup) + f"-{number}")
        try:
            destination.mkdir(mode=0o700)
            backup = destination
            break
        except FileExistsError:
            continue
    else:
        raise RuntimeError("Cannot create unique backup directory")
    (backup / "compose.before.json").write_bytes(before)
    (backup / "compose.candidate.json").write_bytes(candidate)
    log(f"Configuration backup: {backup}")
    compose(backup / "compose.candidate.json", "config", "--quiet")
    log("Checking the current Python release inside the new image")
    compose(backup / "compose.candidate.json", "run", "-T", "--rm", "--no-deps",
            "python", "--check")
    log("Switching only the Python container")
    atomic_write(config, candidate)
    try:
        compose(config, "up", "-d", "--no-deps", "--force-recreate", "python")
        wait_python()
    except Exception:
        log("Python check failed; restoring the old image and container")
        atomic_write(config, before)
        try:
            compose(config, "up", "-d", "--no-deps", "--force-recreate", "python")
            wait_python()
        except Exception as recovery:
            log(f"Automatic recovery failed: {recovery}; inspect {backup}")
        raise
    (backup / "SUCCESS").write_text(IMAGE + "\n")
    log("Python image switched successfully; the previous release and other services remain unchanged")


def main():
    import fcntl
    lock_dir = Path("/run/mjn-ai-ops")
    lock_dir.mkdir(parents=True, exist_ok=True)
    with (lock_dir / "control.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        upgrade()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"FAILED: {exc}")
        sys.exit(1)
