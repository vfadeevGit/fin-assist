#!/bin/bash
set -euo pipefail
[[ $EUID == 0 ]] || { echo "Run this installer as root" >&2; exit 1; }
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
for command in python3 docker curl visudo install; do command -v "$command" >/dev/null; done
id deploy >/dev/null
if id -nG deploy | tr ' ' '\n' | grep -Eq '^(docker|sudo)$'; then
    echo "Remove deploy from docker/sudo groups before installing restricted access" >&2
    exit 1
fi
[[ -f /root/docker/.env ]] || { echo "/root/docker/.env is required" >&2; exit 1; }

# Only change the application image expression; preserve the server's other settings.
python3 - <<'PY'
from pathlib import Path
import os
import tempfile

for name in ("/root", "/root/docker", "/root/docker/.env", "/root/docker/docker-compose.yml",
             "/usr/local", "/usr/local/sbin", "/usr/local/lib", "/etc/sudoers.d"):
    p = Path(name)
    if p.is_symlink() or not p.exists() or p.stat().st_uid != 0 or p.stat().st_mode & 0o022:
        raise SystemExit(f"Expected a root-owned, non-writable, non-symlink path: {name}")
for name in ("/usr/local/lib/finassist", "/usr/local/lib/finassist/deploy-production.sh",
             "/usr/local/sbin/finassist-deploy", "/etc/sudoers.d/finassist-deploy"):
    p = Path(name)
    if p.is_symlink() or (p.exists() and (p.stat().st_uid != 0 or p.stat().st_mode & 0o022)):
        raise SystemExit(f"Unsafe installation destination: {name}")
p = Path('/root/docker/docker-compose.yml')
text = p.read_text()
old = '    image: ${APP_IMAGE}:${APP_TAG}'
new = old + '${APP_DIGEST:+@}${APP_DIGEST:-}'
if new not in text:
    if text.splitlines().count(old) != 1:
        raise SystemExit('Unexpected Compose image configuration. Update finassist image manually to support APP_DIGEST.')
    backup = p.with_name('docker-compose.yml.pre-ci')
    if not backup.exists():
        with backup.open('x') as output:
            output.write(text)
    fd, name = tempfile.mkstemp(dir=p.parent, prefix='.compose-ci-')
    with os.fdopen(fd, 'w') as output:
        output.write(text.replace(old + '\n', new + '\n', 1))
    os.replace(name, p)
PY
docker compose --env-file /root/docker/.env -f /root/docker/docker-compose.yml config --quiet
install -d -o root -g root -m 755 /usr/local/lib/finassist
install -o root -g root -m 700 "$source_dir/deploy-production.sh" /usr/local/lib/finassist/deploy-production.sh
install -o root -g root -m 755 "$source_dir/restricted-deploy.py" /usr/local/sbin/finassist-deploy

rule=$(mktemp)
trap 'rm -f -- "$rule"' EXIT
printf '%s\n' 'deploy ALL=(root) NOPASSWD: NOSETENV: /usr/local/sbin/finassist-deploy' > "$rule"
visudo -cf "$rule"
install -o root -g root -m 440 "$rule" /etc/sudoers.d/finassist-deploy
visudo -c
/usr/local/sbin/finassist-deploy --check
echo "Installed restricted deployment access. No container was restarted."
