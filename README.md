# Trading Bot Frontend

This repository branch is for the cloud-hosted frontend UI.

The live trading backend, SQLite DB, broker/API credentials, release bundles, and Windows backend handoff packages stay off GitHub and are managed separately on the backend machine.

## Local Development

```bash
cd frontend
npm install
npm run dev
```

Open:

```text
http://localhost:5173
```

## Frontend Environment

Copy the example file:

```bash
cp frontend/.env.example frontend/.env.local
```

Set `VITE_API_BASE_URL` to the backend API or private tunnel URL.

For local testing:

```text
VITE_API_BASE_URL=http://localhost:7070
```

For launch, this should point to the Cloudflare/Tailscale/bridge URL that reaches the backend PC through a private access layer.

## Build

```bash
cd frontend
npm run build
```

The Docker image serves the built frontend through Nginx and supports runtime API configuration through `TRADINGBOT_API_BASE_URL`.
