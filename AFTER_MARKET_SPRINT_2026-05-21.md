# After-Market Sprint 2026-05-21

Purpose: implementation state for the current bug-fix sprint. Re-read this file after any context reset and update it before long pauses or context-heavy transitions.

Scope rules:

- Edit only inside `trading_bot`.
- Do not hand-edit `live_backend`.
- Keep the live backend running unless explicitly told otherwise.
- If actual strategy signal logic changes, immediately tell the user what changed and how it affects the live bot.
- Risk sizing/order validation may be changed to make valid live trades fit the configured account rules, but do not manually increase ORB frequency or loosen ORB signal generation without approval.

Clarified requirements:

- Backtest analytics should include summary-level average risk/reward, not risk/reward as a primary per-trade statistic.
- Add average daily, weekly, and monthly PnL.
- Add best/worst day, week, and month.
- Keep previously proposed statistics: positive period percentages, expectancy, average win, average loss, payoff ratio, contract names, richer table/export display.
- ORB work should fix blocked-order sizing/diagnostics so valid trades can fit criteria. Do not force more ORB signals by changing ORB frequency rules.
- Runtime scripting / PC migration remains backburner for this sprint.
- 2026-05-21 latest Live page correction: the Live Controls launch hub should not show the copied-strategy mismatch subtext or the old Live Strategy / Account ID / Symbols cards. Live strategy source and live risk/account portfolio settings are separate. The copied Live Strategy slot remains the signal source; the new Live Risk Portfolio Config controls account size, Topstep profile/account, sizing, exposure, commission, slippage, and profit target for live order sizing.

Implementation checklist:

- [x] Broker reconciliation: match closes by account/symbol/side/order lineage/custom tag/bracket metadata and reject stale pre-entry fills.
- [x] Broker reconciliation: use pending reconcile state instead of fake/stale flat-sync PnL when no matching broker close fill exists.
- [x] Trade provenance: preserve strategy labels/reasons across bot stop/restart and broker history reloads.
- [x] API consistency: expose `brokerOrderId` consistently from decisions, orders, events, and UI rows.
- [x] ORB/order blocked: add structured sizing diagnostics and repair live sizing to use the intended profile/config bundle without changing ORB signal frequency.
- [x] Live config: make selected account/profile/source strategy bundle coherent and visible; warn/block mismatches.
- [x] Logs backend: coalesce repetitive close, flatten, market data stopped/resumed, and gate events.
- [x] Logs frontend: replace/move Equity Review into a compact right-side slide-out log drawer with newest first and concise cards.
- [x] Drawdown: compute useful Topstep/funded drawdown or cushion values when broker drawdown is zero/unavailable.
- [x] Backtest analytics: add daily/weekly/monthly averages, best/worst periods, positive-period percentages, average R/R summary, expectancy, average win/loss, payoff ratio, and contract name visibility.
- [x] Verify with backend tests and frontend build.

Progress log:

- 2026-05-21: Sprint file created as the durable context/memory anchor for this sprint.
- 2026-05-21: Re-read `Documents/AfterMarketTodoLog.md` and `Documents/ImplementationHandoff.md` after context compaction. Current implementation remains scoped to `trading_bot`.
- 2026-05-21: Existing tracked backend already contains the first reconciliation/sizing pass: broker close matching is order-lineage based, stale time/price fallback is removed, unmatched broker-flat cases can become `PENDING_BROKER_RECONCILE`, submitted orders expose `brokerOrderId`/`customTag`, decisions expose `customTag` and `sizingDiagnostics`, and ORB live sizing can compress only the stop/target plan to fit max-risk ticks without changing ORB signal frequency.
- 2026-05-21: Existing tracked runtime cache already clears authoritative empty broker positions/orders and keeps `/api/futures/live/marks` from publishing account/open-position truth. Next backend work: preserve broker order IDs/custom tags/raw numeric statuses in realtime order/trade cache rows and then recompile.
- 2026-05-21: Runtime order/trade cache patched to preserve `brokerOrderId`, `customTag`, raw ProjectX status, mapped status labels, and gross/net PnL. Backend compile passed.
- 2026-05-21: Portfolio backtest analytics API expanded: trade rows include `contractName`; segments now include daily/weekly/monthly/quarterly plus a summary object with average daily/weekly/monthly PnL, best/worst day/week/month, positive-period percentages, expectancy, average win/loss, payoff ratio, and average risk/reward. Backend compile passed.
- 2026-05-21: Live metrics now return actual drawdown used plus `drawdownCushion`, trailing drawdown limit, and trailing drawdown floor. Frontend displays cushion when broker/account drawdown is zero.
- 2026-05-21: Live log UX changed from bottom Equity Review table to a right-side drawer/tab. Frontend and backend coalesce repeated market data, entry gate, and post-close events; drawer shows newest-first concise log cards.
- 2026-05-21: Frontend broker provenance matching now carries custom tags and treats Topstep bracket child order IDs (`entryOrderId + 1..4`) as related to the saved live entry, reducing false `UNTRACKED` broker TP/SL rows after restarts.
- 2026-05-21: Live strategy/account coherence tightened in the frontend: start is disabled when the active Live Strategy slot profile/account does not match the selected Topstep account, and the Live Strategy chip shows the promoted portfolio run ID when available. Existing backend start guards remain in place.
- 2026-05-21: Verification passed: `./mvnw -q -DskipTests compile`, `./mvnw -q test`, and `npm run build`. Browser smoke check passed on `http://127.0.0.1:5175/futures-live`; log drawer opens and renders the concise newest-first card list.
- 2026-05-21: Follow-up correction started after user screenshots: removing the mismatch/subtext design from the Live Controls hub and replacing it with a separate Live Risk Portfolio Config modeled after the backtest Portfolio Run Builder. Frontend now defaults to Topstep 150K Research rules, exposes live risk fields, and sends those fields on `/api/futures/live/start`. Backend start guard no longer requires the copied Live Strategy snapshot profile/account to match the selected live risk profile.
- 2026-05-21: Follow-up correction verified: `./mvnw -q -DskipTests compile`, `./mvnw -q test`, and `npm run build` passed. Browser smoke check on `http://127.0.0.1:5175/futures-live` confirmed the Live Controls hub only shows status/actions, the old mismatch subtext is gone, the old Live Strategy / Account ID / Symbols launch cards are gone, and Live Risk Portfolio Config defaults to Topstep 150K Research with account size 150000 and max risk/trade 2100.
