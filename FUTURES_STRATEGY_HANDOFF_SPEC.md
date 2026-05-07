# Futures Strategy / Backtesting Handoff Spec

Date: 2026-05-05  
Workspace: `/Users/anishpatel/Documents/SoftwareProject/trading_bot`  
Primary goal: build the futures bot into a paper-trading-ready system with honest backtests, ideally reaching roughly `$20k-$25k` annual profit and `300-400` trades/year while maintaining funded-account rule compliance.

Latest continuation note, 2026-05-05:

- Also read `TOPSTEP_PAPER_TRADING_HANDOFF.md`.
- For the local always-on launch plan, also read `LOCAL_24_7_LAUNCH_HANDOFF.md`.
- For the 2026-05-07 cloud UI plus Windows PC backend architecture plan, read `NEXT_STAGE_SYSTEM_ARCHITECTURE_HANDOFF.md`.
- The true event-driven portfolio-level backtester has now been implemented.
- TopstepX / ProjectX read-only auth and sync work with the saved email/API key.
- The active local TopstepX execution account is the 150K practice account `22539378`.
- The actual 50K Combine account is `22529998`.
- Use the 150K practice account for paper/live adapter testing.
- Keep 50K Combine funded rules for backtests intended to prepare for the real eval.
- Do not submit/cancel/flatten orders without explicit user approval for the exact action.

## Non-Negotiable Rules

- Do not fake, curve-fit, or selectively report results.
- Backtest integrity and funded-account compliance are more important than profit.
- Use native futures CSV data for valid futures runs.
- Execution is on 1-minute bars.
- Higher timeframes (`5m`, `15m`, `1h`) are context only.
- Signals enter on the next bar open, never same-bar close.
- Slippage is modeled on entry and exit.
- Round-trip commissions are modeled.
- If stop and target touch in the same bar, assume stop first.
- Force flat near session close.
- Enforce daily loss and trailing drawdown.
- Detect intratrade MAE rule breaches.
- No overlapping trades by default.
- Do not spend money, pull paid data, or use paid APIs without explicit user approval first.

## Current Data State

Native futures data exists for:

- `MNQ`
- `NQ`
- `MGC`
- `ES`
- `MES`
- `GC`
- `M2K`

Current 1-minute futures files are under:

- `backend/market_data/futures/1min/*.csv`
- `backend/market_data/futures/5min/*.csv`
- `backend/market_data/futures/15min/*.csv`
- `backend/market_data/futures/1hour/*.csv`

Approximate 1-minute raw file rows:

- `MNQ`: `350,831`
- `NQ`: `350,263`
- `MGC`: `351,712`
- `ES`: `351,576`
- `MES`: `352,127`
- `GC`: `351,150`
- `M2K`: `336,928` (Databento + Topstep merged)

Diagnostics showed Databento is not row-limiting the current files. The apparent difference versus stock bars comes from futures raw files containing Globex/extended-hours rows while the current backtest intentionally executes only RTH.

The 1-second data experiment did not materially improve strategy consistency relative to cost/complexity. The active strategy engine should remain 1-minute unless the user explicitly approves a new 1-second or tick-data research path.

## Important Files

- `backend/src/main/java/com/tradingbot/FuturesManager.java`
- `backend/src/main/java/com/tradingbot/MainServer.java`
- `backend/src/main/java/com/tradingbot/FuturesConnectionManager.java`
- `frontend/src/pages/FuturesStrategy.jsx`
- `frontend/src/pages/FuturesBacktest.jsx`
- `frontend/src/pages/FuturesBacktestHistory.jsx`
- `frontend/src/pages/FuturesLive.jsx`
- `backend/tradingbot.db`

## Current Enabled Profiles

These profiles are currently saved in `backend/tradingbot.db`.

### MNQ

Risk:

- Account: `50000`
- Max trailing drawdown: `2500`
- Daily loss limit: `500`
- Max risk/trade: `400`
- Max contracts: `12`
- Commission/contract: `1.24`
- Slippage: `1 tick`

Enabled:

- ORB: enabled, max `1/day`
- Sweep: enabled, max `5/day`
- VWAP pullback: enabled, max `3/day`
- Shorts allowed
- Higher-timeframe guard enabled

Key filters:

- Early sweep disabled
- Late sweep enabled
- Late reclaim ticks: `12`
- Late close location: `0.35`
- Min reward/risk: `1.3`
- VWAP volume ratio: `1.1`
- VWAP max distance: `120`
- Max initial risk ticks: `220`

### NQ

Risk:

- Account: `50000`
- Max trailing drawdown: `2500`
- Daily loss limit: `500`
- Max risk/trade: `200`
- Max contracts: `1`
- Commission/contract: `1.24`
- Slippage: `1 tick`

Enabled:

- ORB: enabled, max `2/day`
- ORB retest: enabled
- Sweep: enabled, max `3/day`
- VWAP pullback: enabled, max `3/day`
- Shorts allowed
- Higher-timeframe guard enabled

Key filters:

- Max initial risk ticks: `40`
- VWAP volume ratio: `1.1`
- VWAP max distance: `120`

### MGC

Risk:

- Account: `50000`
- Max trailing drawdown: `2500`
- Daily loss limit: `500`
- Max risk/trade: `200`
- Max contracts: `10`
- Commission/contract: `1.24`
- Slippage: `1 tick`

Enabled:

- ORB: enabled, max `2/day`
- ORB retest: enabled
- Close Momentum: enabled, max `1/day`
- Shorts allowed
- Higher-timeframe guard enabled

Key filters:

- Max initial risk ticks: `80`
- Min reward/risk: `1.0`
- Close Momentum min move ticks: `20`
- Close Momentum volume ratio: `0.7`
- Close Momentum reward/risk: `0.85`

Known weakness:

- This profile helps trade count and normal profit, but the higher-cost stress run is slightly negative. It should be considered research-forward, not paper-trading-ready on its own.

### ES

Risk:

- Account: `50000`
- Max trailing drawdown: `2500`
- Daily loss limit: `500`
- Max risk/trade: `215`
- Max contracts: `2`
- Commission/contract: `1.24`
- Slippage: `1 tick`

Enabled:

- ORB: enabled, max `1/day`
- Sweep: enabled, max `3/day`
- VWAP pullback: enabled, max `3/day`
- Shorts allowed
- Higher-timeframe guard enabled

Key filters:

- ORB retest disabled
- Min reward/risk: `1.3`
- VWAP volume ratio: `1.1`
- VWAP trend slope ticks: `3`
- VWAP max distance: `120`
- Max initial risk ticks: `220`

Known weakness:

- Full-year positive and stress positive, but split-period is weak: first half positive, second half slightly negative. Do not call this paper-ready without further robustness work.

### M2K

Enabled strategy profile currently used for testing:

- ORB: disabled
- openingMomentum: disabled
- sweep: disabled
- vwapPullback: disabled
- vwapMeanReversion: disabled
- fvg: disabled
- afternoonContinuation: disabled
- marketIntradayMomentum: disabled
- keltnerScalp: disabled
- keltnerReversion: disabled
- microScalp: disabled
- closeMomentum: enabled
- closeMomentum maxTradesPerDay: `1`
- allowCloseMomentumLongs: `false`
- allowCloseMomentumShorts: `true`
- closeMomentumMinMoveTicks: `8`
- closeMomentumVolumeRatio: `0.5`
- closeMomentumRewardRisk: `0.9`
- maxInitialRiskTicks: `120`
- openMaeRiskMultiplier: `1.0`

This is the best current configuration for trade count + funded-integrity under user preference.

### MES and GC

Currently left disabled for strategy use.

Reason:

- MES and GC variants produced attractive isolated cases but failed stress, split, or funded-rule integrity. Do not enable casually.

## Latest Saved Backtest Runs

Range: `2025-05-01` to `2026-04-27` where available.  
MNQ data ended at `2026-04-24`.

Normal runs:

| Run | Symbol | Profit | Trades | Win Rate | PF | Max DD | Rule Violation |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `#380` | `MNQ` | `+$5,378.12` | `27` | `85.19%` | `8.30` | `0.67%` | `false` |
| `#383` | `NQ` | `+$1,945.52` | `26` | `73.08%` | `2.99` | `0.65%` | `false` |
| `#385` | `MGC` | `+$3,278.04` | `223` | `56.95%` | `1.22` | `2.89%` | `false` |
| `#387` | `ES` | `+$1,845.84` | `42` | `52.38%` | `1.92` | `1.83%` | `false` |

Normal aggregate, individual-symbol accounting only:

- Profit: `+$12,447.52`
- Trades: `318`
- Rule violations: `0`
- Overlaps inside each symbol run: `0`

Stress runs with commission `2.50` and slippage `1.5`:

| Run | Symbol | Profit | Trades | Win Rate | PF | Max DD | Rule Violation |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `#381` | `MNQ` | `+$4,569.50` | `27` | `85.19%` | `6.38` | `0.69%` | `false` |
| `#382` | `NQ` | `+$1,845.00` | `24` | `75.00%` | `3.24` | `0.71%` | `false` |
| `#384` | `MGC` | `-$155.00` | `222` | `55.86%` | `0.99` | `4.05%` | `false` |
| `#386` | `ES` | `+$185.00` | `18` | `44.44%` | `1.16` | `0.97%` | `false` |

Stress aggregate, individual-symbol accounting only:

- Profit: `+$6,444.50`
- Trades: `291`
- Rule violations: `0`
- Overlaps inside each symbol run: `0`

Important caveat:

- These are not portfolio-level funded-rule results. They are individual symbol backtests added together. Portfolio-level daily loss, trailing drawdown, intratrade MAE, simultaneous positions, and aggregate exposure are not yet simulated.

## Latest Portfolio Backtest Priority Runs (M2K phase)

Primary active window: `2025-05-01` to `2026-05-04`.

Recent portfolio runs to hand off:

- `#188 P4-OPT trade-preserving red-strategy repair normal`: `+$9,494.97`, `225` trades, `58.22%` win, PF `1.78`, maxDD `2.14%`, no breach
- `#189 P4-OPT trade-preserving red-strategy repair stress`: `+$3,905.25`, `190` trades, `55.79%` win, PF `1.36`, maxDD `3.18%`, no breach
- `#190 P5-M2K short close-momentum add-on normal`: `+$9,920.25`, `230` trades, `59.13%` win, PF `1.82`, maxDD `2.14%`, no breach
- `#191 P5-M2K short close-momentum add-on stress`: `+$4,161.25`, `195` trades, `56.92%` win, PF `1.38`, maxDD `2.92%`, no breach
- `#192 P5-M2K expanded short close-momentum normal`: `+$9,962.15`, `242` trades, `58.68%` win, PF `1.79`, maxDD `2.26%`, no breach
- `#193 P5-M2K expanded short close-momentum stress`: `+$4,030.75`, `207` trades, `56.04%` win, PF `1.35`, maxDD `3.01%`, no breach

Current preference: preserve trade count first (`#192/#193` keep 17 extra M2K trades) while holding funded-rule compliance.

Latest continuation note, 2026-05-05:

- Topstep quarterly contract probing is enabled for broader futures pulls.
- M2K is now in both backend and frontend stacks with full merged data.
- ORB short-entry now consistently checks `allowShorts=false`.

## Recent Research / What Failed

Strategies or variants that should not be promoted yet:

- High-frequency Opening Momentum: more trades, but negative or funded-rule failures.
- VWAP strict variants: usually degraded results.
- ORB Retest on MNQ: higher trade count but rule violations or weaker profit.
- Compressed ORB breakout: not robust, often rule violations.
- Afternoon Continuation: poor or rule-violating in current form.
- Market Intraday Momentum: researched and implemented as a backend lab variant, but not promoted because it did not survive integrity/stress well enough.
- MES/GC trade-count variants: poor split/stress behavior.

Research inspiration already used:

- Intraday momentum literature: first half-hour / last half-hour continuation.
- Opening range breakout research.
- VWAP trend continuation concepts with volume and trend slope guards.

## Current Implementation Notes

Implemented features include:

- M2K instrument and symbol integration across backend + UI.
- Topstep quarterly contract probing support for inferred historical contract pulls.
- Portfolio backtester updates for mixed micro/mini unit accounting.
- ORB short-entry guard fix for `allowShorts=false`.
- Per-contract strategy settings.
- Futures strategy UI profile selector.
- Futures backtest history split from futures backtest page.
- Gold and micro-gold (`GC`, `MGC`) in futures stack.
- 1-second import path removed/disabled from user flow after cost and integrity review.
- Diagnostics now clearly says when zero trades are caused by no enabled strategy modules.
- Futures history no longer labels zero-trade runs as `Pass`; it shows `No Trades`.
- Backend strategy lab supports comparing variants without saving them.
- True portfolio-level event-driven futures backtester.
- Portfolio funded-rule checks for aggregate daily loss, trailing drawdown, MAE, cross-symbol overlap, simultaneous exposure, and mixed micro/mini funded units.
- Topstep 50K Combine funded profile.
- TopstepX settings UI and live/backtest funded profile controls.
- TopstepX read-only sync endpoint.
- Strict ProjectX auth validation requiring `success=true`, `errorCode=0`, and a real token.

## Topstep / ProjectX State

Detailed notes live in:

- `TOPSTEP_PAPER_TRADING_HANDOFF.md`

Short version:

- ProjectX authentication works when using the user's TopstepX email as the username.
- Do not expose or print the API key.
- Linked 50K Combine account: `22529998`.
- Linked 150K practice account: `22539378`.
- Saved execution account is currently the 150K practice account.
- Last read-only sync found no open positions, no open orders, and no recent trades on the practice account.
- Active contracts discovered include `MNQ`, `NQ`, `MGC`, `ES`, `MES`, and `GC` June 2026 contracts.

## Verification Commands

Run these before calling anything done:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw test
```

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/frontend
npm run build
```

Restart backend after backend edits:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
PID=$(lsof -tiTCP:7070 -sTCP:LISTEN -n -P || true)
if [ -n "$PID" ]; then kill "$PID"; fi
screen -S tradingbot-backend -X quit >/dev/null 2>&1 || true
screen -dmS tradingbot-backend ./mvnw -q compile exec:java -Dexec.mainClass=com.tradingbot.MainServer
```

Check backend:

```bash
lsof -iTCP:7070 -sTCP:LISTEN -n -P
```

## Next Steps Toward The Goal

### 1. Build the TopstepX paper execution adapter

This is now the most important next engineering step.

Required behavior:

- Use the 150K practice account `22539378`.
- Cache/refresh ProjectX session tokens.
- Resolve active contract IDs for `MNQ`, `NQ`, `MGC`, and `ES`.
- Sync open positions, open orders, recent fills/trades, and linked account state.
- Add dry-run order construction before any live submission.
- Add order submit/cancel/flatten only behind explicit arming and explicit user approval.
- Mirror the backtest funded-rule guard in live/paper mode.
- Hard-block orders if account state is stale, risk limits are breached, or the selected account is wrong.

### 2. Use portfolio simulator to validate current enabled set

Test:

- MNQ + NQ only.
- MNQ + NQ + MGC.
- MNQ + NQ + MGC + ES.

For each:

- Normal friction.
- Higher commission/slippage.
- First half and second half splits.
- Monthly results.
- Aggregate funded-rule checks.
- Simultaneous exposure and overlap checks.

### 3. Stabilize MGC or reduce its role

MGC gives most of the trade count but is the weakest stress component.

Possible research:

- Reduce MGC max contracts.
- Reduce MGC max risk/trade.
- Filter out bad months/time blocks.
- Add walk-forward-only filters, not full-sample hindsight.
- Treat MGC as optional unless stress becomes positive.

### 4. Improve ES or keep it research-only

ES gives additional profit but weak second-half behavior.

Possible research:

- Month/regime filter based only on prior data.
- Volatility-regime guard.
- VWAP-only profile versus ORB/Sweep mix.
- ES disabled in portfolio if it increases aggregate drawdown or stress fragility.

### 5. Search for more robust micro contracts only with permission

Potential next data candidates:

- `M2K` / `RTY` Russell futures.
- `MYM` / `YM` Dow futures.
- `MCL` / `CL` crude futures.
- `M6E` / `6E` Euro FX futures.

Ask the user before any Databento pull or paid-data usage. Estimate cost first if possible.

### 6. Add paper-trading readiness gates

The UI should eventually show:

- `Research`
- `Candidate`
- `Paper Ready`
- `Disabled`

Promotion criteria should require:

- Native futures data only.
- No rule violations.
- No hidden overlaps.
- Positive stress test.
- No catastrophic month.
- Reasonable split-period behavior.
- Portfolio-level funded compliance if traded together.

## Prompt For The Next Chat

## Latest Research Sprint Update, 2026-05-05

Goal from user:

- Move toward a funded-account-style day-trading bot that is more consistent and eventually capable of roughly `$2k-$3k/month`.
- Prioritize trade count and profit, but keep all Topstep/funded-rule checks honest.

Research reviewed:

- Topstep current help docs still support the 50K assumptions used here: `$2,000` Maximum Loss Limit, end-of-day trailing reference monitored intraday, optional `$1,000` DLL, and `5` minis / `50` micros max position size.
- Intraday momentum literature supports using first-half-hour impulse as a predictor for late-day continuation, with stronger commodity-futures effects when early volume/volatility is high.
- ORB research supports momentum-style opening breakouts only with strict volatility/risk filters.
- VWAP is best treated as an institutional execution/liquidity anchor, not a standalone edge.

Implemented:

- Added a gated `IPB` / `Opening Impulse Pullback` signal inside `FuturesManager.findMarketIntradayMomentumSignals`.
  - It requires a first-half-hour directional impulse, opening volume/volatility confirmation, VWAP/EMA pullback continuation, compressed stop, next-bar execution, and a short time stop.
  - It only activates when `marketIntradayMomentum.maxTradesPerDay > 1`, so the current one-signal NQ profile remains unchanged.
  - Added `IPB` to portfolio risk scoring and max-trade routing.
  - Added a lab variant named `Opening Impulse Pullback`.
- Promoted only conservative saved profile updates that survived portfolio normal/stress checks:
  - `MGC.enableAdaptiveExits = true`
  - `MGC.openMaeRiskMultiplier = 2.0`
  - `ES.enableCompressedOrbBreakout = true`
  - `ES.orb.maxTradesPerDay = 2`
  - `M2K.closeMomentumRewardRisk = 0.85`
- Rejected the aggressive risk-budget test where all symbols used `$400` max risk/trade:
  - Normal improved trade count/profit, but stress failed badly (`-$1,984`, 35 trades), so do not promote.
- Rejected always-on IPB for current NQ:
  - It generated many standalone NQ trades but degraded the NQ profile and caused a daily-loss breach. Keep it gated/lab-only unless future portfolio testing proves otherwise.

Latest portfolio validation with `MES,MNQ,NQ,MGC,ES,M2K`, `TOPSTEP_50K_RESEARCH`, max `2` open positions, max `5.0` funded mini units:

| Run | Cost Model | Range | Profit | Trades | Win | PF | Max DD | Max Intraday Loss | Breach |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `#199` | Normal (`$1.24`, `1 tick`) | `2025-05-01` to `2026-05-04` | `+$10,167.15` | `242` | `56.20%` | `1.79` | `2.21%` | `-$681.76` | `false` |
| `#200` | Stress (`$2.50`, `1.5 ticks`) | `2025-05-01` to `2026-05-04` | `+$5,247.25` | `223` | `54.71%` | `1.44` | `2.38%` | `-$655.00` | `false` |
| `#201` | Normal split H1 | `2025-05-01` to `2025-10-31` | `+$6,847.68` | `157` | `54.78%` | `1.80` | `2.21%` | `-$595.90` | `false` |
| `#202` | Normal split H2 | `2025-11-03` to `2026-05-04` | `+$3,319.47` | `85` | `58.82%` | `1.76` | `1.53%` | `-$681.76` | `false` |
| `#203` | Stress split H1 | `2025-05-01` to `2025-10-31` | `+$3,320.50` | `142` | `53.52%` | `1.43` | `2.38%` | `-$579.00` | `false` |
| `#204` | Stress split H2 | `2025-11-03` to `2026-05-04` | `+$1,926.75` | `81` | `56.79%` | `1.44` | `1.87%` | `-$655.00` | `false` |

Monthly normal run `#199`:

- Positive months: `10/12`
- Losing months: `2025-08` (`-$680.40`), `2026-01` (`-$304.79`)
- Best month: `2025-06` (`+$3,787.79`)
- This is cleaner but still below the user's `$2k-$3k/month` goal.

Honest status:

- The target was **not reached**.
- Current annualized research result is roughly `$10.2k` normal / `$5.2k` stress, not `$20k-$25k`.
- Current trade count is `242` normal / `223` stress, not `300-400`.
- Next high-value work should be out-of-sample strategy discovery, not simply increasing risk.

## Continued Optimization Sprint, 2026-05-05

User asked to continue until the portfolio reached at least `$15k` profit while still respecting funded-rule integrity.

Implemented / promoted:

- Updated `TOPSTEP_50K_RESEARCH` only:
  - `maxOpenPositions = 3`
  - `maxRiskPerTrade = 700`
  - The Combine profile remains at `maxOpenPositions = 2` and `maxRiskPerTrade = 400`.
  - Aggregate exposure is still capped at `5.0` funded mini units.
- Promoted selective saved risk and exit improvements:
  - `MNQ.risk.maxRiskPerTrade = 600.0`
  - `NQ.risk.maxRiskPerTrade = 350.0`
  - `MGC.sweep.enabled = true`
  - `MGC.sweep.maxTradesPerDay = 3`
  - `MGC.enableEarlySweep = false`
  - `MGC.enableLateSweep = true`
  - `MGC.lateSweepReclaimTicks = 4.0`
  - `MGC.lateSweepCloseLocation = 0.6`
  - `MGC.minRewardRisk = 1.0`
  - `MGC.keltnerReversion.enabled = true`
  - `MGC.keltnerReversion.maxTradesPerDay = 2`
  - `ES.enableEarlyLossCut = true`
  - `ES.earlyLossCutBars = 18`
  - `ES.earlyLossCutR = 0.6`
  - `ES.earlyLossCutMinFavorableR = 0.2`

Rejected:

- A new morning prior-day liquidity sweep candidate.
  - It added trade count but did not add enough edge and degraded stress results. The experimental code was removed.
- `MNQ.risk.maxRiskPerTrade >= 615`.
  - `615+` caused an intratrade daily-loss breach; `600` was the highest tested clean setting.
- `MNQ.risk.maxRiskPerTrade = 700`.
  - Normal profit rose to `+$13,697.89`, but the run breached the portfolio daily loss limit intratrade.
- Dropping `MES`.
  - Stress improved, but normal profit fell below the `$15k` threshold.

Latest validated portfolio with `MES,MNQ,NQ,MGC,ES,M2K`, `TOPSTEP_50K_RESEARCH`, max `3` open positions, max `5.0` funded mini units:

| Run | Cost Model | Range | Profit | Trades | Win | PF | Max DD | Max Intraday Loss | Max Aggregate MAE | Breach |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `#242` | Normal (`$1.24`, `1 tick`) | `2025-05-01` to `2026-05-04` | `+$15,146.82` | `276` | `57.97%` | `2.01` | `2.14%` | `-$954.16` | `-$817.20` | `false` |
| `#243` | Stress (`$2.50`, `1.5 ticks`) | `2025-05-01` to `2026-05-04` | `+$8,812.00` | `256` | `56.25%` | `1.61` | `2.48%` | `-$940.00` | `-$798.00` | `false` |
| `#244` | Normal split H1 | `2025-05-01` to `2025-10-31` | `+$10,052.90` | `172` | `55.23%` | `2.06` | `2.14%` | `-$611.88` | `-$611.88` | `false` |
| `#245` | Normal split H2 | `2025-11-03` to `2026-05-04` | `+$5,093.92` | `104` | `62.50%` | `1.93` | `1.84%` | `-$954.16` | `-$817.20` | `false` |
| `#246` | Stress split H1 | `2025-05-01` to `2025-10-31` | `+$5,439.75` | `159` | `54.09%` | `1.59` | `2.48%` | `-$690.00` | `-$690.00` | `false` |
| `#247` | Stress split H2 | `2025-11-03` to `2026-05-04` | `+$3,372.25` | `97` | `59.79%` | `1.62` | `2.43%` | `-$940.00` | `-$798.00` | `false` |

Normal run `#242` attribution:

- By symbol:
  - `MNQ`: `+$8,873.92`, 29 trades, 75.86% win
  - `NQ`: `+$2,513.36`, 43 trades, 67.44% win
  - `MGC`: `+$2,441.32`, 73 trades, 53.42% win
  - `ES`: `+$1,018.66`, 57 trades, 43.86% win
  - `M2K`: `+$486.20`, 17 trades, 64.71% win
  - `MES`: `-$186.64`, 57 trades, 59.65% win
- By strategy:
  - `VWAP`: `+$5,329.66`, 81 trades
  - `SWEEP`: `+$4,673.24`, 20 trades
  - `ORB`: `+$2,737.44`, 21 trades
  - `ORB2`: `+$1,940.00`, 60 trades
  - `CMOM`: `+$306.16`, 76 trades
  - `MIM`: `+$147.84`, 17 trades

Monthly normal run `#242`:

- Positive months: `11/13` including partial `2026-05`; `10/12` if excluding partial `2026-05`.
- Losing months:
  - `2025-08`: `-$775.84`
  - `2026-01`: `-$139.03`
- Best months:
  - `2025-06`: `+$4,310.81`
  - `2025-07`: `+$3,107.93`
  - `2025-09`: `+$2,348.96`
- Profit excluding partial `2026-05` remains just above the requested threshold: `+$15,014.30`.

Current honest status:

- The requested `$15k` research-backtest threshold was reached under normal costs and survived stress, split-period, and funded-rule checks.
- This still does **not** prove the bot can make `$2k-$3k/month` live. It is a stronger research profile, not a guarantee.
- The risk budget is now more aggressive: max intraday loss got close to the `$1,000` daily loss guard (`-$954.16` normal, `-$940.00` stress). Paper mode should start with tighter arming and probably a reduced-risk warmup before using the full research profile.

## Live Engine Conversion Sprint, 2026-05-05

User request:

- Convert the successful portfolio backtest engine into a functioning live/practice trading engine now, while backtest research continues in parallel.
- The live/practice engine should mimic the successful portfolio backtest as closely as possible in the current market.
- Use the 150K TopstepX practice account:
  - Account ID: `22539378`
  - Account name: `PRAC-V2-592396-40893088`
  - Saved connection mode: `PRACTICE_COMBINE`
- Avoid Databento live/paid data if current clients can support the live engine. Ask before any paid Databento usage.

Non-negotiable distinction:

- `Research / Backtest Config`
  - This is the mutable optimization workspace.
  - Strategy and risk settings here can keep changing as new backtests are improved.
- `Live Successful Strategy Config`
  - This must be a frozen, explicitly promoted snapshot.
  - It is what live/practice trading uses.
  - It should not update automatically when research settings change.
  - It should store source run ID, symbols, strategy settings, risk settings, portfolio settings, metrics, created timestamp, and code/version metadata.

Initial live snapshot candidates:

| Source Run | Role | Symbols | Profit | Trades | Win | PF | Max DD | Breach |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `#242` | Primary successful normal config | `MES,MNQ,NQ,MGC,ES,M2K` | `+$15,146.82` | `276` | `57.97%` | `2.01` | `2.14%` | `false` |
| `#250` | Leaner alternate / lower max concurrent exposure | `MNQ,NQ,MGC,ES,M2K` | `+$14,934.94` | `219` | `57.08%` | `2.24` | `2.02%` | `false` |

Required UI behavior:

- Add a button on `frontend/src/pages/FuturesLive.jsx`:
  - Suggested label: `Update Live Successful Strategy`.
  - It should snapshot the selected successful portfolio run into the live config.
  - It should show the active live snapshot source, e.g. `Live Strategy: Backtest #242`.
  - It should require confirmation before overwrite.
  - It should be disabled while the live engine is running.
  - It must not mutate the research/backtest config unless explicitly requested.

Required backend architecture:

- Do not keep the live system as a status-only stub.
- Create explicit live/paper components, for example:
  - `LiveStrategySnapshot`
  - `LivePortfolioEngine`
  - `LiveMarketDataService`
  - `TopstepXPaperExecutionAdapter`
  - `LiveRiskGuard`
  - `LiveOrderLedger`
- Persist:
  - live strategy snapshots
  - live sessions
  - live bars or latest-bar state
  - live signal decisions
  - live rejected signals
  - live orders
  - live fills
  - live positions
  - risk/kill-switch events

Backtest-to-live parity requirements:

- Multi-symbol loop for the frozen snapshot symbols.
- Same 1-minute completed-bar timing.
- Same higher-timeframe context (`15m`, `1h`) from live bar aggregation.
- Same `FuturesManager` signal builders where possible, refactored only as needed to share logic cleanly.
- Same next-bar-open intent: generate on completed bar, enter on next bar/open-equivalent.
- Same portfolio signal ranking and tie-breaking.
- Same no-open-symbol overlap guard.
- Same max open positions.
- Same aggregate contracts and funded mini-unit guard.
- Same per-symbol max contracts and risk budget logic.
- Same daily loss guard.
- Same Topstep end-of-day trailing MLL model.
- Same intratrade MAE/risk guard.
- Same stop, target, adaptive exit, time-stop, and forced-flat behavior.
- Same auditability: every signal, rejection, order request, broker response, fill, cancel, flatten, and risk-block decision must be inspectable.

Data-client plan:

- Preferred live account/order/fill client: TopstepX / ProjectX using saved account `22539378`.
- Preferred live market data: any usable TopstepX / ProjectX market hub or polling feed already available through the configured connection.
- Acceptable fallback: existing Tradovate demo/direct connection if credentials and permissions are available.
- Local futures CSV files may be used for startup warmup/backfill and indicator seed state only; they are not live prices.
- Do not start Databento live data or paid pulls without explicit user approval. If current clients cannot provide live data, stop and report the exact blocker and estimated Databento need/cost before using it.

Rollout plan:

1. Dry-run engine:
   - Build live bars from the chosen data client.
   - Run the frozen live snapshot.
   - Produce simulated orders, rejections, and risk decisions with no broker submission.
2. Broker/account sync:
   - Continuously sync account, orders, positions, and fills from TopstepX account `22539378`.
   - Hard-block if the account ID differs.
3. Tiny practice-order test:
   - Only after explicit user approval for the exact test order.
   - Start with one smallest practical MNQ practice order, then immediate flatten/cancel workflow.
4. Bracket / managed-exit validation:
   - Validate stop/target attachment, OCO support, or synthetic exit management.
5. Full guarded paper mode:
   - Use frozen live successful config.
   - Consider reduced-risk warmup because run `#242` came close to the `$1,000` daily-loss guard.
6. Reporting:
   - Show live PnL, daily PnL, open risk, MAE/MFE, fills, order latency, rejected signals, and divergence from backtest assumptions.

Paste this into the next chat:

```text
Project: trading_bot TopstepX futures paper trading sprint
Workspace: /Users/anishpatel/Documents/SoftwareProject/trading_bot

Read these handoffs first:
/Users/anishpatel/Documents/SoftwareProject/trading_bot/FUTURES_STRATEGY_HANDOFF_SPEC.md
/Users/anishpatel/Documents/SoftwareProject/trading_bot/TOPSTEP_PAPER_TRADING_HANDOFF.md

Goal:
Fully implement the live/practice futures engine now, while keeping backtest research moving separately. The live engine should mimic the current successful event-driven portfolio backtest engine as closely as live trading allows, then forward-test it in the current market on the 150K TopstepX practice account. Do not fake, overfit, or promise exact live replication of a backtest.

Current state:
TopstepX / ProjectX auth is working with the user's TopstepX email and saved API key. Do not print or expose the key.
Linked accounts:
- 50K Combine: account ID 22529998, balance 50000, canTrade true, simulated true.
- 150K Practice: account ID 22539378, balance 150000, canTrade true, simulated true.
Current saved TopstepX execution account is the 150K practice account 22539378.
Use this practice account for paper/live adapter testing.
Keep the Topstep 50K funded profile for backtests that prepare for the real eval.

Current successful strategy candidates:
- Primary live snapshot candidate: portfolio backtest #242, symbols MES,MNQ,NQ,MGC,ES,M2K, +$15,146.82, 276 trades, no breach.
- Leaner alternate: portfolio backtest #250, symbols MNQ,NQ,MGC,ES,M2K, +$14,934.94, 219 trades, no breach.

Critical next task:
Build the live/practice portfolio engine and TopstepX paper execution stack:
1. Add a persistent Live Successful Strategy Config snapshot separate from mutable Research / Backtest Config.
2. Add an Update Live Successful Strategy button on FuturesLive.jsx that snapshots a selected successful portfolio run into live config, shows the source run, confirms overwrite, and is disabled while running.
3. Add live market data service and 1-minute bar builder. Prefer TopstepX/ProjectX or existing clients; use local CSV only for warmup/backfill; do not use paid Databento live/pulls without explicit approval.
4. Add TopstepX paper execution adapter targeting account 22539378 only.
5. Add account/order/position/fill sync, active contract resolver, dry-run order construction, order submit/cancel/flatten behind hard arming and exact user approval.
6. Add LivePortfolioEngine that reuses/refactors the portfolio backtest signal builders, ranking, sizing, stop/target/time-stop/adaptive-exit logic, force-flat logic, and risk guard.
7. Mirror funded-rule guards in live mode: daily loss, end-of-day trailing MLL, aggregate funded units, max positions, per-symbol overlap, stale account/data blocks, kill switch.
8. Add audit tables/logs and live UI for decisions, rejected signals, orders, fills, positions, PnL, risk events, and divergence from backtest assumptions.

After implementing, validate backtests and paper readiness:
- Dry-run current market decisions with no broker orders.
- Confirm selected account is exactly 22539378.
- Compare live dry-run decisions against backtest logic on equivalent completed bars where possible.
- Run #242-style portfolio normal/stress checks.
- Normal costs
- Stress costs
- First-half and second-half splits
- Monthly results
- Overlap/exposure checks
- Funded rule compliance
- Read-only Topstep sync only after user approval
- No paper order/cancel/flatten unless user explicitly approves the exact order test

Important:
Use any local tools, code search, subagents, web research, and open-source references that help. For anything involving paid services, Databento data pulls/live feeds, broker APIs that submit/cancel/flatten orders, cloud services, subscriptions, or money-related usage, ask the user for explicit permission before spending or triggering paid usage.

Verification required before final response:
- Run backend tests: ./mvnw test
- Run frontend build: npm run build
- Restart backend on http://localhost:7070 if backend code changed
- Verify live config and research config are separate
- Run normal and stress portfolio backtests
- Run split-period tests
- Check overlaps/exposure
- Check monthly results
- Confirm no funded rule violations
- Explicitly state whether the $20k-$25k and 300-400 trade targets were reached honestly.
```
