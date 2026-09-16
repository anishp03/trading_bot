# Brain Memory Manual

This is the operating note for the local ProjectBrain RAG system: the scripts that index the project/vault corpus, retrieve relevant context for every Codex chat, maintain current-chat scratch memory, and write curated durable memory back at chat closeout.

The system is local-only. It uses no API keys, no network calls, and no third-party Python packages.

## Design Goal

The RAG must prevent hallucinations, not store them.

The permanent RAG corpus should contain only source files, curated project notes, diagnostics, decisions, reports, and evidence-backed chat memory cards. Current-chat scratch can hold in-progress context during one chat, but it is intentionally excluded from the permanent index.

## Scripts

- `scripts/chat_rag_cycle.py`: canonical one-command startup and closeout wrapper.
- `scripts/index_project.py`: build or refresh the local retrieval index.
- `scripts/query_project.py`: retrieve relevant project context for a task.
- `scripts/rag_lib.py`: shared indexing, chunking, metadata, config, and search helpers.
- `scripts/current_chat_memory.py`: maintain the active chat as an isolated, unpromoted scratch component outside the permanent RAG index.
- `scripts/deposit_chat_memory.py`: append evidence-gated, human-curated chat memory to the consolidated chat ledger by default; `--per-chat-file` preserves the legacy one-file-per-chat mode.
- `scripts/eval_rag.py`: run lightweight retrieval evals before changing chunking, metadata, weighting, embeddings, or reranking.
- `scripts/rag_health.py`: check index freshness, CurrentChat exclusion, empty index state, and optional eval status.
- `scripts/inventory_rag_corpus.py`: read-only corpus inventory by artifact type and largest indexed files.
- `scripts/build_brain_dump.py`: regenerate the structured Obsidian/RAG vault from handoff docs, sprint notes, local `production_backend` source, and read-only live backend source maps.

## Storage Model

Permanent corpus:

- `AGENTS.md`
- `ProjectBrain/Vault/**/*.md`
- `ProjectBrain/RAG/Brain Memory Manual.md`
- `ProjectBrain/RAG/memory.yml`
- `ProjectBrain/RAG/scripts/*.py`
- approved source and config files from root-level `production_backend/`, `frontend/`, `scripts/`, and `tools/`

Excluded from permanent corpus:

- `.env*`, credentials, secrets, databases, logs, backups, build output, `node_modules`, `target`, `dist`, and root `dev_runtime/**`
- `live_backend/**`
- `ProjectBrain/RAG/storage/**`
- `ProjectBrain/RAG/cache/**`
- `ProjectBrain/RAG/exports/**`
- `ProjectBrain/RAG/CurrentChat/**`

`CurrentChat` is scratch memory only. It must stay disconnected from the permanent graph/index.

Chat memory closeouts use `ProjectBrain/Vault/ChatDumps/Chat Memory Ledger.md` by default. Older per-chat dump files remain preserved for audit/history, but they are treated as lower-authority chat traces during retrieval and should not be used as implementation proof without source verification.

## Startup Retrieval Loop

Run at the start of every Codex chat:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py startup \
  --title "<short chat title>" \
  --task "<one sentence task summary>"
```

The wrapper performs:

1. Start a fresh current-chat scratch session.
2. Refresh the permanent index incrementally.
3. Query permanent RAG with a compact top-k result set.
4. Run a compact context-pack query when the task is broad, diagnostic, cross-subsystem, strategy/risk/DTM, frontend/backend contract, or RAG-related.
5. Print source paths, line ranges, excerpts, direct-evidence scores, and stale-index warnings when applicable.

After startup retrieval, Codex must read cited source files directly before editing or relying on conclusions.

## Closeout Ingestion Loop

Run before every final response.

### Durable memory created

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout \
  --title "<short chat title>" \
  --summary "<curated summary of durable outcome>" \
  --tag "<tag>" \
  --fact "<subsystem.micro-topic>::<evidence-backed durable fact>" \
  --link "<source file path, wiki link, endpoint, eval, or command proving the fact>"
```

Multiple `--fact`, `--tag`, and `--link` arguments are allowed.

Facts require at least one evidence/source link unless the fact is explicitly a source-free user preference or user decision. Use `--allow-unsourced` only for that narrow case.

### No durable memory created

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout \
  --title "<short chat title>" \
  --no-memory
```

This still refreshes the index and closes current-chat scratch.

## Anti-Hallucination Rules

- Retrieval output is a pointer, not proof.
- Current code, current runtime data, explicit user decisions, and curated project memory outrank stale retrieved chunks.
- Do not deposit guesses, unresolved interpretations, raw transcripts, or noisy logs.
- Do not deposit facts without source links unless they are explicit source-free user preferences or decisions.
- Rejected hypotheses may be deposited only if explicitly labeled rejected and useful for future diagnostics.
- If `query_project.py` warns the index is stale, refresh the index before trusting the result.
- Graph/reference-expanded chunks are hints. They must not dominate direct query evidence.
- Keep retrieved context compact. Default final top-k is intentionally small; context packs should normally stay around 16 chunks.

## Retrieval Architecture

The system uses hybrid local retrieval:

1. SQLite FTS5/BM25 lexical search for exact symbols, paths, strategy codes, config fields, endpoints, and timestamps.
2. Sparse token-vector reranking for dependency-free semantic-ish recall.
3. Optional local hash embeddings, disabled by default.
4. Symbol-aware code retrieval for Java/JS/JSX/TS/Python functions, classes, constants, and API paths.
5. Metadata-aware ranking using artifact type, title, headings, tags, strategy code, module name, source modified time, status, source-of-truth flag, and supersession metadata.
6. Task-mode routing for sprint planning, RAG maintenance, frontend/backend contracts, DTM/risk/broker diagnostics, API contracts, and source audits.
7. Authority labels in query output: `source-truth`, `curated-memory`, `research`, `chat-trace`, `graph-hint`, or `project-context`.
8. Gated graph/reference expansion. Reference-only chunks get low weight and are capped in final context packs.
9. Diversification so one dense note or one source file does not crowd out the rest of the context.

## Retrieval Lanes

The RAG index is one local SQLite corpus, but ranking treats artifacts as lanes:

- Source truth: `AGENTS.md`, sprint plan, backend/frontend source, tests, functions.
- Curated memory: runtime notes, module notes, source maps, concepts, decisions, diagnostics, market-day reports, RAG design notes.
- Research: active and archived research notes.
- Chat trace: the consolidated chat ledger and legacy per-chat dumps.
- Graph hint: reference-expanded chunks that were not directly retrieved by the query.

Graph hints and chat traces are useful for orientation. They cannot replace direct source, direct runtime evidence, or promoted curated notes for implementation decisions.

The retrieval design follows a selective-RAG direction: retrieve source and promoted memory only when task mode needs them, keep chat history compact, and use graph/reference expansion as a controlled follow-up layer instead of an open-ended traversal.

## Indexing Architecture

`index_project.py` reads `memory.yml`, computes included files, removes stale paths from SQLite, chunks files, writes contextual chunk headers, writes FTS rows, stores sparse vectors, and updates file hashes/mtimes.

Chunking defaults:

- Markdown: approximately 500 tokens, 750 max, 80 overlap.
- Code: approximately 700 tokens, 1000 max, 120 overlap.
- General files: approximately 550 tokens, 850 max, 90 overlap.

Markdown chunks split on headings when useful. Code chunks are slightly larger to avoid cutting method/class bodies too aggressively.

## Health and Evals

Run health check:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/rag_health.py
```

Run health check plus evals:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/rag_health.py --eval
```

Run evals directly:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/eval_rag.py
```

Rebuild from scratch after architecture, chunking, ranking, corpus, or schema changes:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/index_project.py --reset --stats
```

## Manual Direct Commands

The wrapper is canonical, but direct commands remain useful for debugging.

Query permanent RAG:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/query_project.py "<task summary>" --top-k 8
```

Retrieve broad context:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/query_project.py "<subsystem or edit area>" --context-pack --top-k 16 --include-context
```

Append current-chat scratch:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/current_chat_memory.py append \
  --role note \
  --topic "<subsystem.micro-topic>" \
  --text "<concise note>"
```

Append evidence-gated chat memory directly:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/deposit_chat_memory.py \
  --title "<chat title>" \
  --summary "<curated summary>" \
  --tag rag \
  --fact "rag.retrieval::Direct query evidence must outrank graph/reference-only chunks." \
  --link "ProjectBrain/RAG/scripts/query_project.py"
```

Use the legacy per-chat file layout only when there is a specific audit reason:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/deposit_chat_memory.py \
  --per-chat-file \
  --title "<chat title>" \
  --summary "<curated summary>" \
  --fact "rag.audit::Per-chat file requested for isolated audit trace." \
  --link "ProjectBrain/RAG/scripts/deposit_chat_memory.py"
```

Inventory the indexed corpus:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/inventory_rag_corpus.py
```

## Rebuild / Migration Checklist

1. Install updated files.
2. Run `python3 -m py_compile ProjectBrain/RAG/scripts/*.py`.
3. Run `python3 ProjectBrain/RAG/scripts/index_project.py --reset --stats`.
4. Run `python3 ProjectBrain/RAG/scripts/rag_health.py --eval`.
5. Open a new Codex chat and confirm startup calls `chat_rag_cycle.py startup`.
6. Complete a small task and confirm closeout calls either evidence-backed memory write-back or `--no-memory`.
