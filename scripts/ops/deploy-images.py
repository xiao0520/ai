#!/usr/bin/env python3
"""Build versioned application images and switch the active Docker Compose stack."""

import argparse
import copy
import datetime
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath

SCRIPT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "mjn_deploy_release", SCRIPT / "deploy-release.py"
)
deploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deploy)

BASE = Path("/opt/mjn-ai")
MODE = BASE / "docker/image-mode.json"
PY_RUNTIME = BASE / "python"
JAVA_BASE = "public.ecr.aws/docker/library/centos:7.9.2009"
PYTHON_BASE = deploy.PYTHON_DOCKER_IMAGE
NGINX_BASE = "public.ecr.aws/docker/library/nginx:1.30.4"
FONT_SOURCES = {
    "/etc/fonts": Path("/etc/fonts"),
    "/usr/share/fonts": Path("/usr/share/fonts"),
    "/usr/share/fontconfig": Path("/usr/share/fontconfig"),
    "/usr/lib64/libfontconfig.so.1": Path("/lib64/libfontconfig.so.1"),
    "/usr/lib64/libfreetype.so.6": Path("/lib64/libfreetype.so.6"),
    "/usr/lib64/libpng15.so.15": Path("/lib64/libpng15.so.15"),
}
APP_MOUNTS = {
    "/mjn/runtime.py",
    "/opt/mjn-ai/releases",
    "/opt/mjn-ai/python",
    "/opt/mjn-ai/tools",
}
ORDER = ("python", "java", "nginx")


def log(message):
    print(f"[{datetime.datetime.now():%Y-%m-%d %H:%M}] {message}", flush=True)


def run(args, *, capture=False, timeout=900):
    result = subprocess.run(
        [str(value) for value in args],
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        timeout=timeout,
    )
    if result.returncode:
        # Arguments and stderr can contain deployment configuration.
        raise RuntimeError(f"{Path(str(args[0])).name} exited with {result.returncode}")
    return result.stdout.strip() if capture else ""


def put(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".new-{os.getpid()}")
    with temporary.open("x", encoding="utf-8") as stream:
        stream.write(content)
    temporary.chmod(0o600)
    os.replace(temporary, path)


def copy_tree(source, target):
    source = Path(source)
    if source.is_symlink() or not source.is_dir():
        raise RuntimeError(f"Missing or linked image input directory: {source}")
    shutil.copytree(source, target, symlinks=True)


def docker_copy(source, target):
    return "COPY " + json.dumps([source, target], ensure_ascii=False)


def image_name(component, version):
    return f"mjn-ai/{component}:{version}"


def rewrite_metadata(meta, before, after):
    result = copy.deepcopy(meta)
    changes = [0]

    def visit(value):
        if isinstance(value, str):
            old = before + "/"
            changes[0] += value.count(old)
            return value.replace(old, after + "/")
        if isinstance(value, list):
            return [visit(item) for item in value]
        if isinstance(value, dict):
            return {key: visit(item) for key, item in value.items()}
        return value

    result = visit(result)
    if before != after and not changes[0]:
        raise RuntimeError(
            "Application metadata does not reference the previous release"
        )
    return result


def rewrite_nginx(config, name, before, after):
    if before == after:
        return config
    old = before + "/" + name
    new = after + "/" + name
    pattern = r"(?m)^(\s*root\s+)" + re.escape(old) + r"(\s*;)"
    rewritten, count = re.subn(pattern, lambda match: match[1] + new + match[2], config)
    if count != 1:
        raise RuntimeError(f"Expected one Nginx root for {name}")
    return rewritten


def selected_services(selected):
    return tuple(
        name
        for name in ORDER
        if name in selected
        or (name == "nginx" and {"web", "admin"}.intersection(selected))
    )


def compose(path, *args):
    run(["docker", "compose", "-p", "mjn-ai", "-f", path, *args], timeout=300)


def source_metadata(directory, desired, previous, selected):
    configs = {}
    for name in ("java", "python"):
        source = directory / "config" / f"{name}.json"
        original = source.read_bytes()
        meta = json.loads(original)
        if name in selected:
            key = deploy.COMPONENTS[name][0]
            meta = rewrite_metadata(meta, previous[key], desired[key])
        configs[source] = (
            original,
            (json.dumps(meta, ensure_ascii=False, indent=2) + "\n").encode(),
        )
    nginx = directory / "config/nginx/nginx.conf"
    original = nginx.read_bytes()
    updated = original.decode()
    for name in ("web", "admin"):
        if name in selected:
            updated = rewrite_nginx(updated, name, previous[name], desired[name])
    configs[nginx] = (original, updated.encode())
    return configs


def build_context(context, component, desired, meta, directory):
    context.mkdir()
    lines = []
    if component in ("java", "python"):
        base = JAVA_BASE if component == "java" else PYTHON_BASE
        lines = [f"FROM {base}"]
        copy_tree(PY_RUNTIME, context / "python-runtime")
        lines.append(docker_copy("python-runtime/", str(PY_RUNTIME) + "/"))
        interpreter = BASE / "tools/python3.14"
        if interpreter.is_file():
            shutil.copy2(interpreter, context / "python3.14")
            lines.append(docker_copy("python3.14", str(interpreter)))
        shutil.copy2(directory / "runtime.py", context / "runtime.py")
        lines.append(docker_copy("runtime.py", "/mjn/runtime.py"))
        key = deploy.COMPONENTS[component][0]
        root = Path(desired[key])
        if component == "java":
            deploy.validate_release(root, selected=("java",))
            jdk = Path(meta["command"][0]).parent.parent
            copy_tree(jdk, context / "jdk")
            copy_tree(root / "main-service", context / "app")
            probe = SCRIPT / "FontProbe.java"
            if not probe.is_file():
                raise RuntimeError(f"Missing font probe: {probe}")
            shutil.copy2(probe, context / "FontProbe.java")
            lines.extend(
                (
                    docker_copy("jdk/", str(jdk) + "/"),
                    docker_copy("app/", str(root / "main-service") + "/"),
                    docker_copy("FontProbe.java", "/mjn/FontProbe.java"),
                )
            )
            for target, source in FONT_SOURCES.items():
                if target == "/usr/share/fontconfig" and not source.is_dir():
                    continue
                relative = "font-" + str(len(lines))
                if source.is_dir():
                    shutil.copytree(source, context / relative, symlinks=False)
                    lines.append(docker_copy(relative + "/", target + "/"))
                else:
                    if not source.is_file():
                        raise RuntimeError(f"Missing Java font library: {source}")
                    shutil.copy2(source.resolve(), context / relative)
                    lines.append(docker_copy(relative, target))
        else:
            deploy.validate_release(root, selected=("python",))
            copy_tree(root / "ai-service", context / "app")
            lines.append(docker_copy("app/", str(root / "ai-service") + "/"))
    elif component == "nginx":
        lines = [f"FROM {NGINX_BASE}"]
        for name in ("web", "admin"):
            root = Path(desired[name])
            deploy.validate_release(root, selected=(name,))
            copy_tree(root / name, context / name)
            lines.append(docker_copy(name + "/", str(root / name) + "/"))
    else:
        raise ValueError(f"Unknown image component: {component}")
    put(context / "Dockerfile", "\n".join(lines) + "\n")


def tree_size(path):
    path = Path(path)
    if path.is_file():
        return path.stat().st_size
    total = 0
    for parent, _, files in os.walk(path, followlinks=False):
        for name in files:
            item = Path(parent) / name
            if not item.is_symlink():
                total += item.stat().st_size
    return total


def check_build_space(desired, metadata, services):
    sources = [PY_RUNTIME]
    if "java" in services:
        sources.extend(
            (
                Path(desired["mjn-ai-java"]) / "main-service",
                Path(metadata["java"]["command"][0]).parent.parent,
            )
        )
        sources.extend(source for source in FONT_SOURCES.values() if source.exists())
    if "python" in services:
        sources.append(Path(desired["mjn-ai-python"]) / "ai-service")
    if "nginx" in services:
        sources.extend(Path(desired[name]) / name for name in ("web", "admin"))
    estimate = sum(tree_size(source) for source in sources)
    required = estimate * 2 + 1024**3
    available = shutil.disk_usage(BASE).free
    if available < required:
        raise RuntimeError(
            f"Insufficient disk space for image build: need about {required // 1024**3 + 1} GiB free"
        )
    log(f"Image build space check passed; about {available // 1024**3} GiB free")


def build_images(work, desired, metadata, services, version):
    images = {}
    directory = deploy.docker_dir()
    check_build_space(desired, metadata, services)
    for name in services:
        context = work / name
        build_context(context, name, desired, metadata.get(name), directory)
        tag = image_name(name, version)
        log(f"Building {name} image: {tag}")
        run(
            [
                "docker",
                "build",
                "--pull=false",
                "--network=none",
                "--platform=linux/amd64",
                "-t",
                tag,
                context,
            ],
            timeout=3600,
        )
        images[name] = tag
    return images


def probe_images(images, metadata, nginx_config, directory):
    if "java" in images:
        command = metadata["java"]["command"][0]
        output = run(
            [
                "docker",
                "run",
                "--rm",
                "--pull=never",
                "--network=none",
                "--entrypoint",
                command,
                images["java"],
                "-Djava.awt.headless=true",
                "/mjn/FontProbe.java",
            ],
            capture=True,
            timeout=120,
        )
        if "FONT_PROBE_OK" not in output:
            raise RuntimeError("Embedded Java font probe failed")
    if "python" in images:
        meta = metadata["python"]
        executable = meta["command"][0]
        run(
            [
                "docker",
                "run",
                "--rm",
                "--pull=never",
                "--network=none",
                "--env",
                "PYTHONPATH=" + str(Path(meta["cwd"]) / "src"),
                "--entrypoint",
                executable,
                images["python"],
                "-c",
                "import fastapi, uvicorn, numpy, qdrant_client, grpc, PIL, lxml, cryptography, aiokafka",
            ],
            timeout=120,
        )
    if "nginx" in images:
        with tempfile.TemporaryDirectory(
            prefix="mjn-nginx-probe-", dir=str(BASE / "docker")
        ) as temporary:
            candidate = Path(temporary) / "nginx"
            log_dir = Path(temporary) / "log"
            log_dir.mkdir()
            copy_tree(directory / "config/nginx", candidate)
            put(candidate / "nginx.conf", nginx_config.decode())
            run(
                [
                    "docker",
                    "run",
                    "--rm",
                    "--pull=never",
                    "--network=none",
                    "--mount",
                    f"type=bind,source={candidate},target=/etc/mjn-ai/nginx,readonly",
                    "--mount",
                    f"type=bind,source={log_dir},target=/var/log/mjn-ai/nginx",
                    "--entrypoint",
                    "nginx",
                    images["nginx"],
                    "-t",
                    "-c",
                    "/etc/mjn-ai/nginx/nginx.conf",
                ],
                timeout=120,
            )


def service_spec(original, images, metadata):
    spec = copy.deepcopy(original)
    for name, image in images.items():
        service = spec["services"][name]
        service["image"] = image
        removed = APP_MOUNTS if name in ("java", "python") else {"/opt/mjn-ai/releases"}
        if name == "java":
            jdk = str(PurePosixPath(metadata["java"]["command"][0]).parent.parent)
            removed = removed | set(FONT_SOURCES) | {jdk}
        service["volumes"] = [
            volume
            for volume in service["volumes"]
            if volume.get("target") not in removed
        ]
        if name in ("java", "python"):
            # Runtime code is embedded; application metadata stays a read-only
            # host configuration mount so credentials cannot enter an image.
            if service.get("entrypoint", [None])[1:] != [
                "/mjn/runtime.py",
                "/mjn/application.json",
            ]:
                raise RuntimeError(f"Unexpected {name} entrypoint")
    return spec


def ready(name):
    deploy.docker_wait(name)


def apply(directory, names):
    for name in ORDER:
        if name not in names:
            continue
        compose(
            directory / "compose.json",
            "up",
            "-d",
            "--no-deps",
            "--force-recreate",
            name,
        )
        ready(name)


def verify_images(images, expected_ids=None):
    ids = {}
    for name, image in images.items():
        raw = run(["docker", "image", "inspect", image], capture=True)
        info = json.loads(raw)[0]
        if info["Os"] != "linux" or info["Architecture"] != "amd64":
            raise RuntimeError(f"Unexpected image architecture for {name}")
        ids[name] = info["Id"]
        if expected_ids and name in expected_ids and ids[name] != expected_ids[name]:
            raise RuntimeError(f"Retained image changed since release: {name}")
    return ids


def snapshots(directory):
    config = directory / "config"
    return {
        "java": json.loads((config / "java.json").read_text()),
        "python": json.loads((config / "python.json").read_text()),
        "nginx": (config / "nginx/nginx.conf").read_text(),
    }


def atomic(path, data):
    if path.is_symlink() or not path.is_file():
        raise RuntimeError(f"Refusing to replace missing or linked config: {path}")
    deploy.atomic_write(path, data)


def backup(directory, current_state, planned_state, before_compose, before_configs):
    parent = deploy.unique_dir(BASE / "backups", "image-deploy-" + deploy.minute())
    parent.chmod(0o700)
    put(parent / "compose.before.json", before_compose.decode())
    put(
        parent / "state.before.json",
        json.dumps(current_state, ensure_ascii=False, indent=2),
    )
    put(
        parent / "state.planned.json",
        json.dumps(planned_state, ensure_ascii=False, indent=2),
    )
    for path, content in before_configs.items():
        put(parent / path.name, content.decode())
    return parent


def switch(directory, selected, previous, desired, images, version, initial=False):
    changed = selected_services(selected)
    old_spec_path = directory / "compose.json"
    old_spec_bytes = old_spec_path.read_bytes()
    old_spec = json.loads(old_spec_bytes)
    if not initial and not MODE.is_file():
        raise RuntimeError("Image mode marker is missing")
    if initial and MODE.exists():
        raise RuntimeError("Image mode is already active")
    current_state = (
        json.loads(MODE.read_text())
        if MODE.is_file()
        else {
            "schema": 1,
            "active": {
                "paths": previous,
                "images": {name: old_spec["services"][name]["image"] for name in ORDER},
            },
            "history": {},
        }
    )
    source = source_metadata(directory, desired, previous, selected)
    current_configs = {path: old for path, (old, _) in source.items()}
    new_metadata = {
        name: json.loads(source[directory / "config" / f"{name}.json"][1])
        for name in ("java", "python")
    }
    new_images = dict(current_state["active"]["images"])
    new_images.update(images)
    candidate = service_spec(old_spec, images, new_metadata)
    new_ids = dict(current_state["active"].get("image_ids", {}))
    new_ids.update(verify_images(images))
    planned = {"paths": desired, "images": new_images, "image_ids": new_ids}
    backup_path = backup(
        directory, current_state, planned, old_spec_bytes, current_configs
    )
    changed_files = []
    try:
        for path, (before, after) in source.items():
            if before != after:
                atomic(path, after)
                changed_files.append((path, before))
        next_spec = (
            json.dumps(candidate, ensure_ascii=False, indent=2) + "\n"
        ).encode()
        atomic(old_spec_path, next_spec)
        compose(old_spec_path, "config", "--quiet")
        verify_images(images, new_ids)
        if deploy.current() != desired:
            raise RuntimeError("Effective release paths do not match the planned state")
        apply(directory, changed)
        if "java" in changed:
            # A bound port alone does not verify font rendering.
            with urllib.request.urlopen(
                "http://127.0.0.1:56039/auth/code", timeout=10
            ) as response:
                captcha = json.load(response)
            if captcha.get("code") != 200 or not captcha.get("data", {}).get("img"):
                raise RuntimeError("Java captcha check failed")
        current_state["active"] = planned
        current_state["history"][version] = planned
        if initial:
            current_state["legacy_backup"] = str(backup_path)
        put(MODE, json.dumps(current_state, ensure_ascii=False, indent=2) + "\n")
        log(f"SUCCESS: switched {','.join(changed)} to versioned images {version}")
        log(f"Configuration backup: {backup_path}")
    except Exception:
        log(
            "Image switch failed; restoring the previous Compose and application configuration"
        )
        atomic(old_spec_path, old_spec_bytes)
        for path, before in reversed(changed_files):
            atomic(path, before)
        try:
            apply(directory, changed)
            log("Previous containers restored")
        except Exception:
            log(f"Automatic recovery failed; inspect {backup_path}")
        raise


def init_current(directory):
    if MODE.exists():
        raise RuntimeError("Image mode is already active")
    spec = json.loads((directory / "compose.json").read_text())
    expected = {"java": JAVA_BASE, "python": PYTHON_BASE, "nginx": NGINX_BASE}
    for name, image in expected.items():
        if spec["services"][name]["image"] != image:
            raise RuntimeError(
                f"Unexpected {name} base image; inspect before migration"
            )
    previous = deploy.current()
    version = "image-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    metadata = snapshots(directory)
    with tempfile.TemporaryDirectory(
        prefix="mjn-image-build-", dir=str(BASE / "docker")
    ) as temp:
        work = Path(temp)
        images = build_images(work, previous, metadata, ORDER, version)
        probe_images(images, metadata, metadata["nginx"].encode(), directory)
        switch(directory, deploy.ALL, previous, previous, images, version, initial=True)


def deploy_new(directory, selected, online):
    previous = deploy.current()
    release = deploy.prepare(online, selected, previous)
    desired = dict(previous)
    desired.update({deploy.COMPONENTS[name][0]: str(release) for name in selected})
    source = source_metadata(directory, desired, previous, selected)
    metadata = {
        name: json.loads(source[directory / "config" / f"{name}.json"][1])
        for name in ("java", "python")
    }
    services = selected_services(selected)
    with tempfile.TemporaryDirectory(
        prefix="mjn-image-build-", dir=str(BASE / "docker")
    ) as temp:
        work = Path(temp)
        images = build_images(work, desired, metadata, services, release.name)
        probe_images(
            images,
            metadata,
            source[directory / "config/nginx/nginx.conf"][1],
            directory,
        )
        switch(directory, selected, previous, desired, images, release.name)


def rollback(directory, version, selected):
    if not MODE.is_file():
        raise RuntimeError("Image mode is not active")
    state = json.loads(MODE.read_text())
    target = state["history"].get(version)
    if not target:
        raise RuntimeError("Unknown retained image version")
    previous = deploy.current()
    desired = dict(previous)
    for name in selected:
        key = deploy.COMPONENTS[name][0]
        desired[key] = target["paths"][key]
    image_services = selected_services(selected)
    images = {name: target["images"][name] for name in image_services}
    # A frontend-only rollback must include the currently selected other
    # frontend, so its image cannot be reused from an unrelated snapshot.
    if {"web", "admin"}.intersection(selected) and set(selected) != set(deploy.ALL):
        source = source_metadata(directory, desired, previous, selected)
        nginx = source[directory / "config/nginx/nginx.conf"][1]
        with tempfile.TemporaryDirectory(
            prefix="mjn-image-build-", dir=str(BASE / "docker")
        ) as temp:
            work = Path(temp)
            check_build_space(desired, {}, ("nginx",))
            build_context(work / "nginx", "nginx", desired, None, directory)
            tag = image_name(
                "nginx", "rollback-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            )
            run(
                [
                    "docker",
                    "build",
                    "--pull=false",
                    "--network=none",
                    "--platform=linux/amd64",
                    "-t",
                    tag,
                    work / "nginx",
                ],
                timeout=3600,
            )
            probe_images({"nginx": tag}, {}, nginx, directory)
            images["nginx"] = tag
    expected_ids = {
        name: target["image_ids"][name]
        for name in images
        if name != "nginx" or not {"web", "admin"}.intersection(selected)
    }
    verify_images(images, expected_ids)
    switch(
        directory,
        selected,
        previous,
        desired,
        images,
        "rollback-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"),
    )


def rollback_to_host(directory):
    if not MODE.is_file():
        raise RuntimeError("Image mode is not active")
    state = json.loads(MODE.read_text())
    backup_path = Path(state.get("legacy_backup", ""))
    if (
        backup_path.is_symlink()
        or backup_path.resolve().parent != (BASE / "backups").resolve()
        or not backup_path.name.startswith("image-deploy-")
    ):
        raise RuntimeError("Legacy configuration backup is invalid")
    paths = {
        directory / "compose.json": backup_path / "compose.before.json",
        directory / "config/java.json": backup_path / "java.json",
        directory / "config/python.json": backup_path / "python.json",
        directory / "config/nginx/nginx.conf": backup_path / "nginx.conf",
    }
    original = {}
    for target, source in paths.items():
        if source.is_symlink() or not source.is_file():
            raise RuntimeError(f"Missing legacy backup file: {source}")
        original[target] = target.read_bytes()
    saved = backup(
        directory,
        state,
        {"legacy": True},
        original[directory / "compose.json"],
        {key: value for key, value in original.items() if key.name != "compose.json"},
    )
    try:
        for target, source in paths.items():
            atomic(target, source.read_bytes())
        compose(directory / "compose.json", "config", "--quiet")
        apply(directory, ORDER)
        MODE.unlink()
        log(f"SUCCESS: restored the host-mounted application layout; backup: {saved}")
    except Exception:
        log("Legacy rollback failed; restoring the image configuration")
        for target, content in original.items():
            atomic(target, content)
        try:
            apply(directory, ORDER)
        except Exception:
            log(f"Automatic recovery failed; inspect {saved}")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--migrate-current",
        action="store_true",
        help="build and switch current applications into immutable images",
    )
    group.add_argument(
        "--legacy-rollback",
        action="store_true",
        help="restore the host-mounted layout saved at migration",
    )
    group.add_argument("--rollback", metavar="VERSION")
    group.add_argument("--list", action="store_true")
    parser.add_argument("--online", action="store_true")
    parser.add_argument("--only", choices=deploy.ALL)
    args = parser.parse_args()
    selected = (args.only,) if args.only else deploy.ALL
    if args.list:
        if MODE.is_file():
            state = json.loads(MODE.read_text())
            for version in sorted(state["history"]):
                print(
                    version
                    + (
                        "  active"
                        if state["history"][version] == state["active"]
                        else "  retained"
                    )
                )
        else:
            log("Image mode is not active")
        return
    if os.geteuid() != 0:
        raise RuntimeError("Run as root")
    if args.online and (args.rollback or args.migrate_current or args.legacy_rollback):
        parser.error("--online is only valid for a new deployment")
    if not deploy.DOCKER_ACTIVE.is_file():
        raise RuntimeError("Docker migration is not active")
    run(["docker", "info"], capture=True, timeout=30)
    run(["docker", "compose", "version"], capture=True, timeout=30)
    if not (BASE / "tools/python3.14").is_file():
        raise RuntimeError("Host deployment Python is missing")
    import fcntl

    lockdir = Path("/run/mjn-ai-ops")
    lockdir.mkdir(parents=True, exist_ok=True)
    with (lockdir / "control.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        directory = deploy.docker_dir()
        if args.migrate_current:
            init_current(directory)
        elif args.legacy_rollback:
            rollback_to_host(directory)
        elif args.rollback:
            rollback(directory, args.rollback, selected)
        else:
            if not MODE.is_file():
                raise RuntimeError("Run --migrate-current before image deployments")
            deploy_new(directory, selected, args.online)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"FAILED: {exc}")
        sys.exit(1)
