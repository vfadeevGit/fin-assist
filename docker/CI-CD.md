# GitHub Actions delivery

The workflow `.github/workflows/delivery.yml` targets Docker Hub repository
`vfadeevdocker/fin-assist` and the existing Timeweb VPS `2.59.42.251`, serving
`https://fin-assist.st-novator.ru`.

## What runs

- Pull requests: Java 21 tests against an isolated PostgreSQL 16 service, deployment script tests, and an AMD64 Docker build. No image publication or production credentials.
- Pushes to `main`: the same checks, followed by publication of `sha-<full Git commit>` to Docker Hub.
- **Run workflow** on `main`: the same pipeline. Enable the **deploy** checkbox to additionally deploy that exact build digest to production. Leave it unchecked to build and publish only.
- Enable **no_cache** for a complete Docker layer rebuild. Base image metadata is refreshed on every build.
- Production deployments run one at a time. Active deployments are not canceled by subsequent pushes.

The manual deployment default can be changed later. The pipeline never deploys a pull request.

## GitHub configuration

In repository **Settings → Secrets and variables → Actions**, add:

| Type | Name | Value |
| --- | --- | --- |
| Secret | `DOCKERHUB_TOKEN` | Docker Hub personal access token with read/write permission for the image repository |
| Variable (optional) | `DOCKERHUB_USERNAME` | `vfadeevdocker` is the default |

In **Settings → Environments**, create `production`, restrict deployment branches to
`main`, and configure:

| Type | Name | Value |
| --- | --- | --- |
| Variable (optional) | `DEPLOY_HOST` | Defaults to `2.59.42.251` |
| Variable (optional) | `DEPLOY_PORT` | Defaults to `22` |
| Secret | `PRODUCTION_SSH_KEY` | Dedicated CI SSH private key without a passphrase; corresponding public key goes in the VPS user's `authorized_keys` |
| Secret | `PRODUCTION_KNOWN_HOSTS` | Verified SSH host public key entry for `2.59.42.251` (or the configured host and port) |

Generate the dedicated key on a trusted machine, install its public key for the deployment
user `deploy`, then paste the private key into the GitHub secret. Do not place credentials in
the repository or chat. The local key is `~/.ssh/finassist-deploy`. Do not add `deploy` to
the `docker` or `sudo` groups. The workflow fixes the SSH user to `deploy`; the privileged
command fixes the project path to `/root`, repository and domain to those above.

Obtain the SSH host key fingerprint through the Timeweb console or an already trusted
session. Compare it with `ssh-keyscan -p 22 2.59.42.251` before saving the matching key
entry as `PRODUCTION_KNOWN_HOSTS`. The workflow requires host verification and does not
automatically trust a newly scanned key.

## Prepare the existing VPS once

1. Confirm Docker Engine, Docker Compose v2, Bash, Python 3 and curl are installed.
2. Install the restricted command using the instructions below. The deployment user
   invokes only `sudo -n /usr/local/sbin/finassist-deploy`; it has no direct Docker access.
3. The installer adds digest support to the existing `/root/docker/docker-compose.yml`
   image expression, preserving other server settings and backing up the original file.
   CI does not run `git pull` or send a script for root to execute.
4. Keep the existing `docker/.env`, database volumes, storage volumes and TLS certificates.
   Set `DOMAIN=fin-assist.st-novator.ru`; retain the actual database credentials.
5. If the Docker Hub image is private, run `docker login` as root using a
   separate read-only Docker Hub token. The GitHub build token is not copied to the VPS.
6. Verify that PostgreSQL and Nginx are already running and HTTPS works. This workflow
   updates an existing installation; initial provisioning remains in [DEPLOY.md](DEPLOY.md).
7. Permit SSH from the GitHub runner network, or arrange a runner with network access
   to the VPS if the firewall limits SSH source addresses.

## Install restricted deployment access

The installer must be run once as root from a trusted copy of these three files in one
directory: `install-production-deploy.sh`, `restricted-deploy.py`, `deploy-production.sh`.
For an uploaded archive, copy it into `/root` and verify its SHA-256 against the value from
your trusted Mac **before** extracting or running it. Do not execute code directly from
the deployment user's writable home directory as root.

```bash
bash /root/finassist-ci-setup/scripts/install-production-deploy.sh
```

It installs:

- `/usr/local/sbin/finassist-deploy`: root-owned isolated Python entrypoint; accepts
  only a full `sha-<commit>` tag and `sha256:<digest>`, or the read-only `--check` operation.
- `/usr/local/lib/finassist/deploy-production.sh`: root-owned implementation, mode 700.
- `/etc/sudoers.d/finassist-deploy`: passwordless access for `deploy` to that one command,
  with environment overrides disabled. The command also constructs a clean subprocess
  environment and serializes deployment with a lock in the root-owned `/run` directory.

The entrypoint does not accept commands, paths, other repositories, or an uploaded script.
The installed implementation is updated only by an administrator using the installer.
The CI token can still replace the application and access its mounted application data;
protect production secrets and review application changes accordingly.

The installer checks root ownership, validates Compose and sudoers, and does not restart
containers or change group membership. Run from your Mac after installation:

```bash
ssh -T -o BatchMode=yes -o IdentitiesOnly=yes -i ~/.ssh/finassist-deploy \
  deploy@2.59.42.251 'sudo -n /usr/local/sbin/finassist-deploy --check'
```

Expected: `Restricted deployment configuration is ready; no services changed`.

Certbot's entrypoint correction and hourly Nginx reload have already been applied on the
VPS separately. Keep `/etc/cron.d/finassist-nginx-reload` in place.

## Deploy

After the workflow is merged into `main` and credentials are configured, open
**Actions → CI and Docker delivery → Run workflow**, select `main`, and enable **deploy**.

The deployment script validates inputs, pulls the exact digest, recreates only `finassist`,
and reloads Nginx to refresh the application container address. It checks `/login` over
HTTPS on the VPS loopback interface with the real hostname and certificate, then the
runner checks the public endpoint. No Compose `down` or volume deletion is performed.

After the VPS health check succeeds, `docker/.env` records `APP_IMAGE`, `APP_TAG` and
`APP_DIGEST`. The previous environment file is saved as `docker/.env.previous`; both files
contain secrets, are ignored by Git, and are stored with mode 600. The build summary
records the published tag and digest. A public endpoint check failure still fails the
workflow, even if the server has already saved a healthy local deployment.

Deployment replaces a single application container, so brief downtime is expected.
Liquibase migrations run at application startup. Back up the production database before
deploying schema changes; the workflow does not reverse migrations or restore databases.

## Failure and recovery

- A failed pull leaves the running application untouched.
- An error after container replacement fails the workflow; the new container may still
  be running. Inspect Compose status and application logs on the VPS.
- A failed VPS health check leaves `docker/.env` unchanged. It does not automatically
  restore the previous application or database.
- For an application rollback, first establish that the old application is compatible
  with the current database. Select its previously recorded commit tag and digest in
  `docker/.env` (or restore `.env.previous` after a successful deployment), then run:

```bash
docker compose --env-file docker/.env -f docker/docker-compose.yml pull finassist
docker compose --env-file docker/.env -f docker/docker-compose.yml up -d --no-deps finassist
docker compose --env-file docker/.env -f docker/docker-compose.yml exec -T nginx nginx -s reload
curl --fail https://fin-assist.st-novator.ru/login --output /dev/null
```

Clearing `APP_DIGEST` selects by tag instead. Prefer retaining a known digest for releases.

## Local validation

```bash
python3 -m unittest discover -s scripts/tests -v
bash -n scripts/deploy-production.sh
bash -n scripts/install-production-deploy.sh
actionlint .github/workflows/delivery.yml
docker compose --env-file docker/.env.example -f docker/docker-compose.yml config --quiet
./gradlew test
```

The deployment tests use temporary files and command doubles; they do not contact production.
Real SSH authentication, Docker Hub writes and production deployment require the configured
GitHub secrets and a workflow run.
