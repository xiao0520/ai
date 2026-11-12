#!/usr/bin/env python3
"""只合并已确认的生产连接参数，保留现有凭据和旧配置备份。"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import stat
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

CONFIG = Path("/etc/mjn-ai/config.env")
QDRANT_ENV = Path("/etc/mjn-ai/qdrant/qdrant.env")

PRODUCTION_MARKERS = {
    "SPRING_PROFILES_ACTIVE": "prod",
    "RUOYI_JAVA_PORT": "56039",
    "AI_PYTHON_BASE_URL": "http://127.0.0.1:58000",
    "AI_ENVIRONMENT": "prod",
}
REQUIRED_EXISTING = (
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_DATA_REDIS_PASSWORD",
    "RUOYI_JWT_SECRET_KEY",
    "AI_INTERNAL_SERVICE_TOKEN",
    "AI_INTERNAL_SIGNING_KEY",
)
KNOWN_UPDATES = {
    "AI_KAFKA_ENABLED": "true",
    "AI_KAFKA_BOOTSTRAP_SERVERS": "127.0.0.1:59092",
    "AI_KNOWLEDGE_INTERNAL_HTTP_ENABLED": "true",
    "AI_JAVA_INTERNAL_BASE_URL": "http://127.0.0.1:56039",
    "AI_QDRANT_URL": "http://127.0.0.1:56333",
}
ASSIGNMENT = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)")


def assignments(contents: str) -> tuple[list[str], dict[str, tuple[int, str]]]:
    """读取 KEY=VALUE，拒绝重复键，避免部署时产生歧义。"""
    lines = contents.splitlines(keepends=True)
    found: dict[str, tuple[int, str]] = {}
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", ";")):
            continue
        match = ASSIGNMENT.fullmatch(stripped)
        if match is None:
            raise ValueError(f"config.env 第 {index + 1} 行不是 KEY=VALUE")
        key, value = match.groups()
        if key in found:
            raise ValueError(f"config.env 中 {key} 重复，未修改文件")
        found[key] = (index, value.strip())
    return lines, found


def literal(value: str) -> str:
    """只用于非敏感的预检比较，不执行环境变量或 shell 展开。"""
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def qdrant_key(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Qdrant 配置文件不存在或是符号链接")
    matches = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"QDRANT__SERVICE__API_KEY=(.*)", line.strip())
        if match:
            matches.append(match.group(1))
    if len(matches) != 1 or not re.fullmatch(r"[0-9a-fA-F]{64}", matches[0]):
        raise ValueError("Qdrant API Key 缺失或格式与当前安装脚本不符")
    return matches[0]


def prepare(contents: str, key: str) -> tuple[str, tuple[str, ...]]:
    """保留已有行与密钥，只替换已确认的六项连接配置。"""
    lines, found = assignments(contents)
    for name, expected in PRODUCTION_MARKERS.items():
        actual = found.get(name)
        if actual is None or literal(actual[1]) != expected:
            raise ValueError(f"{name} 与已确认的生产环境不一致，未修改文件")
    for name in REQUIRED_EXISTING:
        entry = found.get(name)
        if entry is None or not literal(entry[1]).strip():
            raise ValueError(f"{name} 缺失或为空，未修改文件")
    updates = {**KNOWN_UPDATES, "AI_QDRANT_API_KEY": key}
    changed: list[str] = []
    missing: list[str] = []
    for name, value in updates.items():
        current = found.get(name)
        if current is None:
            missing.append(name)
        elif literal(current[1]) != value:
            ending = "\r\n" if lines[current[0]].endswith("\r\n") else "\n"
            lines[current[0]] = f"{name}={value}{ending}"
            changed.append(name)
    if missing:
        if lines and not lines[-1].endswith(("\n", "\r")):
            lines[-1] += "\n"
        lines.append("\n# Java/Python knowledge worker and Qdrant\n")
        lines.extend(f"{name}={updates[name]}\n" for name in missing)
        changed.extend(missing)
    return "".join(lines), tuple(changed)


def backup_name(path: Path) -> Path:
    stamp = datetime.now(timezone.utc).astimezone().strftime("%Y%m%d-%H%M")
    candidate = path.with_name(f"{path.name}.bak.{stamp}")
    number = 2
    while candidate.exists():
        candidate = path.with_name(f"{path.name}.bak.{stamp}-{number}")
        number += 1
    return candidate


def apply(
    config: Path, qdrant_env: Path, check: bool = False
) -> tuple[tuple[str, ...], Path | None]:
    if config.is_symlink() or not config.is_file():
        raise ValueError("生产 config.env 不存在或是符号链接")
    current = config.read_text(encoding="utf-8")
    updated, keys = prepare(current, qdrant_key(qdrant_env))
    if check or not keys:
        return keys, None

    before = config.stat()
    backup = backup_name(config)
    shutil.copy2(config, backup)
    backup.chmod(0o600)
    fd, temporary = tempfile.mkstemp(prefix=".config.env.", dir=config.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as output:
            output.write(updated)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, stat.S_IMODE(before.st_mode))
        if hasattr(os, "chown"):
            os.chown(temporary, before.st_uid, before.st_gid)
        os.replace(temporary, config)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return keys, backup


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="只检查，不修改文件")
    parser.add_argument("--config", type=Path, default=CONFIG)
    parser.add_argument("--qdrant-env", type=Path, default=QDRANT_ENV)
    args = parser.parse_args()
    try:
        keys, backup = apply(args.config, args.qdrant_env, args.check)
    except (OSError, ValueError) as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        return 1
    if args.check:
        print("待更新的键：" + (", ".join(keys) if keys else "无"))
    elif backup is None:
        print("配置已是目标状态，无需修改。")
    else:
        print(f"已备份原配置：{backup}")
        print("已更新的键：" + ", ".join(keys))
        print("现有数据库、Redis、认证及模型凭据均已保留；未重启服务。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
