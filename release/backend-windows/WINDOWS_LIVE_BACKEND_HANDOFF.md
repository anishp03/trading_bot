# Windows Live Backend Handoff

This handoff package is for moving the broker-facing backend and the current trading DB to the Windows PC that will run the backend.

Do not upload the live DB, `.env`, broker keys, or logs to GitHub or a cloud frontend host.

## Package Contents

- `tradingbot-backend.jar`: backend application
- `run-backend-release.ps1`: local backend runner
- `install-windows-scheduled-task.ps1`: installs backend as a startup scheduled task
- `uninstall-windows-scheduled-task.ps1`: removes the scheduled task
- `bootstrap-windows-pc.ps1`: installs Windows dependencies
- `.env.example`: environment template
- `data/tradingbot.db`: current SQLite trading DB backup

## Install On The Windows PC

1. Unzip the handoff package on the Windows PC.
2. Open PowerShell as Administrator.
3. If Java 17 is not installed, run:

```powershell
.\bootstrap-windows-pc.ps1
```

4. Install the backend service:

```powershell
.\install-windows-scheduled-task.ps1
```

The installer copies the backend into:

```text
C:\TradingBot\backend
```

If `C:\TradingBot\backend\data\tradingbot.db` does not already exist, the installer copies the packaged DB there. If a DB already exists, it keeps the existing DB and does not overwrite live trading state.

## Configure

Open:

```text
C:\TradingBot\backend\.env
```

Use conservative local defaults first:

```text
TRADINGBOT_BIND_HOST=127.0.0.1
TRADINGBOT_PORT=7070
TRADINGBOT_DEFAULT_ACCOUNT_EMAIL=patelanish203@gmail.com
TRADINGBOT_CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080
```

Keep broker/API credentials local to this Windows PC. Do not put them in the frontend cloud host.

## Verify

Check the scheduled task:

```powershell
Get-ScheduledTask -TaskName TradingBotBackend
```

Check backend health:

```powershell
Invoke-RestMethod http://localhost:7070/api/system/health
```

Check logs:

```text
C:\TradingBot\backend\logs\backend.out.log
C:\TradingBot\backend\logs\backend.err.log
```

## Update Later

Before updating the backend, stop it and back up the DB:

```powershell
Stop-ScheduledTask -TaskName TradingBotBackend
Copy-Item C:\TradingBot\backend\data\tradingbot.db C:\TradingBot\backend\data\tradingbot-backup-$(Get-Date -Format yyyyMMdd-HHmmss).db
```

Then replace `tradingbot-backend.jar`, start the task again, and verify health:

```powershell
Start-ScheduledTask -TaskName TradingBotBackend
Invoke-RestMethod http://localhost:7070/api/system/health
```

Keep live order submission disarmed until readiness checks pass.
