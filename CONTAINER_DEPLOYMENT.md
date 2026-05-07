# Container Deployment Handoff

Date: 2026-05-07

## Production Shape

- Cloud host runs the frontend container only.
- Windows PC runs the backend container and keeps the trading DB, logs, broker credentials, and ProjectX/TopstepX config local.
- Do not expose backend port `7070` to the public internet.
- For local/private testing, map backend to loopback only: `127.0.0.1:7070:7070`.
- For cloud control, point the frontend at a secure bridge/API gateway URL after that bridge exists.

## First Private User

Public registration is disabled by default. A fresh DB can create one private admin from env vars:

```bash
cp .env.example .env
```

Edit `.env` before launch:

```bash
TRADINGBOT_BOOTSTRAP_ADMIN_EMAIL=your-private-email@example.com
TRADINGBOT_BOOTSTRAP_ADMIN_PASSWORD=use-a-long-private-password
TRADINGBOT_DEFAULT_ACCOUNT_EMAIL=your-private-email@example.com
```

After the first successful login, remove the bootstrap password from `.env` or rotate it. Existing accounts are not overwritten.

## Local Container Smoke Test

From the repo root:

```bash
docker compose build
docker compose up -d
docker compose logs -f backend
docker compose logs -f frontend
```

Open:

```text
http://localhost:8080
```

Health check:

```bash
curl http://localhost:7070/api/system/health
```

Stop:

```bash
docker compose down
```

The compose file maps both services to `127.0.0.1` on the host, so they are not reachable from the public network by default.

## Cloud Frontend Container

Build and run:

```bash
docker build -t tradingbot-frontend ./frontend
docker run -d --name tradingbot-frontend \
  -p 8080:8080 \
  -e TRADINGBOT_API_BASE_URL=https://your-bridge-or-api.example.com \
  tradingbot-frontend
```

Update:

```bash
git pull
docker build -t tradingbot-frontend ./frontend
docker rm -f tradingbot-frontend
docker run -d --name tradingbot-frontend \
  -p 8080:8080 \
  -e TRADINGBOT_API_BASE_URL=https://your-bridge-or-api.example.com \
  tradingbot-frontend
```

Only `TRADINGBOT_API_BASE_URL` belongs in the cloud frontend environment. Broker/API credentials must stay off the cloud host.

## Windows PC Backend Container

Install Docker Desktop, clone the private repo, then from the repo root:

```powershell
Copy-Item .env.example .env
```

Edit `.env` with the private admin values and local backend account email. Then:

```powershell
docker compose build backend
docker compose up -d backend
docker compose logs -f backend
```

Health check:

```powershell
curl.exe http://localhost:7070/api/system/health
```

Before updates:

```powershell
docker compose stop backend
docker run --rm -v trading_bot_backend_data:/data -v ${PWD}:/backup alpine sh -c "cp /data/tradingbot.db /backup/tradingbot-backup-$(date +%Y%m%d-%H%M%S).db"
git pull
docker compose build backend
docker compose up -d backend
```

After restart, check health and keep live order submission disarmed until readiness checks pass.

## GitHub Workflow

Recommended:

```bash
git add .
git commit -m "Prepare private auth and container launch units"
git remote add origin git@github.com:<owner>/<private-repo>.git
git push -u origin main
```

Keep the repository private. Do not commit `.env`, local DB files, logs, market-data CSVs, or broker credentials.
