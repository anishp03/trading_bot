# Operational Scripts

This folder contains only the operational scripts used by the current macOS
host, the isolated development environment, or the existing live deployment path.
Obsolete Linux and Windows packaging/service templates have been removed.

## Files

| File | Purpose | Important side effect |
|---|---|---|
| `run-backend.sh` | Run `production_backend/` on port `7071`. | Creates/updates isolated `dev_runtime/`. |
| `update-live-backend.sh` | Build, copy, and restart the deployed Java backend. | Live-mutating; run only with explicit promotion approval. |
| `run-live-backend.sh` | Supervise the deployed JAR on port `7070`. | Starts/restarts the live process and writes logs/PIDs. |
| `install-macos-backend-launchagent.sh` | Install the macOS always-on backend service. | Writes a LaunchAgent and invokes the live updater. |
| `uninstall-macos-backend-launchagent.sh` | Remove the backend LaunchAgent. | Stops/unloads that service. |

## Current path contract

- Tracked backend source: `production_backend`
- Isolated development state: `dev_runtime`
- Deployed backend code/JAR: `live_backend/backend`
- Canonical live state: `shared_runtime`
- Direct update entry point: `scripts/update-live-backend.sh`

The updater writes its canonical `scripts/` path into live configuration
whenever it performs an approved update. Until that cutover is deliberately
run, an older deployed process may still reference a removed historical path
and may not provide a working Update Backend button.

## Development

```bash
./scripts/run-backend.sh
```

Do not use the live updater, live runner, or service installers as part of
ordinary development verification.

## Live promotion boundary

`update-live-backend.sh` is a code-only promotion path. It builds
`production_backend/`, preserves the canonical runtime database and market data, copies
the source/JAR to the deployed location, updates the direct script path, and can
restart the live backend. A build does not authorize running it.

Before an approved promotion, compile/tests must be reviewed and runtime state
must be backed up through a consistent, separately authorized process. After a
promotion, verify health, release identity, selected account/config, data feed,
and the backend-update status endpoint.

No script in this folder is automatically executed by cloning, building,
testing, or opening the repository.
