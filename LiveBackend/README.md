# LiveBackend

This folder is the backend-only transport package for the Windows PC that runs the live trading engine.

Use this when the backend PC needs the latest approved backend release without pulling the frontend, research files, local Mac workspace state, or historical market data.

## What This Folder Contains

- `tradingbot-backend.jar`: runnable backend release
- `run-backend-release.ps1`: starts the backend from `C:\TradingBot\backend`
- `install-windows-scheduled-task.ps1`: installs/starts the backend as a Windows startup scheduled task
- `uninstall-windows-scheduled-task.ps1`: removes the scheduled task
- `bootstrap-windows-pc.ps1`: installs Windows dependencies
- `.env.example`: backend env template

## What This Folder Does Not Contain

- live SQLite DB
- `.env` with real values
- broker/API keys
- logs
- frontend source/build output

The live DB belongs at:

```text
C:\TradingBot\backend\data\tradingbot.db
```

## First-Time Pull On The Windows PC

From PowerShell:

```powershell
mkdir C:\TradingBot
cd C:\TradingBot
git clone --filter=blob:none --sparse https://github.com/anishp03/trading_bot.git repo
cd repo
git sparse-checkout set LiveBackend
```

Then install:

```powershell
cd C:\TradingBot\repo\LiveBackend
.\install-windows-scheduled-task.ps1
```

If this is the first live machine setup, put the DB handoff package's `data\tradingbot.db` at:

```text
C:\TradingBot\backend\data\tradingbot.db
```

The installer will copy a packaged DB only if `LiveBackend\data\tradingbot.db` exists and no DB is already installed. It will not overwrite an existing live DB.

## Update Flow

Backend updates should be deliberate, not automatic on every push.

Expected flow:

1. Develop on the MacBook.
2. Build/promote a backend release into `LiveBackend`.
3. Push to GitHub.
4. On the Windows backend PC, pull only this folder.
5. Stop the backend scheduled task.
6. Back up `C:\TradingBot\backend\data\tradingbot.db`.
7. Copy the new JAR/scripts into `C:\TradingBot\backend`.
8. Restart the backend.
9. Check health.
10. Keep order submission disarmed until live readiness checks pass.

The one-command updater script will automate steps 4-9 later.

## Manual Health Check

```powershell
Invoke-RestMethod http://localhost:7070/api/system/health
```

## Safety Rule

Do not expose backend port `7070` publicly. Remote UI access should go through Tailscale, Cloudflare Tunnel/Access, or a purpose-built outbound bridge.
