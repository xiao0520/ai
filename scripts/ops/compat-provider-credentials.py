#!/usr/bin/env python3
"""Temporarily hide a new AK/SK credential from an older Python release."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

BASE = Path("/opt/mjn-ai")
CONFIG = Path("/etc/mjn-ai/config.env")
ACTIVE = BASE / "docker/active"
MARKER = BASE / "backups/credential-compat-active.json"
KEY = "AI_PROVIDER_CREDENTIALS"
CREDENTIAL = "doubao-rerank-aksk"
ASSIGNMENT = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)")


def read(path: Path) -> str:
    with path.open("r", encoding="utf-8", newline="") as stream:
        return stream.read()


def provider_line(contents: str) -> tuple[list[str], int, str, str]:
    lines = contents.splitlines(keepends=True)
    matches = []
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", ";")):
            continue
        assignment = ASSIGNMENT.fullmatch(stripped)
        if assignment is None:
            raise ValueError(f"config.env line {index + 1} is not KEY=VALUE")
        if assignment[1] == KEY:
            matches.append((index, assignment[2].strip()))
    if len(matches) != 1:
        raise ValueError("AI_PROVIDER_CREDENTIALS must appear exactly once")
    index, raw = matches[0]
    if len(raw) < 2 or raw[0] != "'" or raw[-1] != "'" or "'" in raw[1:-1]:
        raise ValueError("AI_PROVIDER_CREDENTIALS must be single-quoted JSON")
    return lines, index, raw, "\r\n" if lines[index].endswith("\r\n") else "\n"


def values(raw: str) -> dict:
    parsed = json.loads(raw[1:-1])
    if not isinstance(parsed, dict):
        raise ValueError("AI_PROVIDER_CREDENTIALS must be a JSON object")
    return parsed


def replace(contents: str, raw: str) -> str:
    lines, index, _, ending = provider_line(contents)
    lines[index] = f"{KEY}={raw}{ending}"
    return "".join(lines)


def stage_text(contents: str) -> str:
    _, _, raw, _ = provider_line(contents)
    credentials = values(raw)
    if not isinstance(credentials.get(CREDENTIAL), dict):
        raise ValueError("AK/SK object is absent")
    reduced = {key: value for key, value in credentials.items() if key != CREDENTIAL}
    if not all(isinstance(key, str) and isinstance(value, str)
               for key, value in reduced.items()):
        raise ValueError("Other credentials are incompatible with the old Python release")
    new_raw = "'" + json.dumps(reduced, ensure_ascii=False, separators=(",", ":")) + "'"
    if "'" in new_raw[1:-1]:
        raise ValueError("A remaining credential contains an unsupported quote")
    return replace(contents, new_raw)


def atomic_write(path: Path, contents: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError("config.env is missing or symlinked")
    before = path.stat()
    fd, temporary = tempfile.mkstemp(prefix=".config.env.compat-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as output:
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, stat.S_IMODE(before.st_mode))
        os.chown(temporary, before.st_uid, before.st_gid)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def current_cwd() -> str:
    if ACTIVE.is_symlink() or not ACTIVE.is_file():
        raise ValueError("Docker migration marker is missing")
    run = Path(ACTIVE.read_text(encoding="utf-8").strip())
    if run.is_symlink() or run.resolve().parent != (BASE / "docker/runs").resolve():
        raise ValueError("Unexpected active Docker run")
    meta = run / "config/python.json"
    if meta.is_symlink() or not meta.is_file():
        raise ValueError("Python release metadata is missing")
    cwd = json.loads(meta.read_text(encoding="utf-8"))["cwd"]
    if not re.fullmatch(r"/opt/mjn-ai/releases/[A-Za-z0-9][A-Za-z0-9._-]*/ai-service", cwd):
        raise ValueError("Unexpected Python release path")
    return cwd


def restart_and_wait() -> None:
    subprocess.run(["docker", "restart", "mjn-ai-python"], check=True,
                   stdout=subprocess.DEVNULL)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with opener.open("http://127.0.0.1:58000/health", timeout=3) as response:
                if 200 <= response.status < 400:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(2)
    raise RuntimeError("Python /health timed out; inspect docker logs --tail=80 mjn-ai-python")


def write_marker(state: dict) -> None:
    fd, temporary = tempfile.mkstemp(prefix=".credential-compat-", dir=MARKER.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(state, output)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, MARKER)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def stage() -> None:
    if MARKER.exists() or MARKER.is_symlink():
        raise ValueError(f"Credential is already staged: {MARKER}")
    if CONFIG.is_symlink() or not CONFIG.is_file():
        raise ValueError("Production config.env is missing or symlinked")
    original = read(CONFIG)
    staged = stage_text(original)
    cwd = current_cwd()
    backup = BASE / "backups" / ("credential-compat-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup.mkdir(mode=0o700)
    saved = backup / "config.env.before"
    shutil.copy2(CONFIG, saved)
    saved.chmod(0o600)
    _, _, raw, _ = provider_line(staged)
    write_marker({"backup": str(saved), "old_cwd": cwd,
                  "staged_sha256": hashlib.sha256(raw.encode()).hexdigest()})
    atomic_write(CONFIG, staged)
    print(f"Original config backed up: {saved}", flush=True)
    print(f"Temporarily hidden only {CREDENTIAL}; other settings preserved", flush=True)
    restart_and_wait()
    print("Old Python service is healthy; proceed with Python image upgrade", flush=True)


def restore() -> None:
    if MARKER.is_symlink() or not MARKER.is_file():
        raise ValueError("No staged credential marker found")
    state = json.loads(read(MARKER))
    backup = Path(state["backup"])
    if backup.is_symlink() or not backup.is_file() or backup.parent.parent != BASE / "backups":
        raise ValueError("Original config backup is missing or outside backup directory")
    cwd = current_cwd()
    if cwd == state["old_cwd"]:
        raise ValueError("New Python release is not active; refusing early restoration")
    settings = Path(cwd) / "src/ai_service/infra/settings.py"
    if settings.is_symlink() or not settings.is_file() or "VolcengineAkSkCredential" not in settings.read_text(encoding="utf-8"):
        raise ValueError("Active Python release does not support AK/SK credentials")
    _, _, original_raw, _ = provider_line(read(backup))
    if not isinstance(values(original_raw).get(CREDENTIAL), dict):
        raise ValueError("Backup does not contain AK/SK credential")
    current = read(CONFIG)
    _, _, staged_raw, _ = provider_line(current)
    if hashlib.sha256(staged_raw.encode()).hexdigest() != state["staged_sha256"]:
        raise ValueError("Provider credentials changed since staging; refusing to overwrite")
    atomic_write(CONFIG, replace(current, original_raw))
    try:
        restart_and_wait()
    except Exception:
        atomic_write(CONFIG, current)
        restart_and_wait()
        raise
    MARKER.unlink()
    print(f"AK/SK credential restored from {backup}; new Python service is healthy", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("stage", "restore"))
    args = parser.parse_args()
    try:
        if os.geteuid() != 0:
            raise ValueError("Run as root")
        import fcntl
        lock_dir = Path("/run/mjn-ai-ops")
        lock_dir.mkdir(parents=True, exist_ok=True)
        with (lock_dir / "control.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            stage() if args.action == "stage" else restore()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
