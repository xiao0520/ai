#!/usr/bin/env python3
"""Repair the active Java Compose service's missing font runtime."""
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = Path("/opt/mjn-ai")
ACTIVE = BASE / "docker/active"
PROBE = BASE / "tools/FontProbe.java"
IMAGE = "public.ecr.aws/docker/library/centos:7.9.2009"
LIBRARIES = ("libfontconfig.so.1", "libfreetype.so.6", "libpng15.so.15")


def log(message):
    print(f"[{datetime.datetime.now():%Y-%m-%d %H:%M}] {message}", flush=True)


def run(args, *, capture=False, timeout=180):
    result = subprocess.run([str(arg) for arg in args], text=True, check=False,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None,
                            timeout=timeout)
    if result.returncode:
        # Do not echo Docker arguments: other Compose fields may include secrets.
        raise RuntimeError(f"{Path(str(args[0])).name} exited with {result.returncode}")
    return result.stdout.strip() if capture else None


def compose(file, *args):
    return run(["docker", "compose", "-p", "mjn-ai", "-f", file, *args], timeout=240)


def bind(source, target):
    source = Path(source)
    if not source.exists():
        raise RuntimeError(f"Missing host font path: {source}")
    return {"type": "bind", "source": str(source.resolve()), "target": target,
            "read_only": True, "bind": {"create_host_path": False}}


def font_mounts():
    mounts = [bind("/etc/fonts", "/etc/fonts"),
              bind("/usr/share/fonts", "/usr/share/fonts")]
    if not any(p.suffix.lower() in (".ttf", ".ttc", ".otf", ".pfa", ".pfb")
               for p in Path("/usr/share/fonts").rglob("*")):
        raise RuntimeError("No font files found under /usr/share/fonts")
    if Path("/usr/share/fontconfig").is_dir():
        mounts.append(bind("/usr/share/fontconfig", "/usr/share/fontconfig"))
    for soname in LIBRARIES:
        mounts.append(bind(Path("/lib64") / soname, "/usr/lib64/" + soname))
    return mounts


def active_paths():
    if ACTIVE.is_symlink() or not ACTIVE.is_file():
        raise RuntimeError("Docker migration is not active")
    directory = Path(ACTIVE.read_text().strip())
    if (directory.is_symlink() or directory.resolve().parent !=
            (BASE / "docker/runs").resolve()):
        raise RuntimeError("Unexpected active Docker directory")
    config = directory / "compose.json"
    metadata = directory / "config/java.json"
    if any(p.is_symlink() or not p.is_file() for p in (config, metadata)):
        raise RuntimeError("Active Java configuration is missing or linked")
    return config, metadata


def add_mounts(spec, mounts):
    service = spec["services"]["java"]
    if service["image"] != IMAGE:
        raise RuntimeError("Unexpected Java image")
    volumes = service["volumes"]
    added = 0
    for item in mounts:
        existing = next((v for v in volumes if isinstance(v, dict) and
                         v.get("target") == item["target"]), None)
        if existing:
            if existing.get("source") != item["source"] or not existing.get("read_only"):
                raise RuntimeError("Conflicting Java font mount: " + item["target"])
        else:
            volumes.append(item)
            added += 1
    return added


def isolated_probe(service, java_executable, mounts):
    if not PROBE.is_file():
        raise RuntimeError("Missing FontProbe.java in /opt/mjn-ai/tools")
    jdk = Path(java_executable).parent.parent
    if not jdk.is_dir():
        raise RuntimeError("Java installation is missing on host")
    args = ["docker", "run", "--rm", "--pull=never", "--network", "none",
            "--user", str(service.get("user", "0:0")),
            "--mount", f"type=bind,source={jdk},target={jdk},readonly",
            "--mount", f"type=bind,source={PROBE},target={PROBE},readonly"]
    for item in service["volumes"]:
        if isinstance(item, dict) and item.get("target") in ("/etc/passwd", "/etc/group"):
            args.extend(("--mount", "type=bind,source={source},target={target},readonly".format(**item)))
    for item in mounts:
        args.extend(("--mount", "type=bind,source={source},target={target},readonly".format(**item)))
    args.extend(("--entrypoint", java_executable, service["image"],
                 "-Djava.awt.headless=true", str(PROBE)))
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=90, check=False)
    if result.returncode or "FONT_PROBE_OK" not in result.stdout:
        print(result.stderr[-2000:], file=sys.stderr)
        raise RuntimeError("Isolated Java font probe did not render")


def java_ready():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open("http://127.0.0.1:56039/", timeout=3) as response:
            return response.status < 500
    except urllib.error.HTTPError as exc:
        return exc.code < 500
    except (OSError, urllib.error.URLError):
        return False


def wait_java():
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if java_ready():
            return
        time.sleep(2)
    raise RuntimeError("Java did not start within 180 seconds")


def live_probe(java_executable):
    output = run(["docker", "exec", "mjn-ai-java", java_executable,
                  "-Djava.awt.headless=true", PROBE], capture=True, timeout=60)
    if "FONT_PROBE_OK" not in output:
        raise RuntimeError("Live Java font probe did not render")


def captcha_ready():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with opener.open("http://127.0.0.1:56039/auth/code", timeout=6) as response:
                body = json.load(response)
            if body.get("code") == 200 and isinstance(body.get("data"), dict) and body["data"].get("img"):
                return
        except (OSError, urllib.error.URLError, ValueError):
            pass
        time.sleep(5)
    raise RuntimeError("Captcha API did not return a successful image within 90 seconds")


def atomic_write(path, data):
    temporary = path.with_name(path.name + f".fonts-{os.getpid()}")
    with temporary.open("xb") as stream:
        stream.write(data)
    os.chmod(temporary, path.stat().st_mode)
    os.replace(temporary, path)


def backup_dir():
    parent = BASE / "backups"
    parent.mkdir(parents=True, exist_ok=True)
    stem = "java-fonts-" + datetime.datetime.now().strftime("%Y%m%d-%H%M")
    for index in range(1, 1000):
        path = parent / (stem if index == 1 else f"{stem}-{index}")
        try:
            path.mkdir(mode=0o700)
            return path
        except FileExistsError:
            continue
    raise RuntimeError("Could not create a unique backup directory")


def repair():
    if os.geteuid() != 0:
        raise RuntimeError("Run as root")
    config, metadata = active_paths()
    before = config.read_bytes()
    spec = json.loads(before)
    java = spec["services"]["java"]
    details = json.loads(metadata.read_text())
    executable = details["command"][0]
    mounts = font_mounts()
    added = add_mounts(spec, mounts)
    if not added:
        live_probe(executable)
        captcha_ready()
        log("Java fonts and captcha already work; no change needed")
        return

    log("Checking fonts in an isolated Java container")
    isolated_probe(java, executable, mounts)
    candidate = (json.dumps(spec, ensure_ascii=False, indent=2) + "\n").encode()
    backup = backup_dir()
    (backup / "compose.before.json").write_bytes(before)
    (backup / "compose.candidate.json").write_bytes(candidate)
    for file in backup.iterdir():
        file.chmod(0o600)
    log(f"Configuration backup: {backup}")
    compose(backup / "compose.candidate.json", "config", "--quiet")
    atomic_write(config, candidate)
    try:
        log("Recreating only the Java container")
        compose(config, "up", "-d", "--no-deps", "--force-recreate", "java")
        wait_java()
        live_probe(executable)
        captcha_ready()
    except Exception:
        log("Java verification failed; restoring the previous Compose file and container")
        atomic_write(config, before)
        try:
            compose(config, "up", "-d", "--no-deps", "--force-recreate", "java")
            wait_java()
            log("Previous Java container restored")
        except Exception as recovery:
            log(f"Automatic recovery failed: {recovery}; inspect {backup}")
        raise
    (backup / "SUCCESS").write_text("Java font probe and /auth/code passed\n")
    log("SUCCESS: Java captcha works; other services and releases are unchanged")


def main():
    import fcntl
    lock_dir = Path("/run/mjn-ai-ops")
    lock_dir.mkdir(parents=True, exist_ok=True)
    with (lock_dir / "control.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        repair()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"FAILED: {exc}")
        sys.exit(1)
