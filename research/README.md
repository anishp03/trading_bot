# Quarantined research runners

This directory contains **41 legacy Java programs with `main()` entry points** that were previously mixed into Maven's JUnit source tree. They are historical strategy/backtest experiments, not automated tests.

## Current boundary

- Maven does **not** compile or execute this directory by default.
- The files were moved unchanged and retain package `com.tradingbot` for provenance.
- There is intentionally no Maven research profile tonight; adding one would make unsafe programs easier to run before their storage contracts are fixed.
- `production_backend/tests` now contains 28 files with real `@Test` methods plus `TestDatabaseSupport`.
- `FuturesPortfolioIntegrityResultRunner` remains in the JUnit tree because it contains an actual `@Test` method despite its legacy name.

## Safety warning

Do not compile or run these programs casually. Several contain destructive cleanup, write backtest/result rows, create the research-only `ResearchRunLabels` table, or depend on stale/default database paths. Fifteen did not declare an explicit database-path override at quarantine time. A default can therefore resolve to a developer or shared runtime database rather than a disposable fixture.

Before reviving any runner:

1. Trace every database and filesystem path it can resolve.
2. Require an explicit disposable database and fail closed when it is absent.
3. Replace real-account/provider assumptions with synthetic fixtures.
4. Separate read-only analysis from result persistence and destructive cleanup.
5. Add a focused automated test for the reusable logic moved into production/test code.
6. Only then add an opt-in build helper or Maven profile for the hardened runner.

Production schema is currently owned by `DatabaseManager`, `FuturesManager`, and `FuturesMarketDataStore` under `production_backend/src/`. `ResearchRunLabels` remains research-only because the quarantined programs declare incompatible four-column and five-column variants and production startup has no owner for it.
