# Trading Bot Frontend Launch

This GitHub package is the cloud-facing frontend for the futures trading bot. The live backend, SQLite database, broker credentials, Topstep/ProjectX credentials, and backend release zip stay private and must not be pushed to GitHub.

## Current Baseline

- Final promoted portfolio backtest: `3154`
- Strategy label: `compose_lorb_e671_best_second_wave_third_wave_pruned`
- Result: `999 trades`, `$80,249.81` total profit, `75.28%` win rate, `2.90` profit factor
- Live strategy status: copied into the `LIVE` strategy slot and active in the backend DB snapshot

## Local Frontend

```bash
cd frontend
npm install
npm run dev
```

Open:

```text
http://localhost:5173
```

Create local frontend env:

```bash
cp frontend/.env.example frontend/.env.local
```

For same-machine backend testing:

```text
VITE_API_BASE_URL=http://localhost:7070
```

For basic remote frontend to backend testing:

```text
VITE_API_BASE_URL=https://api.your-domain.com
```

That API URL should point to a private backend tunnel or protected backend hostname. Do not put broker/API secrets in frontend env files.

For Cloudflare Pages launch, prefer same-origin frontend API calls through the Pages Function proxy:

```text
VITE_API_BASE_URL=https://app.tradingconsole.net
VITE_PRIMARY_ACCOUNT_EMAIL=patelanish203@gmail.com
VITE_PRIMARY_ACCOUNT_ROLE=admin
BACKEND_API_ORIGIN=https://api.tradingconsole.net
```

The browser calls `https://app.tradingconsole.net/api/*`; Cloudflare runs `frontend/functions/api/[[path]].js` and forwards the request server-side to the backend tunnel. This avoids cross-subdomain browser cookie/CORS friction.

Cloudflare Access is the login gate. The frontend redirects `/login` into the app and uses `VITE_PRIMARY_ACCOUNT_EMAIL` as the backend account key for stored broker/settings data, so users do not log in twice.

If the backend API is protected by Cloudflare Access service tokens, set these Cloudflare Pages environment variables too:

```text
CF_ACCESS_CLIENT_ID=<service-token-client-id>
CF_ACCESS_CLIENT_SECRET=<service-token-client-secret>
```

## Production Build

```bash
cd frontend
npm ci
npm run build
```

Build output:

```text
frontend/dist
```

This repo uses Vite, React 19, and React Router 7. The latest verified local build completed with Vite `7.3.3`.

## Cloudflare Pages Launch

Cloudflare Pages can deploy the frontend directly from GitHub. The official Cloudflare Vite guide uses:

- Build command: `npm run build`
- Build output directory: `dist`

Recommended Cloudflare Pages settings:

```text
Root directory: frontend
Build command: npm run build
Build output directory: dist
Node version: current Cloudflare default or Node 22 LTS
```

Environment variable:

```text
VITE_API_BASE_URL=https://app.tradingconsole.net
VITE_PRIMARY_ACCOUNT_EMAIL=patelanish203@gmail.com
VITE_PRIMARY_ACCOUNT_ROLE=admin
BACKEND_API_ORIGIN=https://api.tradingconsole.net
```

After each push to the GitHub branch connected to Cloudflare Pages, Cloudflare rebuilds and deploys the frontend.

Official reference:

- Cloudflare Pages Vite deployment: https://developers.cloudflare.com/pages/framework-guides/deploy-a-vite3-project/

## Backend Connection

The backend should run on the other PC on port `7070`, then be exposed through one of these:

- Cloudflare Tunnel with Access protection
- Tailscale/private VPN
- Local network only, if the frontend is opened on the same machine

For Cloudflare Tunnel, publish a hostname like:

```text
api.your-domain.com -> http://localhost:7070
```

Official references:

- Cloudflare Tunnel routing: https://developers.cloudflare.com/tunnel/routing/
- Cloudflare Tunnel as a Windows service: https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/local-management/as-a-service/windows/

## Repo Hygiene

Keep these out of GitHub:

- `backend/`
- `release/`
- `*.db`
- `.env`
- `.env.local`
- broker/API key material
- packaged backend zips

Backend release bundles are generated locally and moved to the trading PC outside GitHub. The canonical implementation handoff lives at `../Documents/ImplementationHandoff.md`.
