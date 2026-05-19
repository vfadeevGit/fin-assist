# fin-assist VPS Deployment

This setup is intended for a plain VPS deployment where:

- Docker Hub builds and stores the application image.
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

1. Copy `docker/.env.example` to `docker/.env`.
2. Set `DOMAIN` to the production hostname.
3. Set `LETSENCRYPT_EMAIL` to the email for certificate registration.
4. Set `APP_IMAGE` and `APP_TAG` to the Docker Hub image and tag that should be deployed. The current default is `vfadeevdocker/fin-assist:0.0.3-SNAPSHOT-prod`.
5. Set a strong `POSTGRES_PASSWORD`.
6. Keep quotes around values that contain spaces, especially `JAVA_TOOL_OPTIONS`.
7. Keep the application image on `linux/amd64`, because the current VPS target runs on AMD64.

## Initial certificate issuance

Run these commands from the repository root on the VPS:

```bash
cp docker/.env.example docker/.env
vi docker/.env
set -a
. docker/.env
set +a

docker compose --env-file docker/.env \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.init.yml \
  up -d postgres finassist nginx

docker compose --env-file docker/.env \
  -f docker/docker-compose.yml \
  run --rm certbot certonly \
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
  down

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

## Current Production Image

Current expected production image:

- `vfadeevdocker/fin-assist:0.0.3-SNAPSHOT-prod`
- platform: `linux/amd64`
