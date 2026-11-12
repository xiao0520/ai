"""Build the single-file server tool; all artifacts stay under D:/project."""
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parent
runtime_source = (root / "runtime.py").read_text(encoding="utf-8")
migration_source = (root / "migrate.py").read_text(encoding="utf-8")
compile(runtime_source, "runtime.py", "exec")
compile(migration_source, "migrate.py", "exec")
bootstrap = (
    "import sys, types\nfrom pathlib import Path\n"
    "_SCRIPT_PATH = Path(sys.argv.pop(1)).resolve()\n"
    "_RUNTIME_SOURCE = " + repr(runtime_source) + "\n"
    "_runtime_module = types.ModuleType('runtime')\n"
    "exec(compile(_RUNTIME_SOURCE, 'runtime.py', 'exec'), _runtime_module.__dict__)\n"
    "sys.modules['runtime'] = _runtime_module\n"
)
body = (
    "#!/usr/bin/env bash\nset -euo pipefail\n"
    "exec /opt/mjn-ai/tools/python3.14 - \"$0\" \"$@\" <<'MJN_DOCKER_MIGRATE_PY'\n"
    + bootstrap + migration_source + "\nMJN_DOCKER_MIGRATE_PY\n"
)
out = root.parent / "packages/docker-migration/migrate.sh"
out.write_text(body, encoding="utf-8", newline="\n")
digest = hashlib.sha256(out.read_bytes()).hexdigest()
out.with_name(out.name + ".sha256").write_text(
    digest + "  migrate.sh\n", encoding="ascii", newline="\n")
print(f"{out} ({out.stat().st_size} bytes)\nSHA256 {digest}")
