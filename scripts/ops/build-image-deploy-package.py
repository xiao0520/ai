#!/usr/bin/env python3
"""Create the checked image deployment tool bundle for the remote server."""

import hashlib
import tarfile
from pathlib import Path

OPS = Path(__file__).resolve().parent
DEST = OPS / "packages"
FILES = (
    "deploy-images.py",
    "deploy-release.py",
    "deploy-all.sh",
    "deploy-java.sh",
    "deploy-python.sh",
    "deploy-web.sh",
    "deploy-admin.sh",
    "FontProbe.java",
)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    target = DEST / "mjn-image-deploy-tools.tar.gz"
    with tarfile.open(target, "w:gz") as bundle:
        for name in FILES:
            source = OPS / name
            if source.is_symlink() or not source.is_file():
                raise RuntimeError(f"Missing or linked tool source: {source}")
            bundle.add(source, arcname=name, recursive=False)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (DEST / (target.name + ".sha256")).write_text(
        f"{digest}  {target.name}\n", encoding="ascii", newline="\n"
    )
    print(target)
    print("SHA256:", digest)


if __name__ == "__main__":
    main()
