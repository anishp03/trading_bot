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

That DB is persistent runtime state, not release code. A normal backend update must leave it in place. If a GitHub pull replaced the DB every time, it could erase new live trades, order ledgers, broker sync state, risk/audit events, and settings created after the DB was last pushed.

The same rule applies to `.env`. The Windows PC should have one local installed file:

```text
C:\TradingBot\backend\.env
```

Backend updates should not overwrite it. The repo carries `.env.example` only. If secrets need to be moved to a new machine, use a separate private handoff package or an encrypted transfer, then keep the installed `.env` local on the backend PC.

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

## DB Changes During Updates

Do not ship the whole live DB for routine updates.

There are three different DB cases:

- First machine setup: use the separate DB handoff ZIP and place `data\tradingbot.db` on the Windows PC once.
- Normal backend update: pull `LiveBackend`, replace the JAR/scripts, and keep the existing DB untouched.
- Schema/config migration: include migration logic in backend code or a tracked migration script under `LiveBackend\migrations`, then apply it to the existing DB after a backup.

The update script should always back up the installed DB before applying a new backend release.

The update script should also keep the installed `.env` untouched. If `.env.example` changes, review it manually and copy only the new non-secret config keys you actually need.

## Manual Health Check

```powershell
Invoke-RestMethod http://localhost:7070/api/system/health
```

## Safety Rule

Do not expose backend port `7070` publicly. Remote UI access should go through Tailscale, Cloudflare Tunnel/Access, or a purpose-built outbound bridge.
