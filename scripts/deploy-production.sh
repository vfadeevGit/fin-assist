#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 5 ]]; then
    echo "Usage: deploy-production.sh DIRECTORY IMAGE SHA_TAG DIGEST DOMAIN" >&2
    exit 2
fi
deploy_dir=$1
export APP_IMAGE=$2 APP_TAG=$3 APP_DIGEST=$4
domain=$5
[[ $deploy_dir == /* && $APP_IMAGE =~ ^[a-z0-9._-]+/[a-z0-9._-]+$ &&
   $APP_TAG =~ ^sha-[0-9a-f]{40}$ && $APP_DIGEST =~ ^sha256:[0-9a-f]{64}$ &&
   $domain =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]+$ ]] || {
    echo "Invalid deployment directory, image, commit tag, digest or domain" >&2
    exit 2
}
cd -- "$deploy_dir"
[[ -f docker/.env && -f docker/docker-compose.yml ]] || {
    echo "Existing docker/.env and docker/docker-compose.yml are required" >&2
    exit 2
}
grep -q 'APP_DIGEST' docker/docker-compose.yml || {
    echo "Update the server Compose file to support APP_DIGEST before deploying" >&2
    exit 2
}
command -v python3 >/dev/null
command -v curl >/dev/null
compose=(docker compose --env-file docker/.env -f docker/docker-compose.yml)
"${compose[@]}" config --quiet
# Pull before replacing the running application. Preserve all existing volumes.
"${compose[@]}" pull finassist
"${compose[@]}" up -d --no-deps finassist
# Nginx resolves the application container address when configuration is loaded.
"${compose[@]}" exec -T nginx nginx -t
"${compose[@]}" exec -T nginx nginx -s reload

healthy=false
for attempt in {1..60}; do
    # Reach this VPS directly, while still validating its certificate and hostname.
    if curl --fail --silent --show-error --output /dev/null \
        --connect-timeout 5 --max-time 10 --noproxy '*' \
        --resolve "$domain:443:127.0.0.1" "https://$domain/login"; then
        healthy=true
        break
    fi
    sleep 5
done
if [[ $healthy != true ]]; then
    echo "Deployment health check failed; inspect the application logs. No automatic database rollback was attempted." >&2
    "${compose[@]}" ps
    exit 1
fi

# Persist only after the health check. Keep the previous settings for operator recovery.
python3 - <<'PY'
import os
from pathlib import Path
import re
import tempfile

path = Path("docker/.env")
backup = path.with_name(".env.previous")
backup_fd, backup_name = tempfile.mkstemp(dir=path.parent, prefix=".env.backup-")
try:
    with os.fdopen(backup_fd, "wb") as output:
        output.write(path.read_bytes())
    os.replace(backup_name, backup)
finally:
    if os.path.exists(backup_name):
        os.unlink(backup_name)
values = {key: os.environ[key] for key in ("APP_IMAGE", "APP_TAG", "APP_DIGEST")}
lines = [line for line in path.read_text().splitlines()
         if not re.match(r"^\s*(?:export\s+)?(?:APP_IMAGE|APP_TAG|APP_DIGEST)\s*=", line)]
lines.extend(f"{key}={value}" for key, value in values.items())
fd, name = tempfile.mkstemp(dir=path.parent, prefix=".env.deploy-")
try:
    with os.fdopen(fd, "w") as output:
        output.write("\n".join(lines) + "\n")
    os.replace(name, path)
finally:
    if os.path.exists(name):
        os.unlink(name)
PY
echo "Deployed $APP_IMAGE:$APP_TAG@$APP_DIGEST"
