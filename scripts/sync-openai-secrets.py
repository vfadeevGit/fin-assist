#!/usr/bin/env python3
"""Upload only OPENAI settings from local Compose to GitHub production secrets."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, help='GitHub owner/repository')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    # Use the dotenv values, not an accidentally inherited local model/key override.
    env = {k: v for k, v in os.environ.items() if not k.startswith('OPENAI_')}
    result = subprocess.run(['docker', 'compose', '--env-file', str(root / 'docker/.env'),
                             '-f', str(root / 'docker/docker-compose.ai.yml'),
                             'config', '--no-consistency', '--format', 'json'],
                            env=env, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit('Cannot read AI settings from docker/.env (raw output withheld).')
    values = json.loads(result.stdout)['services']['finassist']['environment']
    keys = ('OPENAI_BASE_URL', 'OPENAI_API_KEY', 'OPENAI_MODEL')
    if any(not values.get(key) for key in keys):
        raise SystemExit('All three OPENAI values must be set in docker/.env.')
    for key in keys:
        # stdin keeps the secret out of shell history and process command-line arguments.
        result = subprocess.run(['gh', 'secret', 'set', key, '--env', 'production', '--repo', args.repo],
                                input=values[key], text=True, capture_output=True)
        if result.returncode:
            raise SystemExit(f'Could not update {key}; check gh authentication and production environment access.')
        print(f'Updated production secret {key}')


if __name__ == '__main__':
    main()
