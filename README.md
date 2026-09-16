# Trading Bot

Full-stack futures research and trading application with a React frontend, a
Java HTTP API, SQLite persistence, and Topstep/ProjectX connectivity. The Git
repository starts at this directory and owns the editable applications plus
their operating documentation and tooling.

> **Safety:** a practice account is still order-capable. Starting the live bot
> with `executionMode=TOPSTEPX` can cancel, flatten, submit, and manage broker
> orders. Use the realtime subscriber or `SIMULATED` mode when a demonstration
> must not submit orders.

## Repository map

```text
SoftwareProject/
├── AGENTS.md                  repository-wide engineering and safety rules
├── README.md                  architecture and developer orientation
├── run.sh                     local verification backend + frontend launcher
├── production_backend/        tracked canonical backend source
│   ├── README.md              backend ownership, boot, routes, and tests
│   ├── pom.xml                Maven build and Java dependencies
│   ├── resources/             application resources
│   ├── src/                   flattened production Java sources
│   └── tests/                 flattened JUnit test sources
├── frontend/                  tracked React/Vite app and hosted API proxy
├── scripts/                   local, deployment, and macOS operating scripts
├── research/                  quarantined manual Java research runners
├── ProjectBrain/              project knowledge vault and local RAG system
├── dev_runtime/               ignored local-verification state
├── shared_runtime/            ignored live SQLite, market data, and caches
└── live_backend/              ignored read-only live deployment
```

There is no nested `trading_bot` repository and no second editable backend.
`production_backend` is the source application used for local changes and
verification; despite its name, it is not the running live deployment.
`live_backend` is produced only through the approved promotion mechanism and
must not be edited by hand.

## End-to-end architecture

```text
React browser UI
    |
    | local: http://127.0.0.1:7071/api/*
    | hosted: same-origin /api/*
    v
Cloudflare Pages Function (hosted only)
    |
    | BACKEND_API_ORIGIN through the configured tunnel/access policy
    v
Javalin HTTP API
    |
    +--> FuturesManager: presets, signals, backtests, risk, live coordination
    +--> FuturesConnectionManager: ProjectX REST auth/accounts/bars/orders
    +--> ProjectXRealtimeManager: SignalR quotes, trades, depth, persistence
    +--> DatabaseManager/FuturesMarketDataStore: SQLite state and market data
    +--> BackendUpdateService: approved update-script handoff
    |
    v
isolated development state or protected shared live runtime
```

The hosted frontend does not contain the backend. Its Pages Function forwards
requests to the configured backend origin. Cloudflare provides the public edge
and tunnel path; the Java process still runs separately on the host.

### Languages and tools

| Layer | Technology |
|---|---|
| Frontend | JavaScript, React 19, Vite 7, React Router, Lightweight Charts |
| Hosted proxy | Cloudflare Pages Function using the Fetch API |
| Backend | Java 8 target, Javalin 4.6, Maven, Microsoft SignalR client |
| Persistence | SQLite through `sqlite-jdbc`; schema is initialized by Java owners |
| Tests | JUnit 5/Jacoco and Node's built-in test runner; ESLint for frontend lint |
| Operations | Bash, macOS LaunchAgent, Cloudflare tunnel, shaded executable JAR |
| Project memory | Python standard library, SQLite FTS5, Markdown vault |

## Backend ownership

Start with [`production_backend/README.md`](production_backend/README.md). The
shortest useful walk through the current request path is:

1. `src/MainServer.java` creates managers, middleware, and route groups.
2. `src/*Routes.java` translates HTTP requests and responses.
3. `src/FuturesManager.java` owns the current strategy/backtest/risk/live pipeline.
4. `src/FuturesConnectionManager.java` talks to the ProjectX REST API.
5. `src/ProjectXRealtimeManager.java` owns SignalR subscriptions and events.
6. `src/DatabaseManager.java` and `src/FuturesMarketDataStore.java` own SQLite access.
7. `src/RuntimePaths.java` selects isolated development or protected live state.

`FuturesManager.java` is still a large mixed-responsibility owner. Its future
separation must preserve one strategy path for backtest and live operation;
moving files alone must not change signals, risk, sizing, timing, or order flow.

## Runtime location and ownership

Local verification defaults to ignored state under `dev_runtime/`. The live
deployment uses protected state under `shared_runtime/`, including its canonical
database at:

```text
/Users/anishpatel/Documents/SoftwareProject/shared_runtime/db/tradingbot.db
```

Java schema ownership is currently distributed across `DatabaseManager`,
`FuturesManager`, and `FuturesMarketDataStore`; there is not yet a separate
migration/entity layer. Never commit or casually copy SQLite DB/WAL/SHM files,
account state, trade caches, market data, logs, or backups.

## Local verification versus live

| Concern | Local verification | Live deployment |
|---|---|---|
| Backend code | tracked `production_backend/` | promoted copy under `live_backend/backend/` |
| Backend port | `127.0.0.1:7071` | `127.0.0.1:7070` |
| Runtime state | ignored `dev_runtime/` | ignored `shared_runtime/` |
| Frontend | local Vite from `frontend/` | hosted frontend through Cloudflare |
| Changes | edit and verify in the root repo | promote only through `scripts/update-live-backend.sh` |

The updater builds `production_backend`, preserves shared runtime state, copies
the release into `live_backend`, records the canonical updater path, and controls
the live restart only when explicitly run. The frontend's Update Backend action
calls the Java update endpoint, which delegates to that script; it is not a Git
deployment mechanism.

## Main frontend entry points

| Concern | File |
|---|---|
| Routing | `frontend/src/App.jsx` |
| Live workspace | `frontend/src/pages/FuturesLive.jsx` |
| API selection and dev/live write guard | `frontend/src/utils/api.js` |
| Hosted API proxy | `frontend/functions/api/[[path]].js` |

Local Vite calls port `7071` by default. A development frontend deliberately
rejects non-read requests when configured directly against local live port
`7070`.

## Build and test

Backend JUnit tests live directly under `production_backend/tests/`. Frontend unit
tests live under `frontend/tests/unit/`. Historical Java programs with `main()`
entry points are quarantined under [`research/`](research/README.md)
and are not compiled by the normal Maven test lifecycle.

```bash
(cd production_backend && ./mvnw -q -DskipTests compile)
(cd production_backend && ./mvnw -q test)
(cd frontend && npm test)
(cd frontend && npm run lint)
(cd frontend && npm run build)
```

The current full backend suite contains 192 tests and uses portable checked-in
fixtures for its historical-data contracts. A release is not accepted unless
the full suite and clean package both pass with zero failures and errors.

## Project memory

The active retrieval system lives under `ProjectBrain/RAG/`; its durable source
material lives locally under `ProjectBrain/Vault/`. The private vault, generated
indexes, exports, caches, and current-chat scratch state stay out of Git. Follow
`AGENTS.md` for the mandatory startup and closeout workflows.

## Launch local verification

From the repository root, launch the isolated backend and frontend together:

```bash
./run.sh
```

Or start them separately:

```bash
./scripts/run-backend.sh
(cd frontend && npm run dev -- --host 127.0.0.1 --port 5174)
```

Provider tracking without broker submission uses the dedicated realtime
endpoints on port `7071`. A visible strategy loop can use
`executionMode=SIMULATED`. Do not use the live workspace Start action for a
no-order demonstration: it selects `TOPSTEPX` and is order-capable.

Credentials, data entitlements, deployment, promotion, and live restart remain
separate owner-authorized actions.
