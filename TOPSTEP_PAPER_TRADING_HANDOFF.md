# Topstep Paper Trading / Futures Bot Handoff

Date: 2026-05-05 00:00 EDT  
Workspace: `/Users/anishpatel/Documents/SoftwareProject/trading_bot`

## Current Mission

Prepare the futures bot for TopstepX paper trading on the user's linked practice account, while keeping all real Combine/eval logic honest and repeatable. The user wants to test in the 150K practice account before attempting to pass the paid 50K Trading Combine.

For the next local always-on infrastructure phase, read `LOCAL_24_7_LAUNCH_HANDOFF.md`.

For the 2026-05-07 private cloud UI plus Windows PC backend architecture phase, read `NEXT_STAGE_SYSTEM_ARCHITECTURE_HANDOFF.md`.

Do not submit any orders, cancel orders, flatten, import paid data, buy subscriptions, or call money-related/broker actions beyond already-approved read-only sync unless the user explicitly approves the exact action.

## TopstepX Connection State

TopstepX / ProjectX API auth is working.

Important discovery:

- ProjectX wanted the user's TopstepX email identity, not the display username.
- The saved API key must remain local only. Do not paste it into chat or docs.

Linked accounts found by read-only sync:

| Account | ID | Name | Balance | Can Trade | Simulated |
| --- | ---: | --- | ---: | --- | --- |
| 50K Combine | `22529998` | `50KTC-V2-592396-32261585` | `$50,000` | `true` | `true` |
| 150K Practice | `22539378` | `PRAC-V2-592396-40893088` | `$150,000` | `true` | `true` |

Current saved execution account:

- `TOPSTEPX.accountId = 22539378`
- This is the 150K practice account and should be used for paper/live adapter testing.

The 50K Combine account is known and should be used for actual eval attempts later:

- `22529998`

Current read-only account state on the 150K practice account:

- Open positions: `0`
- Open orders: `0`
- Recent trades: `0`

Active contract discovery succeeded:

| Symbol | ProjectX Contract ID | Name | Tick Size | Tick Value |
| --- | --- | --- | ---: | ---: |
| `MNQ` | `CON.F.US.MNQ.M26` | `MNQM6` | `0.25` | `$0.50` |
| `NQ` | `CON.F.US.ENQ.M26` | `NQM6` | `0.25` | `$5.00` |
| `MGC` | `CON.F.US.MGC.M26` | `MGCM6` | `0.10` | `$1.00` |
| `ES` | `CON.F.US.EP.M26` | `ESM6` | `0.25` | `$12.50` |
| `MES` | `CON.F.US.MES.M26` | `MESM6` | `0.25` | `$1.25` |
| `GC` | `CON.F.US.GCE.M26` | `GCM6` | `0.10` | `$10.00` |
| `M2K` | quarterly probe + Databento-backed history | micro Russell 2000 family | `0.10` | `$0.50` |

## Implemented In This Session

Backend:

- Added funded-rule profile endpoint:
  - `GET /api/futures/funded-rule-profiles`
- Added TopstepX read-only sync endpoint:
  - `POST /api/futures/topstepx/sync-readonly`
- Added strict ProjectX auth validation:
  - Only marks connected when `success=true`, `errorCode=0`, and a real token exists.
  - Fixed stale false-positive `connected` state caused by a response with `token:null`.
- Added Topstep 50K Combine funded profile:
  - Account: `$50,000`
  - Maximum Loss Limit: `$2,000`
  - Daily Loss Limit guard: `$1,000`
  - Profit target: `$3,000`
  - Max exposure: `5` mini units / `50` micro contracts
  - Trailing drawdown mode: end-of-day reference
- Added M2K support:
  - Added micro-spec and symbol mapping in `FuturesManager`.
  - Added Topstep + data normalization updates in `FuturesConnectionManager`.
  - Added M2K to frontend backtest/live symbol sets.
  - Added Topstep quarterly contract probing (`H/M/U/Z`) for inferred historical contract IDs.
- Fixed ORB short-path guard in futures engine to honor `settings.allowShorts` before short entries.
- Portfolio backtester now records/enforces mixed micro/mini exposure units:
  - `MNQ`, `MES`, `MGC` count as `0.1` funded units per contract.
  - `NQ`, `ES`, `GC` count as `1.0` funded units per contract.
- Portfolio backtest records:
  - `fundedProfile`
  - `maxAggregateMiniUnits`
  - `maxConcurrentMiniUnits`
- TopstepX runner can be staged locally in `TOPSTEPX` mode without submitting orders.

Frontend:

- Settings page has clearer TopstepX / ProjectX fields:
  - ProjectX Username
  - ProjectX API Key
  - Topstep Account ID
  - Account Mode
  - Market/User hubs
- Futures Backtest page defaults to Topstep 50K profile and sends funded profile / funded units.
- Futures Live page defaults to Topstep 50K profile and shows funded units.
- Futures history displays max contracts / funded units for portfolio runs.

## Safety Rules For Next Chat

- Do not submit a paper order without explicit user permission.
- Do not submit a real Combine order without explicit user permission.
- Do not use Databento, paid broker services, subscriptions, or money-related actions without explicit permission.
- Do not print or expose the API key.
- API calls that read account/order/position/trade data are okay only if the user has explicitly approved read-only sync for that turn.
- Any write action must be named precisely before asking for approval, e.g. "place one 1-contract MNQ market order on 150K practice account and immediately flatten".

## Latest Sprint Notes (2026-05-05)

- The UI run numbering inconsistency was fixed: the history page now displays the real database ID as the run number.
- Portfolio history was pruned so startup shows only the two current keeper runs:
  - `#242` normal keeper: `MES,MNQ,NQ,MGC,ES,M2K`, `+$15,146.82`, `276` trades, `57.97%` win, PF `2.01`, maxDD `2.14%`, no breach.
  - `#250` leaner/stress-style keeper: `MNQ,NQ,MGC,ES,M2K`, `+$14,934.94`, `219` trades, `57.08%` win, PF `2.24`, maxDD `2.02%`, no breach.
- Old unsuccessful portfolio-search rows and old single-contract futures backtests were deleted from `backend/tradingbot.db`.
- A backup was created before pruning:
  - `backend/tradingbot.db.before_keeper_prune_20260505_113238`
- The user now wants a significant sprint to convert the research portfolio backtester into a live/practice execution engine using the 150K practice account while backtesting continues separately.

## Live Engine Conversion Sprint (Next Chat)

Primary objective:

- Build a real live/practice futures engine that mirrors the current event-driven portfolio backtest engine as closely as possible, so the bot can forward-test the current successful strategy in the present market.
- The first live target is the 150K TopstepX practice account:
  - Account ID: `22539378`
  - Account name: `PRAC-V2-592396-40893088`
  - Environment/account mode: `PRACTICE_COMBINE`
- Hard-block all order submission if the selected account is not `22539378` during this sprint.

Important expectation:

- Do not promise exact backtest replication. The live engine should use the same signal logic, ranking, sizing, stops, targets, time stops, risk guards, and portfolio exposure rules, but live fills/data/latency/current market conditions will differ.

Keep two strategy tracks separate:

- `Research / Backtest Config`
  - This is allowed to keep changing while optimization continues.
  - Backtest pages and strategy settings can keep improving candidate configs.
- `Live Successful Strategy Config`
  - This must be a frozen snapshot of the previous most successful validated config.
  - Initial live snapshot should come from portfolio run `#242` unless the user explicitly chooses `#250`.
  - The live engine should not automatically absorb ongoing backtest changes.
  - Store metadata with every snapshot:
    - source portfolio backtest ID
    - symbols
    - funded profile
    - risk settings per symbol
    - strategy settings per symbol
    - global portfolio/risk settings
    - code/version timestamp
    - normal/stress metrics used to justify promotion

Required UI:

- Add a button on `FuturesLive.jsx` named similar to `Update Live Successful Strategy`.
- The button should:
  - Show the current live snapshot source, e.g. `Live Strategy: Backtest #242`.
  - Let the user update the live snapshot from the selected successful portfolio run.
  - Be disabled while the live engine is running.
  - Confirm before overwriting the live snapshot.
  - Never change the research/backtest settings by itself.

Data-client guidance:

- Prefer current clients and local data before paid Databento.
- Use TopstepX / ProjectX for account state, positions, orders, trades/fills, active contract IDs, and any available practice-account market data or hub feed.
- Use existing local futures CSV history only for warmup/context/backfill at startup; do not treat stale CSV bars as live prices.
- If TopstepX cannot provide usable live bars/ticks for the practice engine, try existing configured alternatives first:
  - Tradovate demo/direct if credentials and permissions are available.
  - TopstepX market hub/user hub if the API exposes streaming quotes.
  - Polling endpoints if streaming is unavailable and latency is acceptable for practice.
- Do not start a Databento live data stream, purchase data, or pull paid historical data unless the user explicitly approves that exact paid usage. Databento may remain a fallback for later if current clients cannot provide live market data.

Minimum live-engine parity with backtester:

- Multi-symbol portfolio loop for `MES,MNQ,NQ,MGC,ES,M2K` or the chosen live snapshot symbols.
- Same 1-minute bar aggregation and RTH execution schedule.
- Same higher-timeframe context (`15m`, `1h`) from the live stream/bar builder.
- Same signal builders from `FuturesManager`.
- Same next-bar-open entry model adapted to live: signal forms on completed bar, order placed for the next bar/open-equivalent.
- Same portfolio signal ranking.
- Same max open positions.
- Same per-symbol no-overlap guard.
- Same max aggregate contracts and funded mini-unit guard.
- Same per-symbol max contracts.
- Same daily loss guard.
- Same Topstep end-of-day trailing drawdown model.
- Same intratrade MAE/risk guard.
- Same forced flat time.
- Same stops, targets, adaptive exits, and time stops.
- Same slippage/commission assumptions for simulated/paper analytics.
- Full audit log for every signal, rejection, order request, broker response, fill, cancel, flatten, and risk-block decision.

Recommended live-engine architecture:

- Create explicit backend objects instead of hiding this inside the old live status endpoint:
  - `LiveStrategySnapshot`
  - `LivePortfolioEngine`
  - `LiveMarketDataService`
  - `TopstepXPaperExecutionAdapter`
  - `LiveRiskGuard`
  - `LiveOrderLedger`
- Add persistence tables or JSON files for:
  - live strategy snapshots
  - live engine sessions
  - live signal decisions
  - live orders
  - live fills
  - live positions
  - risk events / kill-switch events
- Add API endpoints for:
  - get/update live successful strategy snapshot
  - start/stop live practice engine
  - dry-run current signal/order construction
  - sync TopstepX account state
  - list live orders/fills/positions/decisions
  - arm/disarm order submission
  - emergency flatten/cancel, guarded behind explicit confirmation

Rollout stages:

1. Dry-run only:
   - Build live bars, run signals, rank them, size hypothetical orders, but submit nothing.
   - Compare live decisions against the backtest engine on the same completed bars where possible.
2. Paper order sandbox:
   - With explicit user approval, send one tiny MNQ practice order to account `22539378`, then immediately flatten/cancel according to the approved test.
3. Bracket/OCO validation:
   - Validate stop/target attachment or managed synthetic exits in practice.
4. Full guarded paper mode:
   - Enable the frozen live successful config with strict kill switch and reduced-size warmup if needed.
5. Continuous reporting:
   - Show live PnL, daily PnL, open risk, max adverse excursion, fills, rejected signals, and divergence from backtest assumptions.

## Recommended Next Engineering Sequence

### 1. Build A Paper Execution Adapter

Create a proper TopstepX adapter layer rather than placing ad hoc calls inside managers.

Suggested backend responsibilities:

- Authenticate and cache short-lived ProjectX token.
- Sync linked accounts.
- Resolve active contracts from symbols.
- Sync open positions.
- Sync open orders.
- Sync recent trades/fills.
- Submit order only when an explicit paper-trading flag is enabled.
- Cancel order.
- Flatten account.
- Record every outbound request and response except secrets.
- Hard block any order if:
  - account ID is not the selected practice account during paper testing,
  - funded-risk guard rejects it,
  - max daily loss reached,
  - trailing loss reached,
  - max position units reached,
  - user has not explicitly armed order submission.

### 2. Add Live/Paper Risk Guard

Mirror the portfolio backtester's funded checks in live mode:

- Daily loss guard.
- End-of-day trailing MLL for Topstep 50K profile.
- Open-position MAE guard.
- Max aggregate funded units.
- Per-symbol contract caps.
- Force-flat time.
- Kill switch.
- No new entry when open orders exist unless strategy explicitly supports that.
- No strategy entry if account sync is stale.

### 3. Paper Trade With The 150K Practice Account

Use account `22539378` only.

Start with the smallest possible tests:

- Read-only sync.
- Contract lookup.
- Dry-run order construction.
- If approved by user, one tiny MNQ paper order with immediate flatten/cancel workflow.
- Then one bracket/OCO workflow only after the simple flow is proven.

### 4. Keep Backtests Aligned With 50K Combine Rules

Even though paper execution uses the 150K practice account, backtests that are meant to prepare for the real eval should keep using:

- `TOPSTEP_50K_COMBINE`
- `$50,000` account
- `$2,000` MLL
- `$1,000` DLL guard
- `$3,000` profit target
- `5` funded mini units
- end-of-day trailing threshold

This keeps the strategy honest for the account the user actually bought.

### 5. Returns Research Still Needed

The current target remains:

- `$20k-$25k` annual profit
- `300-400` trades/year
- no funded-rule violations
- live-repeatable logic only

The previous normal aggregate individual-symbol run reached about `318` trades but only about `+$12.4k`, and it was not portfolio-level accounting. Do not claim the target has been reached unless a current portfolio-level Topstep-profile run proves it.

Run:

- normal portfolio full-year
- stress portfolio full-year
- split-period portfolio tests
- monthly tables
- Topstep 50K funded-rule checks
- practice-account paper dry-runs

Then state honestly whether the target was reached.

## Verification Commands

Backend:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw test
```

Frontend:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/frontend
npm run build
```

Restart backend:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
PID=$(lsof -ti tcp:7070 || true)
if [ -n "$PID" ]; then kill "$PID"; fi
./mvnw -q compile exec:java -Dexec.mainClass=com.tradingbot.MainServer
```

Read-only sync, only with user approval:

```bash
curl -s -X POST http://localhost:7070/api/futures/topstepx/sync-readonly
```

## Prompt For The Next Chat

Paste this into the next chat:

```text
Project: trading_bot TopstepX futures paper trading handoff
Workspace: /Users/anishpatel/Documents/SoftwareProject/trading_bot

Read these first:
/Users/anishpatel/Documents/SoftwareProject/trading_bot/FUTURES_STRATEGY_HANDOFF_SPEC.md
/Users/anishpatel/Documents/SoftwareProject/trading_bot/TOPSTEP_PAPER_TRADING_HANDOFF.md

Important state:
TopstepX / ProjectX auth is working when the saved username is the user's TopstepX email, not the display username. Do not expose the API key.

Linked accounts found:
- 50K Combine: account ID 22529998, name 50KTC-V2-592396-32261585, balance 50000, canTrade true, simulated true.
- 150K Practice: account ID 22539378, name PRAC-V2-592396-40893088, balance 150000, canTrade true, simulated true.

Current saved TopstepX execution account should be 22539378, the 150K practice account. Use it for paper/live adapter testing.

Use Topstep 50K funded rules for backtesting toward the actual eval:
- accountSize 50000
- maxTrailingDrawdown 2000
- dailyLossLimit guard 1000
- profitTarget 3000
- maxAggregateMiniUnits 5
- MNQ/MES/MGC = 0.1 funded units, NQ/ES/GC = 1.0 funded unit
- trailing drawdown uses end-of-day reference for Topstep profile

Already implemented:
- True event-driven portfolio-level futures backtester.
- Topstep 50K funded profile.
- Mixed micro/mini funded unit enforcement.
- Settings UI for TopstepX keys.
- Futures backtest/live UI funded profile controls.
- Read-only TopstepX sync endpoint.
- Strict ProjectX auth validation.

Critical sprint:
Convert the successful portfolio backtest engine into a live/practice engine for the 150K TopstepX practice account. The live engine must mimic the portfolio backtester as closely as live trading allows: same multi-symbol signal builders, same 1-minute completed-bar timing, same next-bar entry intent, same portfolio ranking, same sizing, same stops/targets/time stops/adaptive exits, same daily-loss/trailing-drawdown/funded-unit guards, and same forced-flat logic.

Keep two config tracks separate:
- Research / Backtest Config: can keep changing while optimization continues.
- Live Successful Strategy Config: frozen snapshot used by live practice trading.

Initial live snapshot:
- Prefer source backtest `#242`: `MES,MNQ,NQ,MGC,ES,M2K`, `+$15,146.82`, 276 trades, no breach.
- `#250` is the leaner alternate: `MNQ,NQ,MGC,ES,M2K`, `+$14,934.94`, 219 trades, no breach.
- Do not auto-update live config when research settings change.

Required UI:
- Add an `Update Live Successful Strategy` button on the live page.
- It should snapshot the selected successful portfolio run into the live config, show the snapshot source, and be disabled while live trading is running.

Data-client instruction:
- Avoid Databento live/paid usage unless explicitly approved.
- Prefer TopstepX/ProjectX for account, contract, order, fill, and any available practice market data.
- Use local futures CSV only for startup warmup/context/backfill, not as live price.
- If TopstepX cannot provide usable live market data, try existing configured clients such as Tradovate demo/direct or TopstepX hub/polling before asking about Databento.

Implementation tasks:
1. Add persistent live strategy snapshot storage.
2. Add live market data service and 1-minute bar builder.
3. Add TopstepX paper execution adapter for account `22539378`.
4. Add live portfolio engine that reuses the portfolio backtest signal/risk logic.
5. Add live risk guard, kill switch, order ledger, and audit logs.
6. Add dry-run mode first, then explicit-approval practice order tests.
7. Add live UI for account, selected snapshot, engine state, contracts, positions, orders, fills, rejected signals, risk events, and arm/disarm state.

Safety:
Do not place/cancel/flatten any order without explicit user approval for the exact action. Do not use paid APIs, Databento pulls, broker services, subscriptions, or money-related actions without explicit user permission. Read-only Topstep sync also needs explicit user approval in that turn.

Verification:
- ./mvnw test
- npm run build
- restart backend on http://localhost:7070 if backend changed
- read-only sync only after user approval
- state honestly what is working and what is still guarded
```
