# MYM/MCL And Lagging Contract Research Notes

Date: 2026-05-26
Scope: dev-only research in `/Users/anishpatel/Documents/SoftwareProject/trading_bot`.

## Hard Rules

- Do not edit `/Users/anishpatel/Documents/SoftwareProject/live_backend`.
- Do not push to any remote.
- Do not pull new market data unless explicitly re-approved.
- Preserve `80kprofit`.
- Keep `mcl_mym_baseline_20260526` as the fallback checkpoint.
- Promote to WIP only if total portfolio improves without breaking risk rules.

## Current Checkpoint

- Preset: `mcl_mym_baseline_20260526`
- WIP currently matches checkpoint exactly.
- Portfolio through 2026-05-22: 1053 trades, $86,443.12, 73.88% win, PF 2.81, 4.02% max DD, 0/0 breaches.
- MYM: 28 trades, $3,534.20, 71.43% win, $126.22 avg.
- MCL: 20 trades, $4,075.64, 85.00% win, $203.78 avg.

## Deficiencies To Attack

- MYM/MCL: trade count is far below the desired 200+ combined target.
- ES: low trade count and weak win rate.
- M2K: high win rate and many trades, but average profit is too small.
- MES: many trades, acceptable win rate, but average profit is modest.
- NQ: many trades, weaker win rate and average profit versus its potential.

## Research Direction

- For MCL, raw EIA breakout logic is not enough. Prior sweep showed more trades but worse P/L. Need post-release confirmation or late-day follow-through rather than first-spike chasing.
- For MYM, standalone confirmation/retest logic can crowd out better portfolio trades. Need either a non-crowding score/priority change or a stricter trigger.
- For M2K/MES, exits and reward/risk may matter more than trade count.
- For ES/NQ, quality filters and higher-confidence adds are more important than simply enabling more signals.

## Running Log

- Created this dump before additional improvement work.
- Reconfirmed WIP exactly matches `mcl_mym_baseline_20260526` before more research.
- Current best expanded checkpoint remains 1053 trades, $86,443.12, 73.88% win, PF 2.81, 0 rule violations.
- Historical high-count MYM/MCL attempts are not acceptable yet: the best 100+ MYM/MCL-trade run was only $83,434.25 total, and the best 150+ MYM/MCL-trade run dropped to $80,546.06 with only $10.51 average new-symbol profit.
- Research posture: do not promote high-count MYM/MCL configs unless they improve total portfolio profit and preserve the current risk/quality profile.
- Broad deficiency sweep confirmed the count problem is real: MYM/MCL WFT/ECHO can add a few trades, but aggressive FVG/PDB/micro-scalp/momentum-density versions either lose money or pull win rate/PF down too hard.
- Best accepted WIP after M2K refinement: run 2063, `m2k_best_plus_wft_echo_probe`.
- Accepted WIP totals: 1061 trades, $91,192.50, 73.42% win, PF 2.73, 3.74% max DD, 0/0 breaches, 0 violations.
- Delta versus checkpoint: +8 trades, +$4,749.38, -0.46 win-rate points, -0.08 PF, drawdown improved from 4.02% to 3.74%.
- Main fix: M2K improved from 139 trades / $3,345.28 / $24.07 avg to 141 trades / $8,070.22 / $57.24 avg.
- MYM/MCL are still not at the desired 200+ combined trade target. Accepted WIP has MYM 36 trades / $3,403.56 and MCL 21 trades / $4,061.68. The best MYM index-shadow count probe reached 76 MYM trades, but it fell to 72.88% total win and was rejected.
- Saved the accepted WIP to dev preset `m2k_refinement_20260526` while preserving `mcl_mym_baseline_20260526`.
- Phase6 accepted `mym_shadow_add_lorb_source` and saved `contract_health_phase6_20260526`.
- Phase6 totals: 1075 trades, $93,934.78, 73.40% win, PF 2.70, 3.68% DD, 0/0 breaches.
- Phase6 MYM: 56 trades, $4,190.32, 67.86% win, $74.83 avg.
- Phase6 MCL: 20 trades, $4,284.20, 85.00% win, $214.21 avg.
- Phase6 finding: adding `LORB` as a MYM shadow source added 5 MYM trades and a small total PnL lift, but broad MYM shadow source expansions reached 90-117 MYM trades while degrading MYM quality and total portfolio performance.
- Phase7 tested contract-specific structure modules for MYM/MCL from the phase6 base.
- Phase7 rejected every candidate and restored phase6. The high-count MYM modules were not live-quality: `IDXCONF` produced up to 283 MYM trades but MYM went deeply negative and triggered daily-loss violations; `MYMORB2` and `LORB` also failed rule/quality gates.
- Phase7 MCL structure tests were also rejected. `EIA`, `FVG`, `PDB`, `MSCALP`, and `LORB` increased MCL count, but the added MCL trades were negative or too low-quality. Crude-open variants were safe but produced no additional trades on the available data.
- Phase8 tested laggard repair for M2K/MES/NQ/ES from the phase6 base.
- Phase8 accepted `m2k_soft_loss_cut_open078` and saved `contract_health_phase8_20260526`.
- Phase8 totals: 1075 trades, $94,401.76, 73.40% win, PF 2.73, 3.68% DD, 0/0 breaches.
- Phase8 M2K: 141 trades, $9,998.20, 80.85% win, $70.91 avg.
- Phase8 MYM/MCL unchanged from phase6: MYM 56 / $4,190.32, MCL 20 / $4,284.20.
- Phase8 rejected MES profit jump candidate despite higher total PnL because it dropped total win rate to 68.10% and MES win rate to about 49.73%, which looks like big winners masking poor trade quality.
- Phase8 rejected NQ IPB trim as main WIP because it improved NQ and portfolio win rate but gave up too much total PnL.
- Phase8 rejected ES quality expansion because FVG/PDB caused rule breaches and negative ES contribution; ES VWAP quality lowered drawdown but cut too much PnL.
- Current best dev WIP is `contract_health_phase8_20260526`.
- Current readiness verdict: MYM/MCL are still not ready to move live under the 200+ healthy-trade target. High-count versions found so far are noisy/negative and should not be promoted.
- Phase9 tested stackability from the phase8 WIP and saved no new preset.
- Phase9 result: restored WIP from `contract_health_phase8_20260526`; no candidate beat checkpoint under quality gates.
- Best rejected phase9 candidate was `mes_high_profit_guarded`: 1047 trades, $99,034.86, 68.10% win, PF 2.43, 0/0 breaches. It was rejected because the profit came with poor trade quality: MES was 185 trades / $12,980.04 / 49.73% win and total win rate fell too far.
- Phase9 NQ quality mode improved portfolio win to 74.23%, PF to 2.77, and NQ win to 69.17%, but dropped total PnL to $93,024.64, so it remains an optional quality-mode idea rather than the main WIP.
- Phase9 ES quality stack improved drawdown but cut too much PnL; not a main WIP candidate.
- Phase10 installed two dev-only research modules: `MYMBR` (MYM breadth fade) and `MCLTC` (MCL trend fade). They are saved/loaded/exposed to the Strategy page, but defaults are disabled.
- Phase10 tested both continuation and fade behavior for these modules from the phase8 WIP. No candidate was accepted; WIP was restored from `contract_health_phase8_20260526`.
- Phase10 continuation pass: best rejected was `mym_short_mcl_long_density`, 68 trades, -$1,996.10, 57.35% win, PF 0.65. `MYMBR` and `MCLTC` both showed negative expectancy.
- Phase10 fade pass: best rejected was `mcl_trend_long_balanced`, 53 trades, -$1,991.48, 49.06% win, PF 0.65. `MCLTC` was 25 trades / -$3,401.64 / 16.00% win in that run.
- Research conclusion: broad MYM breadth and MCL trend/fade logic should stay off. The profitable MYM/MCL behavior remains narrow: MYM ORB-long, MYM selected short shadow/sweep/close-momentum pockets, and MCL late-day AFT/MIM plus occasional ORB/CMOM.
- Current saved WIP rows still match `contract_health_phase8_20260526`; no live move is recommended for MYM/MCL under the 200+ healthy-trade target.
