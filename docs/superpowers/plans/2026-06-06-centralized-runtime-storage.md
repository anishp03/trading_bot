# Centralized Runtime Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the app's durable runtime data outside both `trading_bot/backend` and `live_backend/backend` so code promotion updates logic only and never silently replaces live data.

**Architecture:** Introduce one canonical runtime data root under `/Users/anishpatel/Documents/SoftwareProject/shared_runtime`, then route both dev and live backends to that root through explicit environment/system properties. Keep code, builds, logs, and launch config version-specific; centralize SQLite, futures market data, generated synthetic data, and app runtime caches. Make backend promotion code-only by default and add startup/health visibility so the active DB/data paths are impossible to miss.

**Tech Stack:** Java 8 backend, SQLite WAL, shell launch/update scripts, Javalin health/version routes, Maven/JUnit tests.

---

## Proposed System

### Centralized Outside Both Apps

Canonical root:

```text
/Users/anishpatel/Documents/SoftwareProject/shared_runtime/
  db/
    tradingbot.db
    tradingbot.db-wal
    tradingbot.db-shm
  market_data/
    1min/
    5min/
    30min/
    1hour/
    futures/
      1min/
      5min/
      15min/
      1hour/
      level2-synthetic/
      backups/
      topstepx_stage/
      status.properties
  data/
    live_trade_cache/
      <accountId>.json
  backups/
    db/
    market_data/
  locks/
  reports/
```

Centralized data:

- Main SQLite app DB: accounts, sessions, strategy settings, risk settings, futures connections, backtests, portfolio trades, live sessions, live signal decisions, broker/order ledger, realtime events, captured bars, historical Level 2 snapshots, market-data reconciliation rows.
- Market-data CSVs seeded from the live backend, including futures backtest data and legacy equity/stock cache data.
- Synthetic Level 2 files generated under `market_data/futures/level2-synthetic`.
- TopstepX import staging and market-data backups because those are currently relative to the futures data dir.
- Live trade cache JSON currently written under `backend/data/live_trade_cache`.

### Version-Specific To Dev Or Live

Remain separate:

- Source code: `/trading_bot` and `/live_backend/backend`.
- Build outputs: `target/`, jars, frontend `dist/`.
- Process logs: `trading_bot` dev terminal logs and `live_backend/logs`.
- Launch files and local process config: `run.sh`, `live_backend/bin/run-live-backend.sh`, LaunchAgent, `.build.env`.
- Secrets/launch settings in `.env` files. The DB may already contain broker connection secrets in `FuturesConnections`; do not print or document those values.
- Temporary research copied DBs under `backend/target/**`.

### Runtime Rules

- Dev and live both point to the same canonical DB and futures data only when explicitly configured.
- No update/promotion script may copy `tradingbot.db`, `market_data`, or `data/live_trade_cache` from dev into live.
- Live backend should not be restarted automatically by this migration unless explicitly approved.
- SQLite remains the storage engine. WAL stays enabled. App startup must show the resolved DB path and futures data path in `/api/system/health`.
- If both dev and live run at the same time, they can read the same DB. Writes are serialized by SQLite. However, large backtests/imports should not run while live trading is actively managing positions unless we add a lock/guard first.

## User Decisions Locked In - June 6, 2026

1. **Canonical DB seed:** use live DB only.
   - Seed from `/Users/anishpatel/Documents/SoftwareProject/live_backend/backend/tradingbot.db`.
   - Do not merge dev DB rows into the canonical DB during migration.

2. **Canonical market-data seed:** use live market data only.
   - Seed from `/Users/anishpatel/Documents/SoftwareProject/live_backend/backend/market_data`.
   - Do not merge dev market data into the canonical market-data folder during migration.

3. **Dev imports into main DB:** block dev market-data imports into centralized shared runtime storage.
   - Dev may generate backtests only.
   - Market-data update/import/rebuild endpoints are blocked from dev when using shared runtime storage.

4. **Active live guard:** block guarded mutations when live trading is active.
   - Backtest generation is blocked when the latest live session is `RUNNING` or unresolved `PENDING_BROKER_RECONCILE` ledger rows exist.
   - Market-data update/import/rebuild is also blocked while live trading is active.

5. **Live trade cache centralization:** centralize now because live trade cache is part of the broker/reconciliation evidence trail.

## File Map

Modify:

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/DatabaseManager.java`
  - Resolve DB path from system property, environment variable, then centralized runtime root, then legacy fallback.
  - Ensure parent directory exists before opening SQLite.

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/RuntimePaths.java`
  - Add `runtimeRoot()`, `databasePath()`, `futuresDataDir()`, `liveTradeCacheDir()`, and path-source helpers.
  - Support `tradingbot.runtimeRoot` / `TRADINGBOT_RUNTIME_ROOT`.
  - Support `TRADINGBOT_DB_PATH`, `TRADINGBOT_FUTURES_DATA_DIR`, and `TRADINGBOT_LIVE_TRADE_CACHE_DIR`.

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/FuturesManager.java`
  - Replace hard-coded `liveTradeCacheDir()` logic with `RuntimePaths.liveTradeCacheDir()`.
  - Update user-facing storage description text so it does not say futures data lives under `backend/market_data/futures` when centralized.

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/SystemRoutes.java`
  - Add resolved storage paths to `/api/system/health`.

- `/Users/anishpatel/Documents/SoftwareProject/run.sh`
  - Route dev backend to the central runtime root by default, while allowing override.

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/run-live-backend.sh`
  - Route live backend to the central runtime root by default.

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/update-live-backend.sh`
  - Make DB preservation the default.
  - Remove or gate dev DB snapshot promotion behind a loud explicit flag, not the default UI path.
  - Preserve central data dirs and never delete central cache/data.

Create:

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/test/java/com/tradingbot/RuntimePathsTest.java`
  - Unit tests for path resolution precedence.

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/test/java/com/tradingbot/DatabaseManagerPathTest.java`
  - Unit tests for env/system property DB path routing.

- `/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/prepare-shared-runtime.sh`
  - One-time migration helper that creates central dirs, checkpoints DBs, copies chosen canonical DB/data, and writes a manifest.

- `/Users/anishpatel/Documents/SoftwareProject/ProjectBrain/Vault/Decisions/Centralized Runtime Storage.md`
  - Durable architecture decision after implementation is verified.

## Task 1: Path Resolution Contract

**Files:**

- Modify: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/RuntimePaths.java`
- Create: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/test/java/com/tradingbot/RuntimePathsTest.java`

- [ ] **Step 1: Write failing tests for centralized path precedence**

Test cases:

```java
@Test
void runtimeRootDrivesDbFuturesAndTradeCacheDefaults() {
    System.setProperty("tradingbot.runtimeRoot", tempDir.toString());

    assertEquals(tempDir.resolve("db/tradingbot.db").toString(), RuntimePaths.databasePath());
    assertEquals(tempDir.resolve("market_data/futures").toString(), RuntimePaths.futuresDataDir());
    assertEquals(tempDir.resolve("data/live_trade_cache").toString(), RuntimePaths.liveTradeCacheDir());
}

@Test
void explicitPropertiesOverrideRuntimeRoot() {
    System.setProperty("tradingbot.runtimeRoot", tempDir.resolve("root").toString());
    System.setProperty("tradingbot.db.path", tempDir.resolve("custom/custom.db").toString());
    System.setProperty("tradingbot.futuresDataDir", tempDir.resolve("custom/futures").toString());
    System.setProperty("tradingbot.liveTradeCacheDir", tempDir.resolve("custom/cache").toString());

    assertEquals(tempDir.resolve("custom/custom.db").toString(), RuntimePaths.databasePath());
    assertEquals(tempDir.resolve("custom/futures").toString(), RuntimePaths.futuresDataDir());
    assertEquals(tempDir.resolve("custom/cache").toString(), RuntimePaths.liveTradeCacheDir());
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=RuntimePathsTest test
```

Expected: fail because `databasePath()` and `liveTradeCacheDir()` do not exist yet.

- [ ] **Step 3: Implement `RuntimePaths`**

Add explicit precedence:

1. Java system property.
2. Environment variable.
3. `tradingbot.runtimeRoot` / `TRADINGBOT_RUNTIME_ROOT`.
4. Legacy fallback.

- [ ] **Step 4: Run tests and verify pass**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=RuntimePathsTest test
```

Expected: pass.

## Task 2: DatabaseManager Uses The Central Path

**Files:**

- Modify: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/DatabaseManager.java`
- Create: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/test/java/com/tradingbot/DatabaseManagerPathTest.java`

- [ ] **Step 1: Write failing DB path tests**

Test cases:

```java
@Test
void databaseManagerUsesRuntimeRootDbPath() {
    System.setProperty("tradingbot.runtimeRoot", tempDir.toString());

    assertEquals(
        tempDir.resolve("db/tradingbot.db").toAbsolutePath().toString(),
        DatabaseManager.getDatabasePath()
    );
}

@Test
void databaseParentDirectoryIsCreatedOnInitialize() {
    Path dbPath = tempDir.resolve("nested/db/tradingbot.db");
    System.setProperty("tradingbot.db.path", dbPath.toString());

    DatabaseManager.initializeDatabase();

    assertTrue(Files.isRegularFile(dbPath));
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=DatabaseManagerPathTest test
```

Expected: fail because `DatabaseManager` does not use `RuntimePaths.databasePath()` and does not create parent dirs.

- [ ] **Step 3: Implement DB path routing**

Change `DatabaseManager.databaseFile()` to use `RuntimePaths.databasePath()`.

Before connecting, ensure:

```java
File parent = db.getParentFile();
if (parent != null && !parent.exists()) {
    parent.mkdirs();
}
```

- [ ] **Step 4: Run focused tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=DatabaseManagerPathTest,RuntimePathsTest test
```

Expected: pass.

## Task 3: Centralize Live Trade Cache

**Files:**

- Modify: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/FuturesManager.java`
- Test: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/test/java/com/tradingbot/RuntimePathsTest.java`

- [ ] **Step 1: Add a test for live trade cache path**

Test case:

```java
@Test
void liveTradeCacheDirCanBeOverridden() {
    System.setProperty("tradingbot.liveTradeCacheDir", tempDir.resolve("cache").toString());

    assertEquals(tempDir.resolve("cache").toString(), RuntimePaths.liveTradeCacheDir());
}
```

- [ ] **Step 2: Replace hard-coded cache path**

Change `FuturesManager.liveTradeCacheDir()` to:

```java
private static File liveTradeCacheDir() {
    return new File(RuntimePaths.liveTradeCacheDir());
}
```

- [ ] **Step 3: Run focused tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=RuntimePathsTest,FuturesManagerBrokerReconcileTest test
```

Expected: pass.

## Task 4: Expose Resolved Storage Paths In Health

**Files:**

- Modify: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/main/java/com/tradingbot/SystemRoutes.java`
- Modify or create test: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/src/test/java/com/tradingbot/MainServerTest.java`

- [ ] **Step 1: Add health JSON expectations**

Expected fields:

```json
{
  "storage": {
    "databasePath": ".../shared_runtime/db/tradingbot.db",
    "futuresDataDir": ".../shared_runtime/market_data/futures",
    "liveTradeCacheDir": ".../shared_runtime/data/live_trade_cache"
  }
}
```

- [ ] **Step 2: Implement health storage block**

Keep existing fields and append a `storage` object using `RuntimePaths`.

- [ ] **Step 3: Run route tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=MainServerTest test
```

Expected: pass.

## Task 5: Dev Launcher Uses Central Runtime By Default

**Files:**

- Modify: `/Users/anishpatel/Documents/SoftwareProject/run.sh`

- [ ] **Step 1: Add shared runtime defaults**

Add:

```bash
SHARED_RUNTIME_DIR="${TRADINGBOT_RUNTIME_ROOT:-$ROOT_DIR/shared_runtime}"
TRADINGBOT_DB_PATH="${TRADINGBOT_DB_PATH:-$SHARED_RUNTIME_DIR/db/tradingbot.db}"
TRADINGBOT_FUTURES_DATA_DIR="${TRADINGBOT_FUTURES_DATA_DIR:-$SHARED_RUNTIME_DIR/market_data/futures}"
TRADINGBOT_LIVE_TRADE_CACHE_DIR="${TRADINGBOT_LIVE_TRADE_CACHE_DIR:-$SHARED_RUNTIME_DIR/data/live_trade_cache}"
```

Pass all four env vars to the dev backend. Also pass Java system properties through Maven exec if needed so the child JVM cannot miss them:

```bash
./mvnw -q compile exec:java \
  -Dexec.mainClass="com.tradingbot.MainServer" \
  -Dtradingbot.runtimeRoot="$SHARED_RUNTIME_DIR" \
  -Dtradingbot.db.path="$TRADINGBOT_DB_PATH" \
  -Dtradingbot.futuresDataDir="$TRADINGBOT_FUTURES_DATA_DIR" \
  -Dtradingbot.liveTradeCacheDir="$TRADINGBOT_LIVE_TRADE_CACHE_DIR" &
```

- [ ] **Step 2: Shell syntax check**

Run:

```bash
bash -n /Users/anishpatel/Documents/SoftwareProject/run.sh
```

Expected: no output, exit 0.

## Task 6: Live Runner Uses Central Runtime By Default

**Files:**

- Modify: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/run-live-backend.sh`

- [ ] **Step 1: Add central defaults**

Replace internal defaults:

```bash
SHARED_RUNTIME_DIR="${TRADINGBOT_RUNTIME_ROOT:-$SOFTWARE_ROOT/shared_runtime}"
TRADINGBOT_DB_PATH="${TRADINGBOT_DB_PATH:-$SHARED_RUNTIME_DIR/db/tradingbot.db}"
TRADINGBOT_FUTURES_DATA_DIR="${TRADINGBOT_FUTURES_DATA_DIR:-$SHARED_RUNTIME_DIR/market_data/futures}"
TRADINGBOT_LIVE_TRADE_CACHE_DIR="${TRADINGBOT_LIVE_TRADE_CACHE_DIR:-$SHARED_RUNTIME_DIR/data/live_trade_cache}"
```

Pass:

```bash
"-Dtradingbot.runtimeRoot=$SHARED_RUNTIME_DIR"
"-Dtradingbot.liveTradeCacheDir=$TRADINGBOT_LIVE_TRADE_CACHE_DIR"
```

- [ ] **Step 2: Shell syntax check**

Run:

```bash
bash -n /Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/run-live-backend.sh
```

Expected: no output, exit 0.

## Task 7: Promotion Script Becomes Code-Only By Default

**Files:**

- Modify: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/update-live-backend.sh`

- [ ] **Step 1: Change default DB behavior**

Set:

```bash
PRESERVE_LIVE_DB=1
PROMOTE_DEV_DB=0
```

Replace `--preserve-live-db` with an explicit dangerous flag:

```bash
--promote-dev-db
```

Only call `snapshot_dev_database` and `install_promoted_database` when `PROMOTE_DEV_DB=1`.

- [ ] **Step 2: Make env point to shared runtime**

In `ensure_live_env`, write:

```bash
TRADINGBOT_RUNTIME_ROOT=$SOFTWARE_ROOT/shared_runtime
TRADINGBOT_DB_PATH=$SOFTWARE_ROOT/shared_runtime/db/tradingbot.db
TRADINGBOT_FUTURES_DATA_DIR=$SOFTWARE_ROOT/shared_runtime/market_data/futures
TRADINGBOT_LIVE_TRADE_CACHE_DIR=$SOFTWARE_ROOT/shared_runtime/data/live_trade_cache
```

- [ ] **Step 3: Prevent central data deletion**

Keep rsync excludes for DB and market data. Do not `rm -rf` anything under `$SOFTWARE_ROOT/shared_runtime`.

- [ ] **Step 4: Shell syntax check**

Run:

```bash
bash -n /Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/update-live-backend.sh
```

Expected: no output, exit 0.

## Task 8: One-Time Shared Runtime Preparation Script

**Files:**

- Create: `/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/prepare-shared-runtime.sh`

- [ ] **Step 1: Implement dry-run mode first**

Required behavior:

```bash
./trading_bot/scripts/prepare-shared-runtime.sh --dry-run
```

Print:

- chosen source DB path;
- chosen source market data path;
- target central paths;
- file sizes;
- whether target already exists;
- exact commands that would run.

- [ ] **Step 2: Implement apply mode**

Required behavior:

```bash
./trading_bot/scripts/prepare-shared-runtime.sh --source-db live --source-market-data live --apply
```

Actions:

- create central dirs;
- checkpoint source DB with `PRAGMA wal_checkpoint(TRUNCATE)`;
- copy DB with `sqlite3 ".backup"`;
- run `PRAGMA integrity_check`;
- rsync market data into central folder;
- copy existing live trade cache if present;
- write `shared_runtime/MANIFEST.md` with source paths, timestamps, sizes, and integrity result.

- [ ] **Step 3: Script syntax check**

Run:

```bash
bash -n /Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/prepare-shared-runtime.sh
```

Expected: no output, exit 0.

## Task 9: Verification And Dry Run

**Files:**

- No new files unless test failures expose a missing contract.

- [ ] **Step 1: Run backend tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q test
```

Expected: pass.

- [ ] **Step 2: Run compile**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -DskipTests compile
```

Expected: pass.

- [ ] **Step 3: Prepare shared runtime dry-run**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject
./trading_bot/scripts/prepare-shared-runtime.sh --dry-run
```

Expected: prints target plan and does not create/copy data.

- [ ] **Step 4: User approval gate**

Stop here. Do not apply migration, edit live env, restart live, or promote until user approves:

```text
Approve applying shared runtime migration now? This will create/copy data under /Users/anishpatel/Documents/SoftwareProject/shared_runtime but will not restart live unless separately approved.
```

## Task 10: Apply Migration After Approval

**Files/Data:**

- Create/update data under `/Users/anishpatel/Documents/SoftwareProject/shared_runtime`.
- Do not edit `/Users/anishpatel/Documents/SoftwareProject/live_backend` by hand.

- [ ] **Step 1: Run preparation script in apply mode**

Recommended first apply:

```bash
cd /Users/anishpatel/Documents/SoftwareProject
./trading_bot/scripts/prepare-shared-runtime.sh --source-db live --source-market-data live --apply
```

Expected:

- central DB exists;
- integrity check is `ok`;
- central market data exists;
- manifest written.

- [ ] **Step 2: Start dev backend against central storage**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject
TRADINGBOT_RUNTIME_ROOT=/Users/anishpatel/Documents/SoftwareProject/shared_runtime ./run.sh
```

Verify `/api/system/health` shows:

```text
/Users/anishpatel/Documents/SoftwareProject/shared_runtime/db/tradingbot.db
/Users/anishpatel/Documents/SoftwareProject/shared_runtime/market_data/futures
/Users/anishpatel/Documents/SoftwareProject/shared_runtime/data/live_trade_cache
```

- [ ] **Step 3: Run a read-only smoke check**

Use dev frontend/backend to confirm:

- Backtest page loads existing centralized portfolio runs.
- Strategy/Risk configs load.
- Market-data status reads centralized files.
- No live bot start is triggered.

## Task 11: Live Handoff After Approval

**Files/Data:**

- Live update must go through approved update mechanism or script, not manual edits in `live_backend`.

- [ ] **Step 1: Package backend**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q clean package
```

Expected: shaded jar exists.

- [ ] **Step 2: Promote code only**

Run only after explicit user approval:

```bash
cd /Users/anishpatel/Documents/SoftwareProject
./trading_bot/scripts/update-live-backend.sh --no-restart
```

Expected:

- live code staged;
- no dev DB snapshot promotion;
- `.env` points to shared runtime paths;
- live not restarted unless separately approved.

- [ ] **Step 3: Restart live only after approval**

Use existing approved live restart/update path. After restart, verify `/api/system/health` reports shared runtime paths.

## Risk Controls

- Keep pre-migration DBs in place as rollback archives.
- The old live DB remains at `/Users/anishpatel/Documents/SoftwareProject/live_backend/backend/tradingbot.db`.
- The old dev DB remains at `/Users/anishpatel/Documents/SoftwareProject/trading_bot/backend/tradingbot.db`.
- The migration should not delete old DBs or market-data folders.
- Do not run heavy market-data import/backtest generation while live bot has active orders/positions until the lock/guard decision is implemented.

## Self-Review

- Spec coverage: central DB, backtesting data, update button data, synthetic data, live/dev routing, and code-only promotion are all covered.
- Placeholder scan: no implementation steps rely on TODO/TBD.
- Type consistency: path API names are consistent: `runtimeRoot`, `databasePath`, `futuresDataDir`, `liveTradeCacheDir`.
