#!/usr/bin/env python3
"""Deploy selected applications, or restore retained versions. Never remove releases."""
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

BASE = Path("/opt/mjn-ai")
RELEASES = BASE / "releases"
PACKAGES = BASE / "packages"
ETC = Path("/etc")
UNITS = ("mjn-ai-java", "mjn-ai-python")
NGINX = BASE / "nginx/sbin/nginx"
NGINX_CONF = ETC / "mjn-ai/nginx/nginx.conf"
PYTHON = BASE / "tools/python3.14"
UV = BASE / "tools/uv"
CONTROL = BASE / "tools/mjn-aictl.sh"
DOCKER_ACTIVE = BASE / "docker/active"
IMAGE_MODE = BASE / "docker/image-mode.json"
PYTHON_DOCKER_IMAGE = "ghcr.io/astral-sh/uv:python3.14-bookworm-slim"
FILES = ("ruoyi-admin.jar", "ai-service.tar.gz", "web.tar.gz", "admin.tar.gz")
COMPONENTS = {
    "java": ("mjn-ai-java", "main-service", "ruoyi-admin.jar"),
    "python": ("mjn-ai-python", "ai-service", "ai-service.tar.gz"),
    "web": ("web", "web", "web.tar.gz"),
    "admin": ("admin", "admin", "admin.tar.gz"),
}
ALL = tuple(COMPONENTS)



def log(message):
    print(f"[{datetime.datetime.now():%Y-%m-%d %H:%M}] {message}", flush=True)


def run(args, *, capture=False, cwd=None, env=None):
    result = subprocess.run([str(a) for a in args], check=True, cwd=cwd, env=env,
                            stdout=subprocess.PIPE if capture else None, text=True)
    return result.stdout if capture else ""


def unique_dir(parent, name):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
        raise ValueError("Invalid version name.")
    parent.mkdir(parents=True, exist_ok=True)
    for number in range(1, 10000):
        candidate = parent / (name if number == 1 else f"{name}-{number}")
        try:
            candidate.mkdir(mode=0o755)
            return candidate
        except FileExistsError:
            continue
    raise RuntimeError("Too many directories with the same version name.")


def minute():
    return datetime.datetime.now().strftime("%Y%m%d-%H%M")


def roots(text):
    text = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
    pattern = re.escape(str(RELEASES)) + r"/[A-Za-z0-9][A-Za-z0-9._-]*(?=/)"
    return sorted(set(re.findall(pattern, text)))


def current():
    if DOCKER_ACTIVE.is_file():
        return docker_current()
    found = {}
    for unit in UNITS:
        text = run(["systemctl", "show", unit, "-p", "ExecStart", "-p", "WorkingDirectory"],
                   capture=True)
        values = roots(text)
        if len(values) != 1:
            raise RuntimeError(f"{unit}: expected one effective release path; found {values}")
        found[unit] = values[0]
    text = NGINX_CONF.read_text()
    active_roots = re.findall(r"(?m)^\s*root\s+([^;\s]+)\s*;", text)
    app_roots = [p for p in active_roots if roots(p + "/")]
    if len(app_roots) != 2 or {Path(p).name for p in app_roots} != {"web", "admin"}:
        raise RuntimeError("Expected web and admin roots directly in nginx.conf.")
    found["web"] = next(str(Path(p).parent) for p in app_roots if Path(p).name == "web")
    found["admin"] = next(str(Path(p).parent) for p in app_roots if Path(p).name == "admin")
    return found


def docker_dir():
    """Resolve the active migration run without following an arbitrary path."""
    if DOCKER_ACTIVE.is_symlink():
        raise RuntimeError("Docker active marker must not be a symlink.")
    path = Path(DOCKER_ACTIVE.read_text().strip())
    if (path.is_symlink() or path.resolve().parent != (BASE / "docker/runs").resolve()
            or not (path / "compose.json").is_file()):
        raise RuntimeError("Invalid active Docker configuration.")
    return path


def docker_current():
    path = docker_dir()
    found = {}
    for name in ("java", "python"):
        meta = json.loads((path / "config" / f"{name}.json").read_text())
        values = roots("\n".join([*meta["command"], meta["cwd"]]))
        if len(values) != 1:
            raise RuntimeError(f"{name}: expected one Docker release path; found {values}")
        found[COMPONENTS[name][0]] = values[0]
    text = (path / "config/nginx/nginx.conf").read_text()
    active_roots = re.findall(r"(?m)^\s*root\s+([^;\s]+)\s*;", text)
    app_roots = [p for p in active_roots if roots(p + "/")]
    if len(app_roots) != 2 or {Path(p).name for p in app_roots} != {"web", "admin"}:
        raise RuntimeError("Expected web and admin roots in the Docker Nginx config.")
    for name in ("web", "admin"):
        found[name] = str(Path(next(p for p in app_roots if Path(p).name == name)).parent)
    return found


def docker_compose(path, *args):
    return run(["docker", "compose", "-p", "mjn-ai", "-f", path / "compose.json", *args])


def docker_python_run(release, command, *, online=False, environment=None):
    """Prepare and inspect the release in the same OS image that runs Python."""
    compose = json.loads((docker_dir() / "compose.json").read_text())
    image = compose["services"]["python"]["image"]
    if image != PYTHON_DOCKER_IMAGE:
        if IMAGE_MODE.is_file() and image.startswith("mjn-ai/python:"):
            # Prepare the next release in the fixed Python runtime base.
            image = PYTHON_DOCKER_IMAGE
        else:
            raise RuntimeError("Python Docker image is still CentOS 7. Install the Python runtime "
                               "image and run upgrade-python-runtime.py before deployment.")
    cache = BASE / "uv-cache"
    cache.mkdir(mode=0o700, exist_ok=True)
    args = ["docker", "run", "--pull=never", "--platform", "linux/amd64",
            "--rm", "--network", "host" if online else "none",
            "--mount", f"type=bind,source={RELEASES},target={RELEASES}",
            "--mount", f"type=bind,source={BASE / 'tools'},target={BASE / 'tools'},readonly",
            "--mount", f"type=bind,source={BASE / 'python'},target={BASE / 'python'},readonly",
            "--mount", f"type=bind,source={cache},target=/root/.cache/uv",
            "--workdir", str(release / "ai-service")]
    variables = {"UV_CACHE_DIR": "/root/.cache/uv", **(environment or {})}
    for key, value in variables.items():
        args += ["--env", f"{key}={value}"]
    args += ["--entrypoint", str(command[0]), image, *map(str, command[1:])]
    run(args)


def docker_http_ready(url, allow_client_error=False):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(url, timeout=3) as response:
            return 200 <= response.status < 400
    except urllib.error.HTTPError as exc:
        return allow_client_error and 400 <= exc.code < 500
    except (OSError, urllib.error.URLError):
        return False


def docker_wait(name):
    checks = {
        "python": (("http://127.0.0.1:58000/health", False),),
        "java": (("http://127.0.0.1:56039/", True),),
        "nginx": (
            ("http://127.0.0.1:50080/", False),
            ("http://127.0.0.1:58080/", False),
            ("http://127.0.0.1:50080/prod-api/", True),
            ("http://127.0.0.1:58080/api/", True),
        ),
    }
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if all(docker_http_ready(url, allow) for url, allow in checks[name]):
            return
        time.sleep(2)
    raise RuntimeError(f"Docker {name} health check timed out.")


def docker_apply(path, selected):
    for name in ("python", "java", "nginx"):
        if name not in selected and not (name == "nginx" and {"web", "admin"}.intersection(selected)):
            continue
        docker_compose(path, "up", "-d", "--no-deps", "--force-recreate", name)
        docker_wait(name)


def docker_switch(release, previous, selected=ALL):
    requested = version_state(release)
    desired = dict(previous)
    for name in selected:
        key = COMPONENTS[name][0]
        validate_release(Path(requested[key]), selected=(name,))
        desired[key] = requested[key]
    changed = tuple(name for name in selected
                    if desired[COMPONENTS[name][0]] != previous[COMPONENTS[name][0]])
    if not changed:
        log("Selected Docker application paths already match; nothing changed.")
        return
    path = docker_dir()
    backup = unique_dir(BASE / "backups", "deploy-" + minute())
    backup.chmod(0o700)
    (backup / "previous.json").write_text(json.dumps(previous, indent=2) + "\n")
    (backup / "desired.json").write_text(json.dumps(desired, indent=2) + "\n")
    (backup / "target.txt").write_text(str(release) + "\n")
    shutil.copy2(ETC / "mjn-ai/config.env", backup / "config.env.reference")
    replacements = {}
    for name in changed:
        if name in ("java", "python"):
            key = COMPONENTS[name][0]
            config = path / "config" / f"{name}.json"
            before = config.read_bytes()
            metadata = json.loads(before)
            changes = [0]
            def rewrite(value):
                if isinstance(value, str):
                    for separator in ("/", "\\"):
                        old = previous[key] + separator
                        changes[0] += value.count(old)
                        value = value.replace(old, desired[key] + separator)
                    return value
                if isinstance(value, list):
                    return [rewrite(item) for item in value]
                if isinstance(value, dict):
                    return {field: rewrite(item) for field, item in value.items()}
                return value
            metadata = rewrite(metadata)
            if not changes[0]:
                raise RuntimeError(f"{name}: active Docker path not found in metadata.")
            after = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode()
            replacements[config] = (before, after)
        else:
            config = path / "config/nginx/nginx.conf"
            before, after = replacements.get(config, (config.read_bytes(), None))
            text = (after or before).decode()
            old = previous[name] + "/" + name
            new = desired[name] + "/" + name
            pattern = r"(?m)^(\s*root\s+)" + re.escape(old) + r"(\s*;)"
            text, count = re.subn(pattern, lambda match: match[1] + new + match[2], text)
            if count != 1:
                raise RuntimeError(f"{name}: expected exactly one active Nginx root.")
            replacements[config] = (before, text.encode())
    for config in replacements:
        if config.is_symlink():
            raise RuntimeError(f"Refusing to replace symlinked Docker config: {config}")
        saved = backup / "configs" / config.name
        saved.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(config, saved)
    log(f"Docker configuration backup: {backup}")
    applied = []
    try:
        for config, (_, after) in replacements.items():
            atomic_write(config, after)
            applied.append(config)
        if {"web", "admin"}.intersection(changed):
            docker_compose(path, "run", "-T", "--rm", "--no-deps", "nginx",
                           "-t", "-c", "/etc/mjn-ai/nginx/nginx.conf")
        if current() != desired:
            raise RuntimeError("Active Docker release paths did not switch as planned.")
        docker_apply(path, changed)
    except Exception:
        if "python" in changed and applied:
            try:
                result = subprocess.run(["docker", "logs", "--tail", "200", "mjn-ai-python"],
                                        capture_output=True, text=True, check=False)
                failed_log = backup / "python-failed.log"
                failed_log.write_text(result.stdout + result.stderr, encoding="utf-8")
                failed_log.chmod(0o600)
                log(f"Failed Python container logs saved: {failed_log}")
            except Exception:
                log("Could not save failed Python container logs")
        if applied:
            log("Docker switch failed. Restoring selected paths and containers.")
            for config in reversed(applied):
                atomic_write(config, replacements[config][0])
            try:
                docker_apply(path, changed)
                log("Previous Docker application paths restored.")
            except Exception:
                log(f"Docker recovery failed; inspect containers and backup: {backup}")
        raise
    (backup / "SUCCESS").write_text(str(release) + "\n")
    log(f"SUCCESS: switched {','.join(changed)} to snapshot {release.name}; old dependencies retained.")
    log(f"Active component paths: {json.dumps(desired)}")
    log("Production config.env is unchanged. Verify login and model calls in the browser.")

def validate_release(path, require_python=True, selected=ALL):
    needed = {
        "java": ("main-service/ruoyi-admin.jar",),
        "python": ("ai-service/src/ai_service/main.py", "ai-service/pyproject.toml", "ai-service/uv.lock"),
        "web": ("web/index.html",), "admin": ("admin/index.html",),
    }
    for component in selected:
        for relative in needed[component]:
            if not (path / relative).is_file():
                raise RuntimeError(f"Missing release file: {path / relative}")
    if "java" in selected:
        with zipfile.ZipFile(path / "main-service/ruoyi-admin.jar") as jar:
            jar.getinfo("BOOT-INF/classes/org/ruoyi/RuoYiAIApplication.class")
            bad = jar.testzip()
            if bad:
                raise RuntimeError(f"Invalid JAR member: {bad}")
    if "python" in selected and require_python:
        if not os.access(path / "ai-service/.venv/bin/python", os.X_OK):
            raise RuntimeError("Selected release has no executable Python virtual environment.")


def version_state(path):
    manifest = path / ".deployment/state.json"
    if manifest.exists():
        state = json.loads(manifest.read_text())
    else:
        # Legacy complete release, including the first deploy-all implementation.
        state = {data[0]: str(path) for data in COMPONENTS.values()}
    if not isinstance(state, dict) or set(state) != {data[0] for data in COMPONENTS.values()}:
        raise ValueError("Invalid release state manifest.")
    for value in state.values():
        if not isinstance(value, str):
            raise ValueError("Invalid release path in state manifest.")
        root = Path(value)
        if root.is_symlink() or root.resolve().parent != RELEASES.resolve():
            raise ValueError("Release state references a path outside the release directory.")
    return state


def snapshot_packages(destination, selected=ALL):
    destination.mkdir()
    manifest = PACKAGES / "SHA256SUMS"
    expected = {}
    for line in manifest.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([a-fA-F0-9]{64}) [ *](.+)", line)
        if not match or match[2] not in FILES or match[2] in expected:
            raise ValueError("SHA256SUMS contains an unknown, duplicate or malformed package entry.")
        expected[match[2]] = match[1].lower()
    required = {COMPONENTS[name][2] for name in selected}
    if not required.issubset(expected):
        raise ValueError("SHA256SUMS is missing selected application packages.")
    (destination / "SHA256SUMS").write_text("".join(
        expected[n] + "  " + n + "\n" for n in FILES if n in required))
    for name in FILES:
        if name not in required:
            continue
        source = PACKAGES / name
        if source.is_symlink():
            raise ValueError(f"Package must not be a symlink: {name}")
        target = destination / name
        shutil.copy2(source, target)
        with target.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != expected[name]:
            raise ValueError(f"SHA256 mismatch: {name}")
        log(f"Verified: {name}")


def unpack(archive, target, python=False):
    target.mkdir()
    with tarfile.open(archive, "r:gz") as bundle:
        members = bundle.getmembers()
        skipped = set()
        for member in members:
            parts = PurePosixPath(member.name).parts
            if (PurePosixPath(member.name).is_absolute() or ".." in parts
                    or "\\" in member.name or not (member.isfile() or member.isdir())):
                raise ValueError(f"Unsafe archive member: {member.name}")
            # Ignore the regular template file; never install it as production config.
            if python and member.isfile() and parts and parts[-1] == ".env.example":
                if not any(p == ".venv" or p.startswith(".env") for p in parts[:-1]):
                    skipped.add(member.name)
                    continue
            if python and any(p == ".venv" or p.startswith(".env") for p in parts):
                raise ValueError(f"Python package contains a runtime environment file/directory: {member.name}")
        for member in members:
            if member.name in skipped:
                log(f"Skipped template: {member.name}")
                continue
            dest = target.joinpath(*PurePosixPath(member.name).parts)
            if member.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as src, dest.open("wb") as out:
                    shutil.copyfileobj(src, out)
                dest.chmod(0o755 if member.mode & 0o111 else 0o644)
    for directory, _, _ in os.walk(target):
        Path(directory).chmod(0o755)


def prepare(online, selected=ALL, previous=None):
    if len(selected) != len(ALL) and previous is None:
        raise ValueError("Partial deployment requires the current component paths.")
    release = unique_dir(RELEASES, minute())
    metadata = release / ".deployment"
    metadata.mkdir(mode=0o700)
    artifacts = metadata / "artifacts"
    log(f"Preparing {','.join(selected)} release: {release}")
    snapshot_packages(artifacts, selected)
    for name in selected:
        _, directory, package = COMPONENTS[name]
        if name == "java":
            (release / directory).mkdir()
            shutil.copy2(artifacts / package, release / directory / package)
            (release / directory / package).chmod(0o644)
        else:
            unpack(artifacts / package, release / directory, python=name == "python")
    validate_release(release, require_python=False, selected=selected)
    if "python" in selected:
        args = [UV, "sync", "--locked", "--no-dev", "--no-python-downloads", "--python", PYTHON]
        if not online:
            args.append("--offline")
        if DOCKER_ACTIVE.is_file():
            docker_python_run(release, args, online=online, environment={
                "UV_PROJECT_ENVIRONMENT": str(release / "ai-service/.venv"),
                "UV_LINK_MODE": "copy",
            })
            docker_python_run(release, [release / "ai-service/.venv/bin/python", "-c",
                                        "import fastapi, uvicorn, numpy, qdrant_client, "
                                        "grpc, PIL, lxml, cryptography, aiokafka"])
        else:
            env = os.environ.copy()
            env.pop("VIRTUAL_ENV", None)
            env["UV_PROJECT_ENVIRONMENT"] = str(release / "ai-service/.venv")
            env["UV_LINK_MODE"] = "copy"
            run(args, cwd=release / "ai-service", env=env)
            run([release / "ai-service/.venv/bin/python", "-c", "import fastapi, uvicorn"])
    validate_release(release, selected=selected)
    state = dict(previous or {})
    state.update({COMPONENTS[name][0]: str(release) for name in selected})
    (metadata / "state.json").write_text(json.dumps(state, indent=2) + "\n")
    (metadata / "components.json").write_text(json.dumps(list(selected)) + "\n")
    (metadata / "prepared").write_text("ready\n")
    return release


def config_files(selected=ALL):
    paths = [NGINX_CONF] if {"web", "admin"}.intersection(selected) else []
    for unit in UNITS:
        if unit not in {COMPONENTS[name][0] for name in selected}:
            continue
        base = ETC / f"systemd/system/{unit}.service"
        if not base.is_file():
            raise RuntimeError(f"Missing documented unit file: {base}")
        paths.append(base)
        paths.extend(sorted((base.parent / f"{unit}.service.d").glob("*.conf")))
    for path in paths:
        if path.is_symlink():
            raise RuntimeError(f"Refusing to replace symlinked config: {path}")
    return paths


def atomic_write(path, contents):
    # Replace only service/nginx configuration, never a release or dependency.
    temporary = path.with_name(path.name + f".deploy-{os.getpid()}")
    with temporary.open("xb") as out:
        out.write(contents)
    shutil.copystat(path, temporary)
    os.chown(temporary, path.stat().st_uid, path.stat().st_gid)
    os.replace(temporary, path)


def restart_and_check(selected=ALL):
    if set(selected) == set(ALL):
        run(["systemctl", "stop", "mjn-nginx", "mjn-ai-java", "mjn-ai-python"])
        run(["systemctl", "reset-failed", "mjn-ai-python", "mjn-ai-java", "mjn-nginx"])
        for unit in ("mjn-ai-python", "mjn-ai-java", "mjn-nginx"):
            run(["systemctl", "start", unit])
    else:
        for component in ("python", "java"):
            if component in selected:
                unit = COMPONENTS[component][0]
                run(["systemctl", "stop", unit])
                run(["systemctl", "reset-failed", unit])
                run(["systemctl", "start", unit])
        if {"web", "admin"}.intersection(selected):
            run([NGINX, "-t", "-c", NGINX_CONF])
            run(["systemctl", "reload", "mjn-nginx"])
    # Only checks other services; does not restart them or take the operation lock.
    run(["bash", CONTROL, "check"])


def switch(release, previous, selected=ALL):
    if DOCKER_ACTIVE.is_file():
        return docker_switch(release, previous, selected)
    requested = version_state(release)
    desired = dict(previous)
    for name in selected:
        key = COMPONENTS[name][0]
        validate_release(Path(requested[key]), selected=(name,))
        desired[key] = requested[key]
    changed = tuple(name for name in selected
                    if desired[COMPONENTS[name][0]] != previous[COMPONENTS[name][0]])
    if not changed:
        log("Selected application paths already match; nothing changed.")
        return
    frontend = bool({"web", "admin"}.intersection(changed))
    if frontend:
        run([NGINX, "-t", "-c", NGINX_CONF])
    backup = unique_dir(BASE / "backups", "deploy-" + minute())
    backup.chmod(0o700)
    (backup / "previous.json").write_text(json.dumps(previous, indent=2) + "\n")
    (backup / "target.txt").write_text(str(release) + "\n")
    (backup / "desired.json").write_text(json.dumps(desired, indent=2) + "\n")
    shutil.copy2(ETC / "mjn-ai/config.env", backup / "config.env.reference")
    replacements = {}
    for path in config_files(changed):
        original = path.read_bytes()
        updated = original.decode()
        if path == NGINX_CONF:
            for name in ("web", "admin"):
                if name in changed:
                    old = previous[name] + "/" + name
                    new = desired[name] + "/" + name
                    pattern = r"(?m)^(\s*root\s+)" + re.escape(old) + r"(\s*;)"
                    updated = re.sub(pattern, lambda m: m[1] + new + m[2], updated)
        else:
            # A unit/drop-in is rewritten only for its selected service.
            for name in ("java", "python"):
                unit = COMPONENTS[name][0]
                if name in changed and (path.name == unit + ".service"
                                        or path.parent.name == unit + ".service.d"):
                    updated = re.sub(re.escape(previous[unit]) + r"(?=/)",
                                     lambda _: desired[unit], updated)
        if updated.encode() != original:
            replacements[path] = (original, updated.encode())
            saved = backup / "configs" / path.relative_to(ETC)
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, saved)
    if not replacements:
        raise RuntimeError("No owned service/config paths could be updated; nothing switched.")
    if frontend:
        proposed = backup / "nginx-candidate.conf"
        proposed.write_bytes(replacements.get(NGINX_CONF, (b"", NGINX_CONF.read_bytes()))[1])
        run([NGINX, "-t", "-c", proposed])
    log(f"Configuration backup: {backup}")
    try:
        for path, (_, updated) in replacements.items():
            atomic_write(path, updated)
        if any(name in changed for name in ("java", "python")):
            run(["systemctl", "daemon-reload"])
        actual = current()
        if actual != desired:
            raise RuntimeError(f"Effective paths did not switch as planned: {actual}")
        restart_and_check(changed)
    except Exception:
        log("Switch failed. Restoring selected service paths; keeping all release files.")
        for path, (original, _) in replacements.items():
            atomic_write(path, original)
        if any(name in changed for name in ("java", "python")):
            run(["systemctl", "daemon-reload"])
        try:
            restart_and_check(changed)
            log("Previous selected application paths restored and basic checks passed.")
        except Exception:
            log(f"Recovery failed. Inspect journalctl and configuration backup: {backup}")
        raise
    (backup / "SUCCESS").write_text(str(release) + "\n")
    log(f"SUCCESS: switched {','.join(changed)} to snapshot {release.name}; old dependencies retained.")
    log(f"Active component paths: {json.dumps(desired)}")
    log("Production config.env is unchanged. Verify login and model calls in the browser.")


def select_version(name, selected=ALL):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
        raise ValueError("Specify a version directory name, not a path.")
    path = RELEASES / name
    if path.is_symlink() or path.resolve().parent != RELEASES.resolve() or not path.is_dir():
        raise ValueError(f"Unknown or symlinked release: {name}")
    state = version_state(path)
    for component in selected:
        validate_release(Path(state[COMPONENTS[component][0]]), selected=(component,))
    return path


def main():
    if IMAGE_MODE.is_file():
        image_tool = BASE / "tools/deploy-images.py"
        if not image_tool.is_file():
            raise RuntimeError(f"Image deployment tool is missing: {image_tool}")
        os.execv(str(PYTHON), [str(PYTHON), str(image_tool), *sys.argv[1:]])
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--rollback", metavar="VERSION", help="switch to retained version; no uv sync")
    action.add_argument("--list", action="store_true", help="list retained release directories")
    parser.add_argument("--online", action="store_true", help="allow uv to download missing dependencies")
    parser.add_argument("--only", choices=ALL, help="deploy or roll back only one application")
    args = parser.parse_args()
    selected = (args.only,) if args.only else ALL
    if args.online and (args.rollback or args.list):
        parser.error("--online is only valid for a new deployment")
    if args.list:
        for path in sorted(RELEASES.iterdir()):
            if path.is_dir() and not path.is_symlink():
                flag = "prepared" if (path / ".deployment/prepared").exists() else "legacy/unverified"
                components = path / ".deployment/components.json"
                names = ",".join(json.loads(components.read_text())) if components.exists() else "all/legacy"
                print(f"{path.name}  {flag}  {names}")
        return
    if os.geteuid() != 0:
        raise RuntimeError("Run as root.")
    if not (ETC / "mjn-ai/config.env").is_file():
        raise RuntimeError("Missing /etc/mjn-ai/config.env.")
    # New release files must be readable by the application and nginx users.
    os.umask(0o022)
    import fcntl
    state = Path(os.environ.get("MJN_OPS_STATE_DIR", "/run/mjn-ai-ops"))
    state.mkdir(parents=True, exist_ok=True)
    with (state / "control.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        previous = current()
        log(f"Current releases: {json.dumps(previous)}")
        if DOCKER_ACTIVE.is_file():
            config = docker_dir() / "config"
            for name in ("java", "python"):
                if name in selected:
                    meta = json.loads((config / f"{name}.json").read_text())
                    if meta.get("environment_file") != str(ETC / "mjn-ai/config.env"):
                        raise RuntimeError(f"{name} must load /etc/mjn-ai/config.env before deployment.")
        else:
            for unit in UNITS:
                if unit not in {COMPONENTS[name][0] for name in selected}:
                    continue
                configured = run(["systemctl", "show", unit, "-p", "EnvironmentFiles"], capture=True)
                if str(ETC / "mjn-ai/config.env") not in configured:
                    raise RuntimeError(f"{unit} must load /etc/mjn-ai/config.env before deployment.")
        if args.rollback:
            release = select_version(args.rollback, selected)
        else:
            release = prepare(args.online, selected, previous)
        switch(release, previous, selected)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"FAILED: {exc}")
        sys.exit(1)
