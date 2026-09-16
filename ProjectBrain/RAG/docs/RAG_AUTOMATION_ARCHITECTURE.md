# ProjectBrain RAG Automation Architecture

## Summary

This architecture replaces manual RAG updates with a closed-loop Codex workflow:

```text
chat startup
  -> current-chat scratch start
  -> index refresh
  -> permanent RAG retrieval
  -> optional compact context pack
  -> source verification
  -> work
  -> evidence-gated memory closeout
  -> index refresh
  -> scratch closeout
```

The system is designed to prevent the previous failure mode where noisy graph links and stale index state caused the AI to retrieve or preserve the wrong context.

## Core Guarantees

1. **Freshness first**: startup and closeout both refresh the permanent index.
2. **Current-chat isolation**: scratch notes live under `ProjectBrain/RAG/CurrentChat/` and are excluded from `memory.yml`.
3. **Evidence-gated memory**: permanent chat facts require source/evidence links unless explicitly marked as source-free user decisions/preferences.
4. **Direct evidence beats graph links**: graph/reference expansion only follows chunks that already match the query directly.
5. **Reference-only cap**: reference-expanded chunks get low weight and are capped in final context packs.
6. **Compact context**: default top-k is small; broad context packs should stay around 16 chunks.
7. **Source verification**: retrieved chunks are pointers, not proof.
8. **Closeout title guard**: automatic closeout refuses to archive a current-chat scratch session whose active title does not match the closeout title, reducing cross-thread collisions.

## Startup Command

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py startup \
  --title "<short chat title>" \
  --task "<one sentence task summary>"
```

## Closeout Commands

With durable memory:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout \
  --title "<short chat title>" \
  --summary "<curated durable outcome>" \
  --fact "<subsystem.micro-topic>::<evidence-backed fact>" \
  --link "<source/evidence>"
```

Without durable memory:

```bash
python3 /Users/anishpatel/Documents/SoftwareProject/ProjectBrain/RAG/scripts/chat_rag_cycle.py closeout \
  --title "<short chat title>" \
  --no-memory
```

## Retrieval Pipeline

```text
query
  -> query expansion
  -> lexical FTS candidates
  -> sparse vector candidates
  -> optional hash embedding candidates
  -> symbol/code candidates
  -> metadata/exact/freshness scoring
  -> direct-evidence gate
  -> reference/adjacent/companion expansion
  -> reference-only cap
  -> per-file diversification
  -> final context pack
```

## Ingestion Pipeline

```text
included files from memory.yml
  -> exclude secrets/build/runtime/current-chat paths
  -> read text safely
  -> file-type-aware chunking
  -> metadata extraction
  -> contextual headers
  -> sparse terms / optional embeddings
  -> SQLite chunks + FTS rows
  -> file sha/mtime table
```

## Memory Closeout Pipeline

```text
visible chat + current-chat scratch
  -> Codex curates durable summary/facts
  -> deposit_chat_memory.py validates evidence and secret safety
  -> writes one ChatDumps note
  -> index_project.py refreshes permanent index
  -> current_chat_memory.py closeout moves scratch to .md.archived
```

## Failure Handling

- Stale index warning: run `index_project.py`, rerun the query, then inspect files.
- Weak retrieval: narrow query by subsystem, source path, strategy code, endpoint, or symbol.
- Conflicting sources: current code/runtime/user decision wins; deposit a rejected hypothesis only if useful.
- Missing eval coverage: add a case to `ProjectBrain/RAG/evals/rag_retrieval_cases.json` before changing ranking.
