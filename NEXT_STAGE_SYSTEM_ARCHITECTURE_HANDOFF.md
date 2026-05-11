# Next Stage System Architecture Handoff

Date: 2026-05-07  
Workspace: `/Users/anishpatel/Documents/SoftwareProject/trading_bot`

## Purpose

This is the starting handoff for the next development chat. The current project is moving from local development into a private live system architecture:

- Cloud-hosted frontend UI.
- Private login/account gate, with no public account creation.
- Always-on Windows PC running the broker-facing backend.
- Local backend-owned trading database on the PC at first.
- A secure bridge between the cloud UI and the PC backend.
- A separate Mac/Codex workflow for strategy development and backtests that does not interrupt the live bot.

The system is not ready for unattended live deployment yet. The next stage should build the launch architecture, account controls, containers, and operating workflow before any 24/7 production use.

## Latest Code State

Completed before this handoff:

- Removed the temporary fake/demo chart trade generator from `frontend/src/pages/FuturesLive.jsx`.
- The chart trade UI remains in place for real bot trades only.
- Trade dots/popovers should still toggle open and closed when real live trade decisions exist.
- Removed demo-specific chart popover styling from `frontend/src/index.css`.
- Removed the frontend "Create Account" button from `frontend/src/pages/Login.jsx`.
- Disabled the backend public registration endpoint by default. `/api/account/register` now returns `403` unless `TRADINGBOT_ENABLE_PUBLIC_REGISTRATION=true` is deliberately set.

Important safety state:

- Do not submit, cancel, flatten, or modify broker orders unless the user explicitly approves the exact action.
- Read-only ProjectX realtime data testing is acceptable only when the user has approved it.
- Keep broker/API secrets local to the backend machine. Do not put them in cloud frontend env vars, client bundles, screenshots, or docs.

## Target Architecture

Recommended initial production shape:

```text
User Browser
  |
  | HTTPS
  v
Cloud Server
  - Frontend UI
  - Private auth/account gate
  - Optional auth/session DB
  - Optional relay/API gateway
  |
  | secure outbound bridge session
  v
Windows 24/7 PC
  - Java backend
  - Strategy engine
  - ProjectX/TopstepX connection
  - Order/risk controls
  - Trading SQLite DB
  - Logs/backups

Mac / Codex Workstation
  - Development branch work
  - Backtests and strategy research
  - Pushes code to GitHub
```

The Windows PC should initiate outbound connectivity to the cloud bridge. Avoid public port forwarding to the PC backend. The default backend bind should remain `127.0.0.1` until a secure bridge or private network layer is implemented.

## Database Decision

Use two logical database roles:

- Trading DB: lives with the backend on the Windows PC for Phase 2. This keeps live strategy state, trades, orders, broker sync data, and logs close to the process that manages risk.
- Web/Auth DB: optional cloud DB for private login users, sessions, roles, and UI preferences.

Do not make the cloud frontend write directly to the trading DB. If the UI needs trading data, it should request it through the bridge/API layer and the backend should remain the source of truth.

SQLite on the Windows PC is acceptable for the first always-on backend deployment if backups and shutdown procedures are handled carefully. Later, Postgres can be added if multi-client access, cloud reporting, or long-term analytics need a stronger database service.

## Secure Bridge Requirements

The cloud UI cannot safely call `http://<home-pc>:7070` over the public internet. Build one of these:

- Preferred: backend bridge client running on the PC that maintains an outbound TLS/WebSocket connection to the cloud.
- Acceptable for early private testing: Tailscale/WireGuard private network, with strict device access and no public exposure.
- Avoid: router port forwarding, public `7070`, or putting broker credentials on the cloud server.

Bridge minimum requirements:

- Authenticated PC identity.
- Authenticated user sessions in the cloud UI.
- Command allowlist for sensitive actions.
- Clear distinction between read-only status calls and order-affecting commands.
- Audit log for start/stop/arm/disarm/copy-to-live actions.
- Rate limits and replay protection.
- Backend must keep managing risk if the cloud UI disconnects.

## Phase 1: Private Launch UI And Account System

Goal: restore the account-based web app structure before launch.

Required work:

- Re-establish login functionality and route protection.
- Keep the public "Create Account" path removed.
- Keep public registration disabled unless a deliberate admin-only user creation flow replaces it.
- Seed or manually create private users only.
- Add a clear account/session model for the private operator.
- Add roles, even if minimal at first:
  - `admin`: can configure and deploy.
  - `operator`: can view and operate the bot.
  - `viewer`: read-only future option.
- Ensure auth state survives refresh but expires safely.
- Hide broker-sensitive data from the frontend bundle.

UI sequence QA before launch:

- Login and logout.
- Direct navigation to protected routes while logged out.
- Futures strategy/backtest pages.
- Copy Backtest To Live flow.
- Live readiness checks.
- Read-only realtime feed start/stop.
- Symbol and timeframe switching on the chart.
- Chart warmup/building state.
- Real trade dot/popover behavior when actual trade decisions exist.
- Start/stop/disarm/order-arm states.
- Error states when backend/bridge is offline.
- Desktop and mobile layout sanity.

Phase 1 exit criteria:

- A private user can log in and use the app.
- No public signup button remains.
- No unauthenticated access to futures live/backtest/control pages.
- UI flows are checked for the main launch sequences.

## Phase 2: Containers And Deployment Units

Goal: split the system into portable components that can be launched from GitHub on the cloud server and Windows PC.

Frontend container:

- Build the Vite frontend into static assets.
- Serve with Nginx, Caddy, or a small static server container.
- Configure runtime/public env for the cloud API or bridge URL only.
- Do not include broker/API secrets.
- Provide commands for:
  - build
  - local run
  - cloud run
  - logs
  - update/redeploy

Backend container:

- Package the Java backend with Java 17.
- Mount persistent volumes for:
  - SQLite DB
  - logs
  - config/env files
  - market data if needed
- Provide GitHub-friendly instructions:
  - clone repo
  - install Docker/Desktop or runtime
  - create `.env`
  - start container
  - check health
  - stop safely
  - pull updates and restart

Database in Phase 2:

- Keep the authoritative trading DB on the Windows PC with the backend container.
- Add backups before every update.
- Cloud DB, if used, should be limited to auth/session/relay metadata.

Phase 2 exit criteria:

- Frontend can run as a cloud-deployable container.
- Backend can run as a Windows PC container or service with persistent DB/log volumes.
- Startup instructions are copy/paste clear.
- Backend is not publicly exposed.

## Phase 3: Strategy Update And Backtest Workflow

Goal: allow Codex-driven strategy work on the Mac without interrupting the live PC bot.

Recommended workflow:

1. Live PC stays on a stable branch or release tag.
2. Mac/Codex uses a separate branch for strategy development.
3. Run backtests locally on the Mac.
4. Review results honestly, including drawdown, trade count, commissions, and rule compliance.
5. Commit and push changes to GitHub.
6. Promote a release tag or merge only after review.
7. On the Windows PC:
   - confirm the bot is not in a risky state,
   - disarm new entries,
   - verify positions are flat unless deliberately managing an open trade,
   - stop the backend,
   - back up the DB,
   - git pull or fetch the release,
   - rebuild/restart the container,
   - run health checks,
   - restart in read-only or shadow mode first,
   - re-arm only after sanity checks pass.

Future improvement:

- Add blue/green backend handoff or shadow strategy comparison so a new strategy can warm up beside the live one before taking control.

Phase 3 exit criteria:

- There is a written and tested update workflow.
- Strategy research can happen on the Mac without touching the live backend.
- The PC update path includes stop, backup, pull, restart, health check, and rollback.

## Phase 4: Final Launch Handoff

Goal: produce the final operator instructions for launch and ongoing maintenance.

Final handoff should include:

- Cloud server setup instructions.
- Domain/DNS/TLS setup.
- Frontend container launch instructions.
- Private login setup instructions.
- Windows PC backend container launch instructions.
- Local DB backup and restore instructions.
- Bridge connection instructions.
- Secrets checklist.
- Health check commands.
- Start/stop/restart runbook.
- Update/rollback runbook.
- Monitoring and alerting checklist.
- What to do if the UI disconnects.
- What to do if ProjectX realtime disconnects.
- What to do before enabling any order submission.

Phase 4 exit criteria:

- The user can launch the cloud UI.
- The user can launch the backend on the Windows PC.
- The cloud UI can securely read/control the backend through the bridge.
- The user can safely push strategy updates from Mac/Codex to GitHub and deploy them to the PC.

## Immediate Next Chat Starting Tasks

Start the next chat from this file and then:

1. Inspect current frontend auth/login code and routes.
2. Remove public account creation UI.
3. Re-enable private login/account gating.
4. Decide where the cloud auth/session DB will live.
5. Decide whether the first bridge is a true outbound relay or a private network layer.
6. Add frontend and backend container files.
7. Write Windows PC container instructions.
8. Write cloud frontend launch instructions.
9. Run a full UI sequence QA pass before the first private launch.

## 2026-05-07 Auth And Container Progress

Started Phase 1/2 implementation:

- Backend now supports expiring bearer sessions through an `AccountSession` table.
- Login returns a private session token, email, role, and expiration.
- `/api/session` validates refresh-time auth state and `/api/logout` revokes the current session.
- API routes are authenticated by default except `/api/login`, `/api/account/register`, and system health/version.
- Public registration remains disabled unless `TRADINGBOT_ENABLE_PUBLIC_REGISTRATION=true` is deliberately set.
- Accounts now have a `role` column with `admin`, `operator`, and `viewer` semantics.
- Fresh private deployments can seed the first admin with `TRADINGBOT_BOOTSTRAP_ADMIN_EMAIL` and `TRADINGBOT_BOOTSTRAP_ADMIN_PASSWORD`.
- Frontend routes are protected again, auth survives refresh via local storage, and logout is available in the top bar.
- Added frontend and backend Dockerfiles, runtime frontend API config, loopback-only local `docker-compose.yml`, and `CONTAINER_DEPLOYMENT.md`.

Still needed:

- Choose/build the secure bridge or private network path before cloud UI control is allowed.
- Run full UI sequence QA against a seeded private user.
- Create/push the private GitHub repo once GitHub access is available.

## 2026-05-11 Security Hardening Progress

Completed the launch-blocking auth and secret-handling pass:

- Account passwords are now stored as PBKDF2 hashes instead of plaintext.
- Legacy plaintext account passwords migrate to hashes after a successful login.
- Login attempts are rate-limited per email/IP pair.
- Sensitive account, broker, and futures connection writes now use POST form bodies instead of query strings.
- Alpaca and futures API keys can still be edited through the web UI, but stored key values are not returned to the browser.
- Settings read APIs return only saved flags and masked previews, such as `AKTE...7890`.
- Frontend reveal controls for stored broker secrets were removed.
- Public registration remains disabled by default.
- CORS is restricted to configured frontend origins through `TRADINGBOT_CORS_ORIGINS`.
- Public version metadata no longer exposes backend bind host, port, or database path.

Verification:

- `./mvnw package`: passed, 31 backend tests.
- `npm run build`: passed.
- `npm run lint`: 0 errors, same 5 existing React hook dependency warnings.
- API smoke: unauthenticated protected account request returned `401`, public registration returned `403`, broker key save required auth, broker settings returned only masked previews.
- Browser smoke: direct logged-out navigation to `/settings` redirected to `/login`.

Still needed:

- Use a secure bridge/private network before exposing cloud UI control of the PC backend.
- Create/push the private GitHub repo when GitHub access is available.
- Run one final end-to-end UI QA on the real launch machine and frontend origin.
