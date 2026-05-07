# Old PC 24/7 Backend Deployment

This is the recommended path for moving the broker-facing backend off the MacBook and onto an always-on local PC.

For the next architecture phase, read `NEXT_STAGE_SYSTEM_ARCHITECTURE_HANDOFF.md` first.

## Target Shape

- Old PC runs the Java backend locally.
- PC is connected by wired Ethernet if possible.
- PC is plugged into reliable power, ideally through a UPS.
- On Windows, backend runs from the packaged Windows install/Scheduled Task flow and restarts automatically.
- On Linux, backend can run as a `systemd` service and restart automatically.
- Broker/API traffic originates from this PC, not from cloud/VPS/VPN/proxy infrastructure.
- Cloud UI/relay, when built, should only talk to a local outbound bridge.

## 2026-05-07 Next-Stage Role

The old Windows PC is now the intended always-on backend node for the private launch architecture.

Recommended Phase 2 shape:

- PC runs the backend container or packaged backend service.
- PC keeps the authoritative trading SQLite DB beside the backend.
- PC owns ProjectX/TopstepX credentials and order/risk control.
- Cloud server hosts the frontend UI and private account/session layer.
- Cloud server does not host broker credentials.
- Cloud UI talks to the PC through a secure bridge or private network layer, not through public port forwarding.

Initial DB recommendation:

- Keep `tradingbot.db` on the PC with the backend.
- If the cloud app needs login, use a separate cloud auth/session DB.
- Do not split live trading writes between cloud and PC until a proper sync design exists.

Safe update workflow for the PC:

1. Disarm new entries.
2. Verify whether any live position is open.
3. Stop the backend only when it is safe to do so.
4. Back up the DB.
5. Pull the approved GitHub release or branch.
6. Rebuild/restart the backend container or service.
7. Run health checks.
8. Restart in read-only or shadow mode before re-arming orders.

## Recommended OS

Use Windows if that is what the old PC already has. Ubuntu Server or Debian are still good alternatives later, but the current scripts support Windows directly.

Minimum practical setup:

- Java 17 or newer
- Git
- curl / ca-certificates
- sqlite3 for local inspection and backup checks, optional
- Wired Ethernet
- Sleep/hibernate disabled
- Static DHCP reservation on router
- Automatic security updates enabled
- SSH enabled only on trusted LAN
- No public port-forwarding to backend port `7070`

## Windows Dependency Install

Open PowerShell as Administrator from the cloned repo:

```powershell
cd C:\path\to\trading_bot
.\scripts\bootstrap-windows-pc.ps1
```

That installs through `winget`:

- Git for Windows
- Microsoft OpenJDK 17
- SQLite command-line tools, optional but useful

If `winget` is not available, the script prints manual download links for:

- Git for Windows
- Microsoft OpenJDK 17
- SQLite tools
- Node.js LTS, optional frontend only

Optional frontend dependency install:

```powershell
.\scripts\bootstrap-windows-pc.ps1 -WithFrontend
```

Only install frontend dependencies on the old PC if you plan to build or serve the React UI there. The 24/7 broker-facing backend does not require Node/npm.

## Windows Git Pull Workflow

First clone:

```powershell
git clone <your-repo-url> trading_bot
cd trading_bot
.\scripts\bootstrap-windows-pc.ps1
```

Build and install:

```powershell
.\scripts\build-backend-release.ps1
cd .\release\backend-windows
.\install-windows-scheduled-task.ps1
```

Update later:

```powershell
cd C:\path\to\trading_bot
git pull
.\scripts\build-backend-release.ps1
cd .\release\backend-windows
.\install-windows-scheduled-task.ps1
```

Windows service check:

```powershell
Get-ScheduledTask TradingBotBackend
Invoke-RestMethod http://localhost:7070/api/system/health
Invoke-RestMethod http://localhost:7070/api/futures/live/order-arm
```

Default network binding:

```text
TRADINGBOT_BIND_HOST=127.0.0.1
```

That means the backend listens only on the Windows PC itself. This is the safest default. If you later need another computer on your home LAN to reach the backend directly, change `C:\TradingBot\backend\.env` to `TRADINGBOT_BIND_HOST=0.0.0.0` and add a Windows Firewall rule limited to your private LAN only. Do not port-forward it to the internet.

Windows logs:

```text
C:\TradingBot\backend\logs\backend.out.log
C:\TradingBot\backend\logs\backend.err.log
```

The backend installs to:

```text
C:\TradingBot\backend
```

The local DB lives at:

```text
C:\TradingBot\backend\data\tradingbot.db
```

The Windows Scheduled Task runs as `SYSTEM` at startup and restarts the backend if it exits.

## Linux Dependency Install

If you clone this repo on the PC, run:

```bash
cd /path/to/trading_bot
sudo ./scripts/bootstrap-linux-pc.sh
```

That installs:

- `openjdk-17-jdk` for running/building the backend
- `git` for clone/pull
- `curl` and `ca-certificates` for health checks and HTTPS package downloads
- `sqlite3` for local DB inspection and backup verification
- `tar` and `unzip` for Maven wrapper downloads/build tooling

The Maven wrapper `backend/mvnw` downloads Maven automatically, so a separate Maven install is not required.

Optional frontend dependencies:

```bash
sudo WITH_FRONTEND=1 ./scripts/bootstrap-linux-pc.sh
```

Only install frontend dependencies on the old PC if you plan to build or serve the React UI there. The 24/7 broker-facing backend does not require Node/npm.

## Linux Git Pull Workflow

First clone:

```bash
git clone <your-repo-url> trading_bot
cd trading_bot
sudo ./scripts/bootstrap-linux-pc.sh
```

Update later:

```bash
cd /path/to/trading_bot
git pull
./scripts/build-backend-release.sh
cd release/backend
sudo ./install-linux-systemd-backend.sh
```

## Build Release On Mac

From the project root:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot
./scripts/build-backend-release.sh
```

This creates:

```text
release/backend/tradingbot-backend.jar
release/backend-windows/tradingbot-backend.jar
release/backend/run-backend-release.sh
release/backend/tradingbot-backend.service
release/backend/install-linux-systemd-backend.sh
release/backend-windows/install-windows-scheduled-task.ps1
release/backend/.env.example
release/backend-windows/.env.example
```

## Install On Linux PC

Copy the contents of `release/backend` to the old PC, then on that PC:

```bash
cd /path/to/copied/release/backend
sudo ./install-linux-systemd-backend.sh
```

The service installs to:

```text
/opt/tradingbot/backend
```

Service commands:

```bash
sudo systemctl status tradingbot-backend
sudo systemctl restart tradingbot-backend
sudo journalctl -u tradingbot-backend -f
```

Health check:

```bash
curl http://localhost:7070/api/system/health
curl http://localhost:7070/api/futures/live/order-arm
```

## Data And Secrets

The backend service stores its database at:

```text
/opt/tradingbot/backend/data/tradingbot.db
```

Local environment config lives at:

```text
/opt/tradingbot/backend/.env
```

Keep `.env` and database backups off Git and out of cloud UI hosting.

## PC Power Settings

Disable sleep and hibernation:

```bash
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
```

Check service survives reboot:

```bash
sudo reboot
```

After reboot:

```bash
systemctl status tradingbot-backend
curl http://localhost:7070/api/system/health
curl http://localhost:7070/api/futures/live/order-arm
```

The order-arm endpoint should report guarded after restart.

## Network Rules

- Do not expose `7070` to the internet.
- Do not port-forward the old PC backend.
- Do not route TopstepX/ProjectX broker traffic through a VPN, VPS, proxy, Tor, or cloud server.
- Keep broker credentials local to the PC.
- Use cloud only for a future read-only relay/monitoring layer.

## Still Required Before 24/7 Trading

- Broker order/fill/position reconciliation.
- Authoritative broker-ledger PnL.
- Alerting.
- Automated database backups and restore test.
- Startup preflight checks against broker state.
- Multi-week paper endurance test.
