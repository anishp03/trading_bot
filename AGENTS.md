# Codex Project Operating Manual

This file is the standing runbook for every Codex chat opened from `/Users/anishpatel/Documents/SoftwareProject`. Treat it as always active, not as a checklist only for large tasks.

The purpose of this file is narrow: prepare the chat to work correctly, retrieve the right memory, protect the live trading system, verify claims, and write useful memory back after the chat.

## Every Chat Startup

Purpose: every Codex chat must hydrate itself from current project memory before planning, editing, diagnosing, or making strong claims. Do not answer from model memory alone when project RAG or source files can be queried.

1. Read this `AGENTS.md` at the start of every chat.
2. Identify the user's current task in one sentence. Use that sentence as the retrieval seed.
3. Run the automatic startup loop:

   ```bash
   python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py startup \
     --title "<short chat title>" \
     --task "<one sentence task summary>"
   ```

4. Convert retrieval into working context before acting: list the relevant source paths, discard weak/noisy/stale chunks, and read the retrieved source files directly before editing or relying on a conclusion.
5. If retrieval returns weak context, prints a stale-index warning, or conflicts with current source/runtime evidence, refresh the index, rerun retrieval with a narrower query, then inspect the relevant files manually.
6. Read only the source/docs needed for the current task. Do not read sprint planning docs unless the user explicitly asks to work on sprint items.
7. Re-read any file immediately before editing it.
8. During the chat, append concise current-chat scratch notes for useful in-progress facts, decisions, diagnostics, implementation results, rejected hypotheses, or user constraints:

   ```bash
   python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py append \
     --role note \
     --topic "<subsystem.micro-topic>" \
     --text "<concise note>"
   ```

The startup wrapper performs the repetitive mechanics: starts current-chat scratch, refreshes the index, queries permanent RAG, and runs compact context-pack retrieval when the task is broad enough to need it.

## Every Chat Closeout

Purpose: every completed chat must leave the next chat with better project context, without indexing guesses or raw noisy transcript.

Before the final response, run exactly one closeout path:

### A. Durable memory was created

Use this when the chat produced stable facts, decisions, diagnostics, implementation results, rejected hypotheses, or subsystem explanations that future chats should retrieve.

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout \
  --title "<short chat title>" \
  --summary "<curated summary of durable outcome>" \
  --tag "<tag>" \
  --fact "<subsystem.micro-topic>::<evidence-backed durable fact>" \
  --link "<source file path, wiki link, endpoint, eval, or command proving the fact>"
```

Pass multiple `--fact`, `--tag`, and `--link` arguments when needed. Facts require at least one source/evidence link unless the fact is an explicit user preference or a source-free user decision; only then may `--allow-unsourced` be used.

### B. No durable memory was created

Use this for routine reads, failed quick experiments with no durable conclusion, or small tasks that changed nothing future chats need.

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout \
  --title "<short chat title>" \
  --no-memory
```

This still refreshes the index and closes current-chat scratch so modified project files remain retrievable and scratch does not pollute future context.

## Anti-Hallucination Contract

The RAG system must prevent hallucinations, not preserve them.

- Retrieval is a pointer, not proof. Current code, current runtime data, explicit user decisions, and curated project memory win over stale retrieved chunks.
- Do not implement from graph-only or chat-trace-only context. Read the relevant source files, tests, frontend/backend contracts, or runtime evidence directly before changing behavior.
- Never write permanent memory for guesses, unverified interpretations, raw transcript, noisy command output, or routine file reads.
- Do not write a `--fact` unless it is supported by a source/evidence link or is explicitly a user preference/source-free decision.
- Rejected hypotheses may be deposited only when labeled as rejected and useful for future diagnostics.
- If `query_project.py` warns that the index is stale, do not rely on the retrieved result until `index_project.py` has refreshed the index.
- `ProjectBrain/RAG/CurrentChat/` is scratch only and must remain excluded from the permanent corpus.
- Graph/reference-expanded chunks are hints. They cannot outrank direct source evidence without direct query support.
- Keep retrieved context compact. More context is not automatically better.

## Automatic RAG Architecture

Every chat has two automatic RAG loops.

### Startup retrieval loop

`task summary -> incremental index refresh -> permanent RAG query -> optional context-pack query -> current-chat query -> source verification -> answer/edit plan`

Rules:

- Permanent RAG is queried on every chat startup before substantive work.
- Incremental indexing runs before retrieval to reduce stale-context failures.
- Context packs are used only when the task crosses subsystem boundaries or needs broader diagnosis; normal chats should keep retrieved context small.
- Graph/reference-expanded chunks are hints, not authority. Direct source files, current runtime state, and explicit user decisions are the source of truth.
- Sprint planning docs are not retrieved unless the task is explicitly about sprint planning.

### Closeout ingestion loop

`final action summary -> curated memory cards -> evidence-gated ChatDumps/category notes -> graph-link repair -> index refresh -> automatic scratch closeout -> final response`

Rules:

- No manual trigger is required.
- The assistant must perform RAG write-back automatically before the final response whenever the chat produced durable project knowledge.
- Use the visible chat plus current-chat scratch together because scratch may lag the latest turns.
- Write one curated chat dump for useful completed work, plus category notes only for durable decisions, diagnostics, reports, rejected hypotheses, or subsystem explanations that should be retrieved independently.
- Do not dump raw transcripts, secrets, credentials, `.env` values, broker tokens, account secrets, noisy logs, or routine command output into permanent RAG.
- After memory write-back, run `graph_link_repair.py --apply --ensure-section` so new reports, chat dumps, decisions, and concept notes reconnect to the main Obsidian/RAG graph before indexing.
- After graph-link repair, run `index_project.py` so the next chat retrieves the new facts.
- Close the current-chat scratch layer automatically after successful write-back with `current_chat_memory.py closeout`. This is automatic maintenance, not a user-triggered archive workflow.

## RAG Commands

Use `chat_rag_cycle.py` as the canonical wrapper and the other scripts as direct fallback/debug commands.

- Automatic startup loop:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py startup --title "<short chat title>" --task "<task summary>"`
- Automatic closeout with durable memory:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout --title "<chat title>" --summary "<summary>" --fact "<subsystem.micro-topic>::<fact>" --link "<source/evidence>"`
- Automatic closeout with no durable memory:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout --title "<chat title>" --no-memory`
- RAG health check:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/rag_health.py`

Direct fallback/debug commands:

- Refresh changed files before retrieval and after memory write-back:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/index_project.py`
- Rebuild from scratch after RAG architecture, chunking, ranking, or corpus changes:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/index_project.py --reset --stats`
- Query memory:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/query_project.py "<task summary>" --top-k 8`
- Broad context-pack retrieval:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/query_project.py "<subsystem or edit area>" --context-pack --top-k 16 --include-context`
- Start/query/append/export/close current-chat scratch memory:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py start --title "<short chat title>" --task "<task summary>"`
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py append --role note --topic "<subsystem.micro-topic>" --text "<concise note>"`
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py query "<task summary>" --top-k 8`
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py export`
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py closeout --reason "Automatic RAG closeout after memory write-back"`
- Deposit an automatic curated chat memory dump:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/deposit_chat_memory.py --title "<chat title>" --summary "<curated summary>" --tag "<tag>" --fact "<subsystem.micro-topic>::<durable fact>" --link "<source path or wiki link>"`
- Repair vault graph links before indexing:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/graph_link_repair.py --apply --ensure-section`
- Run retrieval evals before RAG ranking/chunking/corpus changes:
  `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/eval_rag.py`

Implementation notes:

- `query_project.py` prints ranked source paths with line numbers and excerpts.
- `deposit_chat_memory.py` appends curated chat-level memory to `ProjectBrain/Vault/ChatDumps/Chat Memory Ledger.md` by default. Use `--per-chat-file` only for an explicit isolated audit trace.
- `current_chat_memory.py` manages an isolated scratch component under `ProjectBrain/RAG/CurrentChat/`; this is current-chat working memory and is not part of the permanent RAG index.
- `eval_rag.py` checks retrieval changes against local expected-context cases.
- `index_project.py` stores chunks in `/Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/storage/project_memory.sqlite3`.
- Retrieval uses local SQLite FTS5 plus sparse token-vector reranking.
- Broad context packs use contextual chunk headers, metadata ranking, query expansion, reference-following, and diversified results across related files.
- Query output includes authority labels. `source-truth` and current runtime evidence outrank `curated-memory`; `chat-trace` and `graph-hint` are orientation only.
- Optional local hash embeddings are supported but disabled by default unless `memory.yml` enables them.
- The scripts are local-only: no API keys, no network calls, and no third-party Python packages.
- Retrieval output is a pointer, not proof. Read cited files directly before changing code or relying on a conclusion.

## Project Paths

- Project root: `/Users/anishpatel/Documents/SoftwareProject`
- Git repository and development workspace: `/Users/anishpatel/Documents/SoftwareProject`
- Tracked local-verification backend: `/Users/anishpatel/Documents/SoftwareProject/production_backend`
- Tracked frontend: `/Users/anishpatel/Documents/SoftwareProject/frontend`
- Operational scripts: `/Users/anishpatel/Documents/SoftwareProject/scripts`
- Development runtime state: `/Users/anishpatel/Documents/SoftwareProject/dev_runtime`
- Read-only live runtime: `/Users/anishpatel/Documents/SoftwareProject/live_backend`
- Project memory vault: `/Users/anishpatel/Documents/SoftwareProject/ProjectBrain/Vault`
- RAG home: `/Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG`
- Sprint planning note: `/Users/anishpatel/Documents/SoftwareProject/ProjectBrain/Vault/Sprints/NextSprint.md`

Normal ports:

- Live backend: `127.0.0.1:7070`
- Dev backend: `127.0.0.1:7071`
- Dev frontend: `127.0.0.1:5174` or the next available Vite port

## Non-Negotiable Safety

1. Treat this as a live trading system. Small unintended behavior changes matter.
2. Never put broker credentials, Topstep/ProjectX credentials, Cloudflare tokens, API keys, account secrets, `.env` values, or SQLite secrets into tracked files, docs, logs, or reports.
3. Do not commit, push, deploy, restart live services, or promote code unless the user explicitly asks.
4. Do not revert user changes unless the user explicitly asks.
5. Preserve databases, market data, logs, backups, and runtime artifacts unless the user explicitly asks to modify them.
6. Keep `live_backend` physically read-only. It may be inspected for diagnostics. It must not be edited by hand.
7. All code modifications happen in the tracked root workspace first. Backend changes belong in `production_backend`; `live_backend` is never a source workspace.
8. If strategy logic, signal filters, risk rules, sizing, DTM behavior, broker reconciliation, preset defaults, or deployment state change, report the exact behavioral impact.

## Senior Engineering Override

Do not equate "smallest diff" with "correct work." Use senior engineering judgment:

- If the requested change exposes duplicated state, broken ownership, stale assumptions, or inconsistent data contracts, call it out.
- Fix root causes when the defect is structural and the blast radius is clear.
- Keep unrelated cleanup out of scope, but do not leave an obviously fragile patch when a focused structural fix is safer.
- For refactors touching more than five files, split work into phases of no more than five files, verify each phase, and get user approval before continuing.
- For structural refactors on files over 300 LOC, first remove dead imports, dead props, unused exports, and debug-only noise in a separate cleanup phase when doing so is required for a safe edit.

## Context Discipline

1. Use `rg` or `rg --files` first for local search.
2. Files over 500 LOC must be read in chunks around the relevant symbols. Do not assume one read captures the full file.
3. Search results can be truncated. If results look suspiciously small or broad, rerun by directory, extension, or exact symbol.
4. After long chats, resumes, or context compaction, re-read every file before editing it again.
5. For renames or signature changes, search separately for direct references, type references, string literals, dynamic imports, `require` calls, re-exports, barrel files, tests, mocks, frontend payload fields, backend DTOs, and persistence keys.
6. After editing a file, read the changed region again before claiming success.

## Backend Frontend Contract Integrity

Maintain one coherent contract between backend, frontend, database fields, and runtime payloads.

- A frontend CRUD field must map to the exact backend field that stores and serves it.
- Do not create duplicate config names, parallel payload shapes, or legacy aliases unless the user explicitly asks for a migration bridge.
- When changing config payloads, inspect both request and response paths.
- Verify that backend defaults, frontend defaults, saved DB values, and displayed UI values agree.
- Strategy Config and Risk Config remain separate concepts.
- Live start must send the selected Strategy Config preset, selected Risk Config, selected account, symbols, and enabled live features without silently substituting another config.
- If a backend field is renamed, update frontend forms, tables, local storage merge logic, docs, tests, and any live-trade reasoning payloads that consume it.

## Backtest Live Bot Integrity

This is a very important rule: every strategy/backtest change must preserve integrity between backtests and the live bot.

- Backtests and live trading must use the same strategy rules, detector logic, preset settings, signal timing, and risk handoff assumptions unless a live-only execution feature is explicitly documented.
- The default goal is execution parity: when a setup is valid in the approved backtest logic and is live-executable under broker/risk constraints, the live bot should attempt to take it. Do not turn backtest/live parity into a reason to block good backtest-qualified setups.
- The live market will not reproduce backtest results exactly. Integrity means the live bot is following the same strategy logic and attempting the same eligible opportunities the backtest used, while clearly separating real broker non-fills, partial fills, slippage, cancels, and DTM/order-management effects.
- A missed backtest winner in live is a live execution/fillability defect to investigate first, not a strategy defect and not a reason to add stricter signal filters by default.
- Strategy Config preset changes must be traceable from backtest generation to live startup.
- If a strategy detector, filter, timing rule, signal note, preset default, risk handoff, or candidate validation path changes, verify whether the live bot uses the same path or needs a parity update.
- Entry Optimizer, DTM, live entry-decay checks, and broker order-type choices are allowed live execution layers, but they must not silently remove backtest-qualified winners. If they skip, reprice, cancel, or convert an entry into a resting order, the report must state whether the backtest trade would have been attempted and whether live execution prevented the fill.
- When reporting a strategy or backtest change, explicitly state whether live bot parity was preserved, changed, or still needs verification.

## Generalized Strategy Improvement

This is a very important rule: strategy improvement must pursue generalized market-structure quality, not curve-fitting.

- Do not add narrow filters that merely make a historical backtest mimic known data.
- Prefer strategy logic that identifies durable setup quality: market structure, trend context, liquidity behavior, volatility/risk geometry, volume participation, entry freshness, and invalidation quality.
- A good generalized strategy will still take some losing trades. Do not "fix" every loss unless evidence shows the strategy accepted a false positive or violated its own rules.
- Separate normal market variance from logic defects. A losing trade can be acceptable when the setup matched the strategy rules and market structure supported the entry.
- Do not propose extra live filters, stricter decay blocks, or strategy removals when the stronger evidence is that live failed to execute a backtest-qualified setup. Fix missed-entry execution, order type, repricing, fill tracking, or broker-state handling first.
- When proposing strategy changes, explain why the change should generalize beyond the sampled backtest window and how it will be checked against live bot parity.

## Verification Rules

File writes only prove bytes changed. They do not prove the project works.

Choose the narrowest verification that proves the change, then broaden when shared runtime behavior is touched:

- Backend compile: `cd /Users/anishpatel/Documents/SoftwareProject/production_backend && ./mvnw -q -DskipTests compile`
- Backend focused test: `cd /Users/anishpatel/Documents/SoftwareProject/production_backend && ./mvnw -q -Dtest=ClassNameTest test`
- Backend full tests for shared strategy/risk/live/broker code: `cd /Users/anishpatel/Documents/SoftwareProject/production_backend && ./mvnw -q test`
- Backend package before promotion handoff: `cd /Users/anishpatel/Documents/SoftwareProject/production_backend && ./mvnw -q clean package`
- Frontend lint: `cd /Users/anishpatel/Documents/SoftwareProject/frontend && npm run lint`
- Frontend build: `cd /Users/anishpatel/Documents/SoftwareProject/frontend && npm run build`
- Frontend UI changes: run the dev server and visually verify the changed screen when practical.

If verification cannot be run, say exactly why. Do not report completion as if it passed.

## Live Backend Diagnostic Report

Run this workflow when the user asks for a `live_backend diagnostic report`, `live diagnostic`, `why no trades`, or equivalent.

Purpose: determine whether no trades or unexpected live behavior came from valid strategy selectivity, missing data, stale feeds, config mismatch, risk rejection, broker/reconciliation state, DTM/order-flow gates, or a logic defect.

Rules:

1. Inspect `live_backend` read-only.
2. Do not modify the live DB, live source, live jar, live logs, runtime files, or live process.
3. Do not restart, promote, or stop live services unless the user explicitly asks.
4. Prefer read-only API calls, read-only SQLite queries, source inspection, and log inspection.
5. Do not create user-facing "no activity" live logs. Diagnostics explain non-actions retrospectively.

Required checks:

- Runtime health: live backend reachability, process state if safely observable, active session status, selected account, selected Strategy Config, selected Risk Config, symbols, DTM state, entry optimizer state, and feed age.
- Market data flow: recent candle availability per symbol/timeframe, expected warmup depth, realtime freshness, contract mapping, missing or flat candles, prior-session context source, and malformed poll snapshots.
- Strategy generation: active preset loaded from live DB, enabled strategies per symbol, required symbol settings present, candidate generation path, near-miss diagnostics, and source-event strategies.
- Risk and sizing: selected Risk Config values, account balance source, max risk/trade, contract caps, aggregate exposure, trailing drawdown, daily loss, duplicate checks, correlated-family exposure checks, and rejected sizing plans.
- Broker path: Topstep/ProjectX connection state, open orders, open positions, broker reconciliation status, ledger/trade-cache agreement, pending-submitted states, stale order locks, and submit/manage errors.
- DTM and entry optimizer: whether either feature blocked, modified, closed, protected, or skipped a trade; whether missing Level 2 correctly fell back or incorrectly blocked trading.
- Backtest-qualified opportunity check: if the user points to a successful backtest trade, determine whether live generated the same candidate, attempted the order, used an executable order type, received a fill, missed as a resting order, was canceled, or was blocked. Treat a missed live fill of a backtest winner as an execution/fillability issue before recommending stricter strategy filtering.
- Frontend/backend contract: confirm the live UI-visible config matches the backend session/config values and no stale frontend payload sent the wrong config.
- Evidence: include exact files, endpoints, read-only SQL summaries, log timestamps, counts, and sample rows used.

Report format:

- What changed since the last known good state, if anything.
- What went right.
- What went wrong or remains uncertain.
- Whether no trades were caused by valid strategy selectivity or by a defect.
- For backtest-vs-live complaints, whether live failed to attempt/fill a backtest-qualified opportunity and what execution layer caused the miss.
- Evidence supporting the conclusion.
- Risk impact for live trading.
- Proposed implementation plan for any real defect, scoped to dev first.

Do not be a yes-man. It is acceptable and valuable to conclude that no trade should have fired when the evidence supports that.

## Market Day Report

Run this workflow when the user asks for a `market day report`, `daily trade review`, `review today's trades`, or equivalent.

Purpose: reconstruct the trading day from live trades, candles, market data, configs, live decisions, broker events, and DTM/order-flow actions; then judge whether the bot behaved correctly and whether the market outcome was normal variance or a fixable defect.

Rules:

1. Inspect live data read-only unless the user explicitly asks for a dev reproduction.
2. Reconstruct decisions from durable data first: live trades, signal decisions, logs, broker fills, order events, DTM decisions, candles, and market data.
3. Re-run or simulate only in dev when needed to prove a hypothesis.
4. Distinguish unlucky valid losses from trade-handling defects.
5. Do not propose code changes unless a genuine logic, contract, data, or execution issue is supported by evidence.

Required analysis:

- Check all of the trades commited during the day, and analayze weather the strategy engine actually follwoed correct marketstructure or was it a flase signal and from there determine weather it was out logic's fault and it was a false positive. or the market structure supported our trade and this was just unlucky and no change needs to be made to the strategy logic, our logic is following the strategy rules accurately.
- Trade inventory: all entries, exits, symbols, strategies, sizes, fills, planned entry/stop/target, actual fill, commissions/slippage, PnL, MFE, MAE, and exit reason.
- Market structure: whether each entry followed the documented structure for its strategy, including trend, VWAP/EMA context, prior-session levels, volume, breakout/retest quality, risk distance, and timing.
- Entry quality: whether the entry was executable, stale, late, front-run, overextended, or correctly next-bar aligned.
- Exit quality: whether target, stop, time exit, broker exit, manual exit, DTM action, or early thesis cut matched the plan.
- DTM contribution: estimate how DTM increased profit, protected profit, reduced loss, missed an opportunity, or caused harm.
- Risk contribution: determine whether sizing, exposure, correlated-family guards, daily loss, trailing drawdown, and per-strategy limits behaved as intended.
- Missed-winner analysis: for any backtest winner absent or unfilled in live, reconstruct the exact live path: candidate generated or not, order attempted or not, order type, limit/marketability, broker response, fillVolume, open-order duration, cancel reason, and whether live should be changed to execute the backtest-qualified setup more reliably.
- Strategy-level pattern: determine whether issues are isolated to one trade, one symbol, one strategy, one module, or broad runtime behavior.
- Projected effect: for any supported fix, estimate expected direction and rough magnitude of impact on returns, trade count, drawdown, and win rate. State uncertainty clearly.

Report format:

- Executive conclusion.
- What went right.
- What went wrong or remains uncertain.
- Trade-by-trade findings.
- Strategy/module findings.
- DTM and risk findings.
- Evidence supporting the decision.
- Implementation plan for confirmed issues, dev first.
- RAG filing tags and recommended NextSprint items, if any.

## Live Update And Promotion Rules

Use this only when the user explicitly asks to update live/backend runtime or production frontend.

Backend live update:

- Build and verify in `/Users/anishpatel/Documents/SoftwareProject/production_backend`.
- Do not hand-copy edited files into `live_backend`.
- Use only the approved live backend update endpoint or existing approved promotion mechanism in the live backend.
- Preserve live DB, market data, logs, credentials, and runtime artifacts.
- After update, verify health, version/build identity if available, live monitor readiness, and critical endpoints.

Frontend production update:

- Frontend production is updated through Git only.
- Do not put backend, DB files, secrets, release bundles, or live runtime files into Git, they should be automatically remvoed by the .gitignore.
- Verify `npm run build` before any requested frontend push/deploy handoff.

## Project Memory And RAG Organization

Memory must become more useful after every completed chat without requiring the user to say `update the RAG`.

## Automatic RAG Closeout

Before the final response for any completed task, perform automatic memory write-back when the chat produced useful durable project knowledge.

1. Build the final action summary: what changed, what was inspected, what was verified, what remains uncertain, and any live/backtest/risk/account impact.
2. Export/query current-chat scratch if it exists:
   `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py export`
3. Compare the exported scratch with the visible chat. Use both sources because scratch may lag the latest turns.
4. Extract only durable memory cards. Durable memory includes:
   - user decisions;
   - completed code or config changes;
   - diagnostic conclusions;
   - market-day reports;
   - live/backend promotion or deployment state changes;
   - strategy, risk, DTM, broker, market-data, or frontend/backend contract findings;
   - rejected hypotheses that future chats should not repeat;
   - subsystem explanations that will help future retrieval.
5. Append to the consolidated chat memory ledger when durable memory exists:
   `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/deposit_chat_memory.py --title "<chat title>" --summary "<curated summary>" --tag "<tag>" --fact "<subsystem.micro-topic>::<durable fact>" --link "<source path or wiki link>"`
6. Promote durable standalone decisions, diagnostics, reports, or subsystem explanations into their category folders when they should retrieve independently from the chat dump.
7. Repair vault graph links after every memory write-back and before indexing:
   `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/graph_link_repair.py --apply --ensure-section`
8. Refresh the permanent index after every memory write-back:
   `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/index_project.py`
9. Close the current-chat scratch layer automatically after successful write-back:
   `python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py closeout --reason "Automatic RAG closeout after memory write-back"`
10. Include RAG write-back status in the final response: `RAG: updated and indexed`, `RAG: no durable memory to write`, or `RAG: write-back/index failed: <reason>`.

Do not create permanent memory for routine reads, broad searches, ordinary command output, temporary observations, or tiny edits that have no future retrieval value. If a chat produced no durable facts, report `RAG: no durable memory to write` and do not create a noisy dump.

Organize memory by functionality so retrieval can diagnose specific failures:

- `ProjectBrain/Vault/ChatDumps/Chat Memory Ledger.md` for the append-only chat trace ledger. Legacy per-chat dumps stay preserved under `ProjectBrain/Vault/ChatDumps/` but should not be treated as implementation proof without direct source verification.
- `ProjectBrain/Vault/Reports/Diagnostics/` for live backend diagnostic reports.
- `ProjectBrain/Vault/Reports/MarketDays/` for market-day reports.
- `ProjectBrain/Vault/Decisions/` for accepted decisions and rejected hypotheses.
- `ProjectBrain/Vault/Concepts/` for durable explanations of app subsystems.
- `ProjectBrain/Vault/Sprints/NextSprint.md` for pending implementation work only.
- `ProjectBrain/RAG/exports/` for generated retrieval/report exports.
- `ProjectBrain/RAG/CurrentChat/` for isolated current-chat scratch memory that stays disconnected from permanent RAG until automatic closeout promotes useful memory.

Suggested subsystem tags for notes and RAG metadata:

- `strategy-presets`
- `risk-config`
- `live-start`
- `candidate-generation`
- `strategy-diagnostics`
- `order-flow`
- `dtm`
- `broker-reconcile`
- `ledger-trade-cache`
- `frontend-backend-contract`
- `market-data`
- `backtest`
- `deployment`
- `live-backend-diagnostic`
- `market-day-report`
- `rag-architecture`
- `retrieval-indexing`

For chat dumps, prefer micro-topic facts when a broad subsystem has important small surfaces: `subsystem.micro-topic::fact`. Examples: `dtm.order-flow`, `live-start.api-route`, `frontend-backend-contract.trade-cache`, `risk-config.trailing-drawdown`, `market-data.realtime-depth`, and `rag.retrieval-indexing`.

RAG corpus should include:

- `AGENTS.md`
- `ProjectBrain/Vault/**/*.md`
- `ProjectBrain/RAG/Brain Memory Manual.md`
- `ProjectBrain/RAG/memory.yml`
- `ProjectBrain/RAG/scripts/*.py`
- `README.md`
- `production_backend/src/*.java`
- `production_backend/tests/*.java`
- `production_backend/resources/**/*`
- `frontend/src/**/*.{js,jsx,ts,tsx,css}`
- `frontend/functions/**/*.js`
- `scripts/**/*.sh`
- `research/**/*.md`
- selected research docs when directly relevant

RAG corpus must exclude:

- `.env*`
- `node_modules`
- `target`
- `dist`
- logs, backups, DB files, DB WAL/SHM files
- broker credentials, account secrets, Cloudflare tokens, API keys
- `live_backend/**` except generated read-only diagnostic summaries that intentionally omit secrets
- `ProjectBrain/RAG/CurrentChat/**` from the permanent RAG corpus

After memory write-back, always run:
`python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/index_project.py`

## Final Report

Every final answer after work should include:

- What changed.
- What evidence was run or inspected.
- Live trading, backtest, preset, risk, or account impact.
- For backtest-vs-live work, whether live attempted and filled the backtest-qualified opportunity; if not, identify the live execution, broker, risk, config, or data layer that prevented it.
- RAG write-back status.
- What still needs work, if anything or is it complete.

For strategy, risk, DTM, broker, diagnostics, market-day reports, or promotion work, use stakeholder language:

- What changed.
- What went right.
- What went wrong or remains uncertain.
- Evidence supporting the decision.
- Follow-up work.
