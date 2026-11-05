# fin-assist VPS Deployment

This setup is intended for a plain VPS deployment where:

- GitHub Actions builds the application image and stores it on Docker Hub.
- The VPS runs Docker Engine with Docker Compose.
- `nginx` terminates TLS and proxies requests to the `finassist` container.
- `certbot` obtains and renews the Let's Encrypt certificate through the shared webroot.

## Files

- `docker/docker-compose.yml` - steady-state production stack
- `docker/docker-compose.init.yml` - temporary override for first certificate issuance
- `docker/.env.example` - environment template for the VPS
- `docker/nginx/templates/finassist-init.conf.template` - HTTP bootstrap config
- `docker/nginx/templates/finassist.conf.template` - final HTTPS config

## One-time preparation

1. Create a cloud server in Timeweb Cloud with Ubuntu.
2. Attach a public IPv4 address.
3. Open inbound TCP `22`, `80`, and `443` in Timeweb Cloud firewall and on the server itself.
4. Point your domain A record to the VPS public IP.
5. Wait until DNS resolves the domain to the VPS IP.
6. Install Docker Engine and Docker Compose plugin on the VPS.
7. Clone this repository onto the VPS.

## Environment

1. For a **new installation only**, copy `docker/.env.example` to `docker/.env`. On an existing VPS, preserve its `.env` and follow the AI Tools rollout in [CI-CD.md](CI-CD.md).
2. Set `DOMAIN` to the production hostname.
3. Set `LETSENCRYPT_EMAIL` to the email for certificate registration.
4. Use `APP_IMAGE=vfadeevdocker/fin-assist`; set `APP_TAG=sha-<full commit>` and `APP_DIGEST=sha256:<digest>` from a successful GitHub image build. The template intentionally has no release selected.
5. For a new database, set a strong `POSTGRES_PASSWORD`. On an existing installation, retain all three actual `POSTGRES_*` values; editing these variables does not change existing database credentials.
6. Keep quotes around values that contain spaces, especially `JAVA_TOOL_OPTIONS`.
7. Set `OPENAI_API_KEY` to your OpenRouter API key for the application and optionally `OPENAI_MODEL` (default `qwen/qwen3.8-flash`). Run `chmod 600 docker/.env`.
8. Keep the application image on `linux/amd64`, because the current VPS target runs on AMD64.

## Initial certificate issuance

Run these commands from the repository root on the VPS:

```bash
# Fresh installation only; do not overwrite an existing server environment.
cp -n docker/.env.example docker/.env
chmod 600 docker/.env
vi docker/.env
set -a
. docker/.env
set +a
: "${DOMAIN:?Set the production hostname}"
: "${LETSENCRYPT_EMAIL:?Set the real certificate contact email}"
: "${APP_TAG:?Select a published image tag}"

docker compose --env-file docker/.env \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.init.yml \
  up -d postgres finassist nginx

docker compose --env-file docker/.env \
  -f docker/docker-compose.yml \
  run --rm --no-deps --entrypoint certbot certbot certonly \
  --webroot -w /var/www/certbot \
  --email "$LETSENCRYPT_EMAIL" \
  --agree-tos \
  --no-eff-email \
  -d "$DOMAIN"
```

After the certificate is issued, switch to the final stack:

```bash
docker compose --env-file docker/.env \
  -f docker/docker-compose.yml \
  up -d --no-deps --force-recreate nginx

docker compose --env-file docker/.env \
  -f docker/docker-compose.yml \
  up -d
```

If you see `-XX:+UseParallelGC: command not found` after `. docker/.env`, it means a value with spaces in `docker/.env` is unquoted. The typical fix is:

```bash
JAVA_TOOL_OPTIONS="-Xmx1024m -XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90 -XX:MinHeapFreeRatio=20 -XX:MaxHeapFreeRatio=40"
```

If you do not want to source the full file at all, you can export only the values needed for certificate issuance:

```bash
export DOMAIN=your-domain.example.com
export LETSENCRYPT_EMAIL=ops@example.com
```

## Updating the application

For automated builds and manual production deployments, see [GitHub Actions setup](CI-CD.md).
Production runs at `https://fin-assist.st-novator.ru` on Timeweb host `2.59.42.251`.

When switching manually to a tag, clear `APP_DIGEST` in `docker/.env` first;
otherwise Compose continues to use the previously pinned digest.

1. Push changes to GitHub.
2. Build and push an AMD64-compatible Docker image tag to Docker Hub, or wait for Docker Hub to publish that exact tag.
3. Update `APP_TAG` in `docker/.env` if needed.
4. Pull and restart:

```bash
docker compose --env-file docker/.env -f docker/docker-compose.yml pull
docker compose --env-file docker/.env -f docker/docker-compose.yml up -d
```

## Verification

1. Check container state:

```bash
docker compose --env-file docker/.env -f docker/docker-compose.yml ps
```

1. Check logs if something fails:

```bash
docker compose --env-file docker/.env -f docker/docker-compose.yml logs -f nginx
docker compose --env-file docker/.env -f docker/docker-compose.yml logs -f finassist
docker compose --env-file docker/.env -f docker/docker-compose.yml logs -f certbot
```

1. Confirm that:

- `https://your-domain` opens the application
- HTTP redirects to HTTPS
- the application can connect to PostgreSQL
- files written by Jmix local storage appear in the `storage` volume

## Production image and certificate renewal

The deployed image is recorded in the **server's** `docker/.env` after a successful
deployment: repository `vfadeevdocker/fin-assist`, a `sha-<commit>` tag and an exact
digest, on `linux/amd64`. The template is not a record of the live deployment.

Certbot renews certificates in the existing `letsencrypt` volume every 12 hours,
using the shared `/var/www/certbot` webroot. Nginx must reload to pick up renewed
certificates. Preserve the existing server job `/etc/cron.d/finassist-nginx-reload`.
For a fresh VPS, install an equivalent hourly root cron job running:

```bash
/usr/bin/docker compose --env-file /root/docker/.env -f /root/docker/docker-compose.yml exec -T nginx nginx -s reload
```

These paths assume the production checkout is `/root`, as required by the restricted
deployment command. Validate HTTPS and renewal on the VPS before considering a new
installation ready.
