# Backend API orientation

This directory is the tracked Java backend source. It builds the Javalin HTTP API, shared strategy and risk engine, SQLite persistence, and Topstep/ProjectX REST and SignalR integrations. The deployed copy remains outside this repository at `live_backend/backend`; do not treat that runtime copy as editable source.

For the complete system view, start with the repository [README](../README.md). This file then provides the backend-specific owner, boot, route, runtime, and test map.

## Current source map

| Path or owner | Current responsibility |
|---|---|
| `src/MainServer.java` | Composition root: initializes stores, creates Javalin, installs auth/error middleware, and registers every route group. |
| `src/*Routes.java` | Physically flat HTTP adapters. Seven files retain the logical `com.tradingbot.api.routes` package and own 66 of the 95 current endpoints. |
| `src/FuturesBacktestRoutes.java` | Deferred route adapter; remains beside the package-private runtime mutation guard. |
| `src/FuturesConnectionRoutes.java` | Deferred route adapter; remains beside the package-private runtime mutation guard. |
| `src/SystemRoutes.java` | Deferred route adapter; remains beside package-private runtime-path and backend-update owners. |
| `src/ApiAuthSupport.java` | Authentication middleware, authorization roles, login throttling, and account selection. Its support-package move is deferred. |
| `src/ApiRequestUtils.java` | Shared request parsing and JSON-string escaping. It is public so aligned route packages can use it, but its support-package move is deferred. |
| `src/FuturesManager.java` | Current monolithic owner for settings, strategy detection, backtests, risk, live orchestration, execution handoff, reconciliation, and futures DDL. |
| `src/FuturesConnectionManager.java` | Topstep/ProjectX credentials, REST calls, accounts, contracts, bars, orders, positions, and broker-state queries. |
| `src/ProjectXRealtimeManager.java` | SignalR connection lifecycle, subscriptions, event handling, and realtime persistence. |
| `src/DatabaseManager.java` | SQLite connections plus identity and legacy schema bootstrap. |
| `src/FuturesMarketDataStore.java` | Futures bars, Level 2 snapshots, and reconciliation persistence. |
| `src/RuntimePaths.java` | Runtime-root, database, market-data, and live-trade-cache path precedence. |
| `resources/liqrec-source2268.properties` | Internal Liquidity Reclaim source-detector profile; it is strategy logic and requires backtest/live parity coverage. |
| `tests/*.java` | Physically flat Maven/JUnit regression tests and shared test support. |
| `../research/java/` | Manual research runners; intentionally outside Maven's test source set. |

The physical layout is intentionally shallow. Java package identity still comes
from each file's `package` declaration, so fully qualified class names, imports,
route ownership, and the `com.tradingbot.MainServer` entry point are unchanged.

The aligned route package currently contains:

- `AccountRoutes`: four account endpoints.
- `AuthRoutes`: three authentication/session endpoints.
- `FuturesLiveRoutes`: 28 live, realtime, audit, and trade-cache endpoints.
- `FuturesMarketRoutes`: three instrument, data-status, and trade-analysis endpoints.
- `FuturesRiskRoutes`: three risk endpoints.
- `FuturesStrategyRoutes`: nine strategy and preset endpoints.
- `LegacyEquityRoutes`: 16 compatibility endpoints that consistently return HTTP 410.

The three deferred route owners contain the remaining 29 endpoints. Package location does not change URL paths, payloads, defaults, authorization, or execution behavior.

## Boot sequence

`MainServer.main` performs these operations in order:

1. Initialize `DatabaseManager`, `FuturesManager`, `FuturesConnectionManager`, and `ProjectXRealtimeManager` stores.
2. Create `AccountManager`.
3. Resolve bind host and port, create Javalin, and start the listener.
4. Install `/api/*` authentication/authorization middleware plus API error handlers.
5. Register system, authentication, account, legacy, market, strategy, risk, connection, backtest, and live route groups.

Starting the process can initialize or migrate the selected SQLite database. Registering routes alone does not submit an order, but `/api/futures/live/start` with `executionMode=TOPSTEPX` is a broker-mutating operation and currently performs startup flatten/cancel actions. Use the repository safe-demo guidance before launching anything.

## Runtime boundaries

Runtime locations are selected by system properties first, environment variables second, and legacy path fallbacks last. The supported aggregate boundary is `TRADINGBOT_RUNTIME_ROOT`; role is selected with `TRADINGBOT_RUNTIME_ROLE=dev|live`.

- Development must use the repository-root `dev_runtime` directory and the development port.
- Live remains `/Users/anishpatel/Documents/SoftwareProject/live_backend/backend` with the shared runtime selected by the approved launcher.
- `production_backend/target`, local databases, market data, logs, credentials, and caches are generated or sensitive artifacts and must stay untracked.
- Maven recreates the package-oriented `target/` tree while compiling. Run `./mvnw clean` when a source-only folder view is desired; never edit or promote individual files from inside `target/`.
- Source relocation does not authorize starting services, accessing a broker, promoting a build, or changing live runtime data.

## Build and test map

Run from `production_backend/`:

```bash
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MainServerTest,FuturesLiveStartContractTest test
./mvnw -q test
./mvnw -q clean package
```

The package command produces `target/backend-0.0.1-SNAPSHOT-all.jar`. The current full-suite baseline runs 192 tests with portable checked-in fixtures; all 192 must pass with zero failures and errors before promotion.

Important focused ownership tests:

| Concern | Test |
|---|---|
| Request parsing compatibility | `MainServerTest` |
| Live-start defaults and request mapping | `FuturesLiveStartContractTest` |
| Runtime mutation boundary | `RuntimeMutationGuardTest` |
| Runtime path precedence | `RuntimePathsTest`, `FuturesManagerRuntimePathsTest`, `DatabaseManagerPathTest` |
| Route health payload | `SystemRoutesTest` |
| Backtest/live strategy integrity | `FuturesBacktestLiveParityIntegrityTest` |

Route refactors must also compare the normalized Javalin method/path inventory before and after. The current contract is exactly 95 unique registrations.

## Deferred package target

Do not move these owners merely to make the tree look finished. Their current dependencies must be separated or made explicit first.

```text
com.tradingbot.bootstrap/                 MainServer
com.tradingbot.application.futures/       FuturesManager, LiveRuntimeState
com.tradingbot.application.runtime/       RuntimeMutationGuard
com.tradingbot.infrastructure.identity/   AccountManager
com.tradingbot.infrastructure.database/   DatabaseManager
com.tradingbot.infrastructure.marketdata/ FuturesMarketDataStore
com.tradingbot.infrastructure.projectx/   FuturesConnectionManager, ProjectXRealtimeManager
com.tradingbot.infrastructure.runtime/    RuntimePaths
com.tradingbot.infrastructure.deployment/ BackendUpdateService
com.tradingbot.legacy.equity/              AlpacaManager, StrategyManager
com.tradingbot.api.support/                ApiAuthSupport, ApiRequestUtils
```

`FuturesManager` is not a clean application service today: it still contains domain rules, SQL, DTO-like nested classes, execution coordination, and serialization. Its eventual package move must follow extraction and parity verification rather than conceal those mixed responsibilities.
