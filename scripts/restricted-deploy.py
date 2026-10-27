#!/usr/bin/python3 -I
"""Root-owned sudo entrypoint; accepts image identifiers, never commands or paths."""
import fcntl
import os
from pathlib import Path
import re
import subprocess
import sys


LOCK_PATH = "/run/finassist-deploy.lock"
SAFE_ENV = {"HOME": "/root", "PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"}


def main(args):
    check = args == ["--check"]
    if not check and not (len(args) == 2
                         and re.fullmatch(r"sha-[0-9a-f]{40}", args[0])
                         and re.fullmatch(r"sha256:[0-9a-f]{64}", args[1])):
        print("Usage: finassist-deploy SHA_TAG SHA256_DIGEST | --check", file=sys.stderr)
        return 2
    if os.geteuid() != 0:
        print("Run through the configured sudo rule", file=sys.stderr)
        return 1
    if check:
        for name in ("/root", "/root/docker", "/root/docker/.env",
                     "/root/docker/docker-compose.yml", "/usr/local/lib/finassist",
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
             "-f", "/root/docker/docker-compose.yml", "config", "--quiet"],
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
            env=SAFE_ENV, stdin=subprocess.DEVNULL, check=False).returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
