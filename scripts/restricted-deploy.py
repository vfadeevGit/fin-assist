#!/usr/bin/python3 -I
"""Root-owned sudo entrypoint; accepts image identifiers, never commands or paths."""
import fcntl
import json
from urllib.parse import urlsplit
import os
from pathlib import Path
import re
import subprocess
import sys


LOCK_PATH = "/run/finassist-deploy.lock"
SAFE_ENV = {"HOME": "/root", "PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"}


AI_KEYS = ("OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_MODEL")


def read_ai_config(stream):
    """Accept a small JSON object, never shell code or arbitrary environment names."""
    raw = stream.read(16385)
    if len(raw) > 16384:
        raise ValueError("AI configuration is too large")
    try:
        values = json.loads(raw)
    except (ValueError, TypeError):
        raise ValueError("Invalid AI configuration JSON") from None
    if not isinstance(values, dict) or set(values) != set(AI_KEYS):
        raise ValueError("Exactly three OPENAI variables are required")
    for value in values.values():
        if (not isinstance(value, str) or not value or len(value) > 4096
                or any(ord(c) < 32 or ord(c) == 127 or c in "'\\" for c in value)):
            raise ValueError("Invalid AI configuration value")
    url = urlsplit(values["OPENAI_BASE_URL"])
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("AI base URL must be HTTPS without credentials, query or fragment")
    return values


def main(args):
    with_ai = len(args) == 3 and args[0] == "--with-ai-config"
    if with_ai:
        args = args[1:]
    check = args == ["--check"]
    if not check and not (len(args) == 2
                         and re.fullmatch(r"sha-[0-9a-f]{40}", args[0])
                         and re.fullmatch(r"sha256:[0-9a-f]{64}", args[1])):
        print("Usage: finassist-deploy [--with-ai-config] SHA_TAG SHA256_DIGEST | --check", file=sys.stderr)
        return 2
    if os.geteuid() != 0:
        print("Run through the configured sudo rule", file=sys.stderr)
        return 1
    ai_values = {}
    if with_ai:
        try:
            ai_values = read_ai_config(sys.stdin)
        except ValueError as error:
            print(str(error), file=sys.stderr)
            return 2
    if check:
        for name in ("/root", "/root/docker", "/root/docker/.env",
                     "/root/docker/docker-compose.yml", "/root/docker/docker-compose.ai.yml", "/usr/local/lib/finassist",
                     "/usr/local/lib/finassist/deploy-production.sh",
                     "/usr/local/sbin/finassist-deploy"):
            path = Path(name)
            if path.is_symlink() or not path.exists():
                print(f"Missing or symlinked deployment path: {name}", file=sys.stderr)
                return 1
            info = path.stat()
            if info.st_uid != 0 or info.st_mode & 0o022:
                print(f"Deployment path must be root-owned and not group/world writable: {name}", file=sys.stderr)
                return 1
        result = subprocess.run(
            ["/usr/bin/docker", "compose", "--env-file", "/root/docker/.env",
             "-f", "/root/docker/docker-compose.yml", "-f", "/root/docker/docker-compose.ai.yml", "config", "--quiet"],
            env=SAFE_ENV, stdin=subprocess.DEVNULL, check=False)
        if result.returncode == 0:
            print("Restricted deployment configuration is ready; no services changed")
        return result.returncode
    with open(LOCK_PATH, "a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Another production deployment is running", file=sys.stderr)
            return 1
        return subprocess.run(
            ["/bin/bash", "/usr/local/lib/finassist/deploy-production.sh",
             "/root", "vfadeevdocker/fin-assist", *args, "fin-assist.st-novator.ru"],
            env={**SAFE_ENV, **ai_values}, stdin=subprocess.DEVNULL, check=False).returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
