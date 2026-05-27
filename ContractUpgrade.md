# Contract Upgrade

## Scope

This document covers the dev-only contract upgrade work for adding and improving `MYM` and `MCL`, plus the follow-up contract-health work on `M2K`, `MES`, `NQ`, and `ES`.

No live backend files were edited. No market data was pulled during this continuation. The backtests used the already-pulled dataset ending `2026-05-22`.

## Current Readiness Verdict

`MYM` and `MCL` are not ready to move live under the requested standard of 200+ healthy trades per new contract.

The current best WIP improves portfolio PnL cleanly, but the two new contracts still do not have enough high-quality trade count:

| Preset | Trades | PnL | Win | PF | DD | MYM | MCL |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `contract_health_phase6_20260526` | 1075 | $93,934.78 | 73.40% | 2.70 | 3.68% | 56 / $4,190.32 | 20 / $4,284.20 |
| `contract_health_phase8_20260526` | 1075 | $94,401.76 | 73.40% | 2.73 | 3.68% | 56 / $4,190.32 | 20 / $4,284.20 |

The phase8 WIP is the cleanest dev config currently saved. It improves `M2K` without changing `MYM` or `MCL`.

## Changes Made

### Dev Strategy Settings

Added configurable risk multipliers for frequency-style add-on strategies:

| Setting | Purpose | Default/Impact |
| --- | --- | --- |
| `microShadowPortfolioRiskMultiplier` | Sizes `SHDW` trades independently from the parent contract risk. | Defaults preserve existing behavior. |
| `microEchoPortfolioRiskMultiplier` | Sizes delayed `ECHO` trades independently. | Defaults preserve existing behavior. |
| `winnerFollowThroughPortfolioRiskMultiplier` | Sizes `WFT` follow-through trades independently. | Defaults preserve existing behavior. |

Added configurable source filters:

| Setting | Purpose |
| --- | --- |
| `microShadowSourceCodes` | Controls which source strategies can create `SHDW` trades. |
| `microEchoSourceCodes` | Controls which source strategies can create `ECHO` trades. |

These settings are saved, loaded, cloned, sanitized, and emitted through strategy JSON. Existing presets keep their behavior unless the WIP/preset explicitly changes these values.

### Dev Signal Routing

Added an `MGC -> MCL` micro-shadow pair for research. This does not generate live trades unless `MCL` has `microShadow` enabled in the active live strategy slot.

Added phase runner commands in `MicroContractExpansionRunner`:

| Command | Purpose | Result |
| --- | --- | --- |
| `phase6-health` | Targeted MYM/MCL source-filter and shadow tests. | Saved `contract_health_phase6_20260526`. |
| `phase7-health` | Contract-specific structure module research for MYM/MCL. | Rejected all candidates; restored phase6. |
| `phase8-health` | Laggard repair for M2K/MES/NQ/ES from phase6 base. | Saved `contract_health_phase8_20260526`. |
| `phase9-health` | Stackability tests from phase8 for M2K/MES/NQ/ES. | Rejected all candidates; restored phase8. |
| `phase10-health` | New MYM breadth and MCL trend/fade module research from phase8. | Rejected all candidates; restored phase8. |

### Current Accepted WIP

The WIP now matches `contract_health_phase8_20260526`.

Accepted improvement:

| Contract | Change | Result |
| --- | --- | --- |
| `M2K` | Kept phase5/phase6 open scaling and added a soft early-loss-cut profile. | `M2K` PnL improved from `$9,571.70` to `$9,998.20`; avg/trade improved from `$67.88` to `$70.91`; win stayed `80.85%`. |

Rejected MYM/MCL expansions:

| Attempt | Why Rejected |
| --- | --- |
| Broad MYM `SHDW` source expansion | Reached 90-117 MYM trades, but added near-flat/noisy shadow trades and lowered portfolio quality. |
| MYM `IDXCONF` | Produced up to 283 MYM trades, but MYM went deeply negative and triggered daily-loss rule violations. |
| MYM `MYMORB2` | Added trades but negative expectancy and rule breaches. |
| MYM `LORB` | Some positive short-only pockets, but insufficient count and rule breaches in portfolio replay. |
| MCL `EIA` | Added many MCL trades, but MCL became negative and sometimes breached rules. |
| MCL `FVG`, `PDB`, `MSCALP`, `LORB` | Increased trade count but added negative or low-quality MCL trades. |
| MCL crude-open module | Safe but produced no incremental trades on the available data. |
| MYM breadth/fade module (`MYMBR`) | Both continuation and fade versions were negative; several candidates quickly hit daily/trailing rule pressure. |
| MCL trend/fade module (`MCLTC`) | Both continuation and fade versions were negative; high-count versions produced large negative MCL expectancy. |

Phase10 checkpoint under the current dev engine replayed the phase8 settings at 1058 trades, `$92,173.70`, `72.97%` win, PF `2.69`, `3.69%` DD, with `MYM` 56 / `$4,190.32` and `MCL` 20 / `$4,284.20`. The saved WIP rows still match `contract_health_phase8_20260526`; no phase10 preset was promoted.

Rejected laggard stackability findings:

| Attempt | Why Rejected |
| --- | --- |
| MES high-profit mode | Raised total PnL to about `$99k`, but portfolio win fell to `68.10%`, PF fell to `2.43`, and MES itself was only `49.73%` win. |
| NQ quality mode | Raised portfolio win to `74.23%`, PF to `2.77`, and NQ win to `69.17%`, but total PnL fell to `$93,024.64`. Useful optional stability mode, not main WIP. |
| ES VWAP quality stack | Lowered drawdown, but cut too much total PnL. |
| Tighter M2K loss cuts | Gave back PnL versus the phase8 accepted soft loss-cut profile. |

## Potential Side Effects

### Backtesting

The new settings increase the number of tunable fields in strategy presets. Existing presets should remain stable because defaults are preserved, but any preset copy/clone now includes the new fields.

The `MGC -> MCL` shadow route only matters when `MCL.microShadow.enabled=true`. It can create many low-quality trades if source codes or quality filters are loosened, so it should stay disabled unless a future backtest proves it.

The new `MYMBR` and `MCLTC` modules are installed for dev research only and should stay disabled. The first phase10 research pass showed that enabling them as generalized breadth/trend add-ons is harmful.

### Strategy Page

The backend now exposes additional strategy knobs. If the Strategy page already renders dynamic fields from backend JSON, these fields may appear automatically. If the page uses a hand-authored field list, the UI needs explicit controls before live rollout:

- `microShadowPortfolioRiskMultiplier`
- `microEchoPortfolioRiskMultiplier`
- `winnerFollowThroughPortfolioRiskMultiplier`
- `microShadowSourceCodes`
- `microEchoSourceCodes`
- `mymBreadthConfirmation`
- `mclTrendContinuation`

Potential UI issue: exposing source-code CSV fields directly can invite unsafe manual edits. A safer UI is a multi-select checklist of known strategy codes.

### Backtest Page

Backtest result summaries now have more WIP/preset checkpoints. The page should treat `contract_health_phase8_20260526` as the current dev WIP candidate, not as a live strategy.

Potential UI issue: users may see high trade-count rejected runs and confuse them with accepted WIP. The page should label accepted presets vs rejected research runs clearly.

### Live Backend

No live backend changes were made. However, the shared strategy engine now contains dev logic that live code could use if the live active strategy slot enables those strategies/settings.

Potential live risk: enabling rejected MYM/MCL modules in the live slot would likely add noisy trades and could increase daily-loss pressure.

### Data

No new data pull was performed in this continuation. Backtest data coverage is through `2026-05-22`.

Before live promotion, refresh data only in a planned data sprint and rerun the full acceptance suite on the refreshed set.

## Live Implementation Plan

Do not move `MYM` and `MCL` live until they pass the readiness gates below.

### Readiness Gates

Required before live rollout:

1. `MYM + MCL` should add at least 200 combined high-quality trades, or a smaller count only if PnL/avg/win are clearly strong enough to justify it.
2. New-contract PnL must be positive per contract.
3. Portfolio PnL must exceed the current accepted WIP.
4. Portfolio win rate should stay near the baseline and should not fall because of noisy new trades.
5. No daily-loss, trailing-drawdown, MAE, or rule violations.
6. `MYM` and `MCL` average PnL per trade must not be diluted by flat/noisy add-ons.
7. The accepted config must survive a data refresh and at least one out-of-sample replay slice.

### Live Backend Wiring

When ready, implement live support in a separate sprint:

1. Add `MYM` and `MCL` to the live strategy snapshot symbol list only after the accepted backtest preset is selected.
2. Verify Topstep instrument IDs/contracts for `MYM` and `MCL`.
3. Confirm contract specs:
   - tick size
   - tick value
   - multiplier
   - max contracts
   - micro-to-mini exposure units
   - session handling
4. Confirm market-data subscription and live bar aggregation for both symbols.
5. Confirm order routing supports both symbols through TopstepX.
6. Add live risk caps for both contracts:
   - per-trade risk
   - max contracts
   - max aggregate mini units
   - max concurrent positions
   - daily loss guard
7. Add symbol-specific kill-switch support.

### Trade Tracking

Live trade tracking must include `MYM` and `MCL` in:

- candidate generation
- signal decision rows
- submitted order ledger rows
- open position state
- partial fills
- stop/target updates
- realized PnL aggregation
- per-contract dashboard summaries
- portfolio exposure summaries
- replay/audit reconciliation

Every `MYM` and `MCL` trade should store:

- symbol
- strategy code
- side
- contracts
- entry/stop/target
- initial risk
- funded mini-unit exposure
- DTM decision
- optimizer decision
- entry reason JSON
- exit reason JSON
- source strategy when the trade is `SHDW`, `ECHO`, or `WFT`

### DTM Integration

Before live promotion, verify DTM handles `MYM` and `MCL` for:

- break-even stop transitions
- trailing-stop transitions
- managed giveback exits
- early loss cuts
- adaptive target expansion
- max-hold exits
- symbol-specific tick rounding
- symbol-specific stop-distance minimums

DTM logs should include strategy code and source strategy for derived trades.

### Entry Optimizer

The optimizer must evaluate `MYM` and `MCL` order-flow context separately from index/gold defaults:

- bid/ask depth availability
- trade tape availability
- quote freshness
- spread sanity
- slippage estimate
- marketable order guard
- no stale depth pass

If Topstep does not provide reliable depth for either contract, the optimizer should degrade explicitly and log the fallback reason.

### Logs And Reasons

Add full live reasoning coverage:

- `TRADE_CANDIDATE`
- `ORDER_FLOW_PASS`
- `ORDER_FLOW_BLOCKED`
- `RISK_ACCEPTED`
- `RISK_REJECTED`
- `ORDER_SUBMITTED`
- `ORDER_FILLED`
- `POSITION_MANAGED`
- `POSITION_EXITED`
- `RULE_GUARD_BLOCKED`

Trade reasons should explain:

- why the setup was valid
- which strategy produced it
- which source strategy produced it for derived trades
- why risk sizing was accepted
- why DTM changed or held stops
- why the trade exited

### Webapp UI Checklist

Update the webapp wherever symbol-specific live state appears:

- Live page symbol table
- portfolio PnL summary
- per-contract PnL cards/table
- open positions table
- order ledger
- live thinking log
- DTM/optimizer event feed
- rejected-trades table
- active strategy snapshot view
- strategy diagnostics/watch page
- backtest-to-live promotion modal
- trade reason drawer/modal
- export/download reports

Add clear labels for:

- accepted live symbols
- backtest-only research symbols
- disabled/rejected strategy modules
- active strategy preset name
- source portfolio backtest ID

## Dev Live Transfer Checkpoint

Date: 2026-05-27

Scope: dev app only. Production `live_backend` was not edited.

Implemented:

- Dev live default basket is now `MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL`.
- Dev live page default strategy preset is now `94k`, the frozen name for the accepted phase8 WIP preset.
- Dev live page treats `MYM` and `MCL` as micros for contract-limit sizing.
- Dev API defaults for live marks, chart, monitor, diagnostics, realtime plan/start, and live start now include `MYM` and `MCL`.
- Dev `PRESET_94K.*` was created from `PRESET_WIP.*`; `PRESET_WIP.*` remains identical as the editable working copy.
- Dev `LIVE.*` strategy setting rows were refreshed from `PRESET_94K.*`, including `LIVE.MYM.*` and `LIVE.MCL.*`.
- Dev active live snapshot symbol list was updated to include `MYM` and `MCL` and point at `94k` / `PRESET_94K`.
- Dev futures connection rows already include `MYM.c.0` and `MCL.c.0` for TopstepX, Databento, and Tradovate config surfaces.

Verification targets:

- Backend compile.
- Frontend build.
- Live page symbol chips, chart buttons, trackers, marks, and monitor requests show all 8 contracts.
- Realtime plan resolves ProjectX contracts for `MYM` and `MCL`.
- Topstep order adapter dry run can resolve both symbols before any real start.

### Promotion Flow

Recommended live promotion flow:

1. Freeze the accepted dev preset.
2. Rerun backtest after fresh data pull.
3. Run out-of-sample replay.
4. Run live lifecycle self-test for `MYM` and `MCL`.
5. Create a live strategy snapshot from the accepted backtest ID.
6. Verify UI shows both contracts correctly in all live trackers.
7. Start in practice/sim mode.
8. Run a limited session with lower max contracts.
9. Review fills, DTM decisions, optimizer logs, and trade reasons.
10. Only then consider full live sizing.

## Current Next Research Direction

The next productive research path is not broadening MYM/MCL trade count. The evidence says broadening creates noise. The next useful direction is to design a genuinely new MYM/MCL logic layer with stronger cross-market confirmation, then test it with strict acceptance gates.

Suggested ideas:

- MYM confirmation from Dow structure plus ES/NQ/M2K breadth alignment.
- MCL crude-specific trend continuation with stricter oil session context, not generic MGC shadowing.
- Out-of-sample validation slices before accepting any high-count module.
- Separate "quality mode" candidate for NQ that raises win rate by trimming low-quality IPB trades, if the user later wants stability over raw PnL.
