#!/usr/bin/env bash
# Read-only details needed to construct the Docker cutover; never print secrets.
set -euo pipefail
[[ $(id -u) -eq 0 ]] || { echo '请使用 root 执行。' >&2; exit 1; }
exec /opt/mjn-ai/tools/python3.14 - <<'PY'
import json
import os
from pathlib import Path
import re
import subprocess

UNITS = ('mysqld', 'mjn-redis', 'mjn-kafka', 'mjn-ai-java', 'mjn-ai-python', 'mjn-nginx')
PATHS = (
    '/etc/my.cnf', '/etc/mjn-ai/redis.conf', '/etc/mjn-ai/kafka/server.properties',
    '/etc/mjn-ai/nginx/nginx.conf', '/etc/mjn-ai/nginx/mime.types',
    '/var/lib/mysql', '/var/lib/mjn-redis', '/opt/mjn-ai/data/kafka',
    '/var/lib/mjn-ai/main-service', '/var/log/mjn-ai/main-service',
    '/var/log/mjn-ai/nginx', '/var/log/mjn-ai/kafka',
    '/opt/mjn-ai/python', '/opt/mjn-ai/tools/python3.14',
    '/usr/lib/jvm/java-17-amazon-corretto',
)

def run(args, timeout=15):
    try:
        p = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.DEVNULL, timeout=timeout, check=False)
        return p.stdout.strip() if p.returncode == 0 else ''
    except (OSError, subprocess.TimeoutExpired):
        return ''

def command(pid):
    try:
        return [x.decode('utf-8', 'replace') for x in
                Path('/proc/{}/cmdline'.format(pid)).read_bytes().split(b'\0') if x]
    except (OSError, ValueError):
        return []

def safe_args(unit, argv):
    if unit == 'mjn-ai-java':
        result = {'jvm_flags': [x for x in argv if
                  re.fullmatch(r'-(?:Xms|Xmx|Xss)[0-9]+[kKmMgG]', x)
                  or re.fullmatch(r'-XX:[+\-][A-Za-z0-9]+', x)],
                  'property_names': sorted(set(re.findall(r'^-D([^=]+)=', x)[0]
                      for x in argv if re.findall(r'^-D([^=]+)=', x)))}
        for index, value in enumerate(argv[:-1]):
            if value == '-jar' and argv[index + 1].startswith('/opt/mjn-ai/releases/'):
                result['jar'] = argv[index + 1]
        return result
    if unit == 'mjn-ai-python':
        result = {}
        for flag, key in (('-m', 'module'), ('--host', 'host'), ('--port', 'port'),
                          ('--workers', 'workers'), ('--log-level', 'log_level')):
            if flag in argv:
                index = argv.index(flag)
                if index + 1 < len(argv):
                    value = argv[index + 1]
                    if re.fullmatch(r'[A-Za-z0-9_.:-]+', value):
                        result[key] = value
        result['executable'] = argv[0] if argv and argv[0].startswith('/opt/mjn-ai/releases/') else ''
        return result
    return {}

def stat_path(raw):
    path = Path(raw)
    try:
        info = path.lstat()
        out = {'exists': True, 'uid': info.st_uid, 'gid': info.st_gid,
               'mode': oct(info.st_mode & 0o777), 'symlink': path.is_symlink(),
               'resolved': str(path.resolve())}
        if path.is_dir():
            blocks = run(['du', '-sk', raw], timeout=60).split()
            if blocks and blocks[0].isdigit():
                out['size_mib'] = round(int(blocks[0]) / 1024, 1)
        else:
            out['size_bytes'] = info.st_size
        return out
    except OSError:
        return {'exists': False}

def config_values(path, names, split_pattern):
    result = {}
    try:
        lines = Path(path).read_text(errors='replace').splitlines()
    except OSError:
        return result
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith(('#', ';', '[')):
            continue
        match = re.match(split_pattern, line)
        if match and match.group(1) in names:
            value = match.group(2).strip().strip('"')
            if len(value) < 512 and not re.search(r'(?i)(password|secret|token|key=|@)', value):
                result.setdefault(match.group(1), []).append(value)
    return result

report = {'scope': 'read-only Docker migration details; no secrets or service changes',
          'units': {}, 'paths': {p: stat_path(p) for p in PATHS}}
for unit in UNITS:
    raw = run(['systemctl', 'show', unit, '--no-pager',
               '-p', 'User', '-p', 'Group', '-p', 'MainPID',
               '-p', 'WorkingDirectory', '-p', 'EnvironmentFiles',
               '-p', 'FragmentPath', '-p', 'DropInPaths'])
    props = dict(line.split('=', 1) for line in raw.splitlines() if '=' in line)
    pid = props.pop('MainPID', '0')
    envfiles = props.pop('EnvironmentFiles', '')
    props['environment_file_paths'] = re.findall(r'/[^\s()]+', envfiles)
    props['safe_process_args'] = safe_args(unit, command(pid))
    report['units'][unit] = props

report['mysql_directives'] = config_values('/etc/my.cnf', {
    'port', 'datadir', 'socket', 'pid-file', 'log-error', 'log_error', 'tmpdir',
    'secure-file-priv', 'secure_file_priv', 'bind-address', 'user',
    'lower_case_table_names', 'character-set-server', 'collation-server',
    'log-bin', 'log_bin', 'relay-log', 'relay_log', 'plugin-dir', 'plugin_dir',
    'innodb-data-home-dir', 'innodb_log_group_home_dir', 'innodb_undo_directory',
}, r'^([A-Za-z0-9_-]+)\s*=\s*(.*)$')
report['redis_directives'] = config_values('/etc/mjn-ai/redis.conf', {
    'bind', 'port', 'dir', 'dbfilename', 'appendonly', 'appenddirname', 'appendfilename',
    'pidfile', 'logfile', 'aclfile', 'include', 'unixsocket',
}, r'^([A-Za-z0-9_-]+)\s+(.*)$')
report['kafka_directives'] = config_values('/etc/mjn-ai/kafka/server.properties', {
    'listeners', 'advertised.listeners', 'log.dirs', 'metadata.log.dir',
    'controller.quorum.bootstrap.servers', 'process.roles', 'node.id',
}, r'^([A-Za-z0-9._-]+)\s*=\s*(.*)$')
report['nginx_directives'] = config_values('/etc/mjn-ai/nginx/nginx.conf', {
    'user', 'pid', 'error_log', 'access_log', 'root', 'include', 'listen',
    'proxy_pass', 'client_body_temp_path', 'proxy_temp_path',
    'fastcgi_temp_path', 'uwsgi_temp_path', 'scgi_temp_path', 'load_module',
}, r'^([A-Za-z0-9_]+)\s+([^;]+);')
report['image_users'] = {}
for name, image in (
    ('mysql', 'public.ecr.aws/docker/library/mysql:8.0.46'),
    ('redis', 'public.ecr.aws/docker/library/redis:7.2.16-bookworm'),
    ('kafka', 'apache/kafka:4.3.1'),
    ('nginx', 'public.ecr.aws/docker/library/nginx:1.30.4'),
):
    report['image_users'][name] = run(
        ['docker', 'image', 'inspect', image, '--format', '{{.Config.User}}'])
print(json.dumps(report, ensure_ascii=False, indent=2))
PY
