#!/usr/bin/env python3
"""Query the local project RAG index."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
import re
from typing import Dict, List, Set

from rag_lib import (
    config_root,
    connect_db,
    cosine,
    dense_cosine,
    dense_embedding,
    db_path,
    embedding_dimensions,
    embeddings_enabled,
    excerpt,
    expanded_query,
    fts_query,
    index_stats,
    iter_included_files,
    load_config,
    read_json_dict,
    posix_rel,
    sparse_terms,
    tokenize,
)


RECENCY_TERMS = {
    "current", "latest", "recent", "new", "newest", "fresh", "today",
    "yesterday", "updated", "update", "now", "currently", "changed",
}
REFERENCE_WEIGHT = 0.14
REFERENCE_ONLY_SCORE_CAP = 0.48
MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION = 0.18
PROJECT_ROOT_PREFIX = "/Users/anishpatel/Documents/SoftwareProject/"
PROJECT_PATH_ALIASES = (
    ("trading_bot/backend_api/", "production_backend/"),
    ("trading_bot/backend/", "production_backend/"),
    ("trading_bot/frontend/", "frontend/"),
    ("trading_bot/ops/", "scripts/"),
    ("trading_bot/scripts/", "scripts/"),
    ("trading_bot/tools/", "tools/"),
    ("trading_bot/dev_runtime/", "dev_runtime/"),
    ("backend_api/", "production_backend/"),
    ("backend/", "production_backend/"),
    ("ops/", "scripts/"),
    ("production_backend/src/main/java/com/tradingbot/api/routes/", "production_backend/src/"),
    ("production_backend/src/main/java/com/tradingbot/", "production_backend/src/"),
    ("production_backend/src/test/java/com/tradingbot/api/routes/", "production_backend/tests/"),
    ("production_backend/src/test/java/com/tradingbot/", "production_backend/tests/"),
    ("production_backend/src/main/resources/", "production_backend/resources/"),
)
PROJECT_PATH_EXACT_ALIASES = {
    "trading_bot/backend_api": "production_backend",
    "trading_bot/backend": "production_backend",
    "trading_bot/frontend": "frontend",
    "trading_bot/ops": "scripts",
    "trading_bot/scripts": "scripts",
    "trading_bot/tools": "tools",
    "trading_bot/dev_runtime": "dev_runtime",
    "trading_bot/README.md": "README.md",
    "trading_bot/AGENTS.md": "AGENTS.md",
    "trading_bot/.gitignore": ".gitignore",
    "backend_api": "production_backend",
    "backend": "production_backend",
    "ops": "scripts",
    "production_backend/src/main/java/com/tradingbot/api/routes": "production_backend/src",
    "production_backend/src/main/java/com/tradingbot": "production_backend/src",
    "production_backend/src/test/java/com/tradingbot/api/routes": "production_backend/tests",
    "production_backend/src/test/java/com/tradingbot": "production_backend/tests",
    "production_backend/src/main/resources": "production_backend/resources",
}


SOURCE_TRUTH_TYPES = {
    "backend-source",
    "frontend-source",
    "frontend-function",
    "backend-test",
    "operating-manual",
    "sprint-plan",
}
CURATED_MEMORY_TYPES = {
    "runtime-note",
    "module-note",
    "source-map",
    "workflow-map",
    "decision",
    "concept",
    "diagnostic-report",
    "market-day-report",
    "strategy-note",
    "dtm-note",
    "rag-design",
    "architecture-current",
    "architecture-contract",
    "sprint-report",
}
CHAT_TRACE_TYPES = {"chat-dump", "chat-ledger"}


def classify_task(query: str) -> Set[str]:
    terms = set(tokenize(query))
    lowered = query.lower()
    modes: Set[str] = set()
    if "nextsprint" in lowered or {"sprint", "queue"} & terms or "active queue" in lowered:
        modes.add("sprint-routing")
    if terms & {"rag", "retrieval", "index", "indexing", "memory", "chunk", "chunks", "ledger"}:
        modes.add("rag-maintenance")
    if ({"frontend", "backend"} <= terms) or terms & {"contract", "payload", "strategypreset", "riskconfig"}:
        modes.add("frontend-backend-contract")
    if terms & {"dtm", "broker", "risk", "gate", "bottleneck", "fake", "logs", "loop", "orderflow", "execution"}:
        modes.add("risk-dtm-broker")
    if terms & {"source", "code", "line", "review", "architecture", "control", "plane"}:
        modes.add("source-audit")
    if "/api/" in lowered or terms & {"endpoint", "route"}:
        modes.add("api-contract")
    return modes


def authority_label(item: Dict) -> str:
    artifact_type = item.get("artifact_type") or ""
    try:
        direct_evidence = float(item.get("direct_evidence", 0.0) or 0.0)
    except (TypeError, ValueError):
        direct_evidence = 0.0
    if item.get("reference", 0.0) > 0 and direct_evidence < MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION:
        return "graph-hint"
    if artifact_type in SOURCE_TRUTH_TYPES:
        return "source-truth"
    if artifact_type in CURATED_MEMORY_TYPES:
        return "curated-memory"
    if artifact_type.startswith("research-"):
        return "research"
    if artifact_type in CHAT_TRACE_TYPES:
        return "chat-trace"
    return "project-context"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Retrieve project context from the local RAG index.")
    parser.add_argument("query", nargs="+", help="Search query.")
    parser.add_argument("--config", default=str(Path(__file__).resolve().parents[1] / "memory.yml"))
    parser.add_argument("--top-k", type=int, default=None, help="Number of final results.")
    parser.add_argument(
        "--context-pack",
        action="store_true",
        help="Use broader retrieval and diversify results across related files.",
    )
    parser.add_argument(
        "--include-context",
        action="store_true",
        help="Include contextual chunk headers in text output.",
    )
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of text.")
    parser.add_argument(
        "--no-stale-warning",
        action="store_true",
        help="Do not warn when included files are newer than the index.",
    )
    parser.add_argument(
        "--fail-on-stale",
        action="store_true",
        help="Exit with code 3 instead of returning results when the index is stale.",
    )
    return parser.parse_args()


def lexical_candidates(conn, query: str, limit: int) -> Dict[int, Dict]:
    match = fts_query(query)
    if not match:
        return {}
    try:
        rows = conn.execute(
            """
            SELECT rowid, path, text, tags, bm25(chunks_fts) AS rank
            FROM chunks_fts
            WHERE chunks_fts MATCH ?
            ORDER BY rank
            LIMIT ?
            """,
            (match, limit),
        ).fetchall()
    except Exception:
        rows = []

    rank_values = [float(row["rank"]) for row in rows]
    relevance_values = [(-rank if rank < 0 else 1.0 / (1.0 + rank)) for rank in rank_values]
    min_relevance = min(relevance_values) if relevance_values else 0.0
    max_relevance = max(relevance_values) if relevance_values else 0.0

    candidates: Dict[int, Dict] = {}
    for row, relevance in zip(rows, relevance_values):
        rank = float(row["rank"])
        if max_relevance > min_relevance:
            lexical = 0.20 + 0.80 * ((relevance - min_relevance) / (max_relevance - min_relevance))
        elif relevance > 0:
            lexical = 0.60
        else:
            lexical = 0.0
        candidates[int(row["rowid"])] = {
            "id": int(row["rowid"]),
            "path": row["path"],
            "text": row["text"],
            "tags": row["tags"],
            "lexical": float(lexical),
            "bm25": rank,
            "vector": 0.0,
        }
    return candidates


def vector_candidates(conn, query: str, limit: int) -> Dict[int, Dict]:
    query_terms = sparse_terms(query)
    if not query_terms:
        return {}

    rows = conn.execute(
        """
        SELECT id, path, line_start, line_end, file_type, text, search_text,
               terms_json, tags, metadata_json, artifact_type
        FROM chunks
        """
    ).fetchall()
    scored = []
    query_path_terms = set(tokenize(query))
    for row in rows:
        doc_terms = read_json_dict(row["terms_json"])
        score = cosine(query_terms, doc_terms)
        if query_path_terms:
            path_terms = set(tokenize(row["path"] + " " + row["tags"] + " " + row["artifact_type"]))
            score += 0.015 * len(query_path_terms & path_terms)
        if score > 0:
            scored.append((score, row))
    scored.sort(key=lambda item: item[0], reverse=True)

    candidates: Dict[int, Dict] = {}
    for score, row in scored[:limit]:
        candidates[int(row["id"])] = {
            "id": int(row["id"]),
            "path": row["path"],
            "text": row["text"],
            "tags": row["tags"],
            "metadata_json": row["metadata_json"],
            "artifact_type": row["artifact_type"],
            "lexical": 0.0,
            "bm25": None,
            "vector": float(score),
            "embedding": 0.0,
        }
    return candidates


def read_json_vector(value: str) -> List[float]:
    if not value:
        return []
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return []
    if not isinstance(parsed, list):
        return []
    return [float(item) for item in parsed]


def embedding_candidates(conn, config, query: str, limit: int) -> Dict[int, Dict]:
    if not embeddings_enabled(config):
        return {}
    query_vector = dense_embedding(query, embedding_dimensions(config))
    rows = conn.execute(
        """
        SELECT id, path, line_start, line_end, file_type, text, embedding_json,
               tags, metadata_json, artifact_type
        FROM chunks
        WHERE embedding_json != ''
        """
    ).fetchall()
    scored = []
    for row in rows:
        score = dense_cosine(query_vector, read_json_vector(row["embedding_json"]))
        if score > 0:
            scored.append((score, row))
    scored.sort(key=lambda item: item[0], reverse=True)
    candidates: Dict[int, Dict] = {}
    for score, row in scored[:limit]:
        candidates[int(row["id"])] = {
            "id": int(row["id"]),
            "path": row["path"],
            "text": row["text"],
            "tags": row["tags"],
            "metadata_json": row["metadata_json"],
            "artifact_type": row["artifact_type"],
            "lexical": 0.0,
            "bm25": None,
            "vector": 0.0,
            "embedding": float(score),
        }
    return candidates


def is_code_shaped_query(query: str) -> bool:
    if re.search(r"/api/[A-Za-z0-9_./{}?=&:-]+", query):
        return True
    if re.search(r"\b[A-Za-z_$][A-Za-z0-9_$]*\s*\(", query):
        return True
    if re.search(r"\b(?:[a-z]+[A-Z][A-Za-z0-9_$]*|[A-Z][a-z]+[A-Z][A-Za-z0-9_$]*)\b", query):
        return True
    if re.search(r"\b[A-Za-z0-9_]+\.(?:java|jsx|js|ts|tsx|py|css|md)\b", query):
        return True
    if set(tokenize(query)) & {"method", "function", "class", "endpoint", "route", "api", "component", "hook"}:
        return True
    return False


def symbol_candidates(conn, query: str, limit: int, gate_query: str | None = None) -> Dict[int, Dict]:
    if not is_code_shaped_query(gate_query or query):
        return {}
    query_symbols = {
        value.lower()
        for value in re.findall(r"\b[A-Za-z_$][A-Za-z0-9_$]{2,}\b", query)
        if value.lower() not in {"the", "and", "with", "from", "into", "this"}
    }
    query_api_paths = set(value.lower() for value in re.findall(r"/api/[A-Za-z0-9_./{}?=&:-]+", query))
    if not query_symbols and not query_api_paths:
        return {}
    rows = conn.execute(
        """
        SELECT id, path, text, tags, metadata_json, artifact_type
        FROM chunks
        WHERE artifact_type IN ('backend-source', 'frontend-source', 'frontend-function', 'backend-test', 'rag-script')
        """
    ).fetchall()
    scored = []
    for row in rows:
        try:
            metadata = json.loads(row["metadata_json"] or "{}")
        except json.JSONDecodeError:
            metadata = {}
        code_symbols = {str(value).lower() for value in metadata.get("code_symbols", [])}
        defined_symbols = {str(value).lower() for value in metadata.get("defined_symbols", [])}
        api_paths = {str(value).lower() for value in metadata.get("api_paths", [])}
        text_lower = str(row["text"] or "").lower()
        path_lower = str(row["path"] or "").lower()
        symbol_hits = len(query_symbols & code_symbols)
        defined_hits = len(query_symbols & defined_symbols)
        api_hits = len(query_api_paths & api_paths)
        text_hits = sum(
            1
            for symbol in query_symbols
            if re.search(rf"(?<![a-z0-9_$]){re.escape(symbol)}(?![a-z0-9_$])", text_lower)
        )
        module_hits = sum(1 for symbol in query_symbols if symbol in path_lower)
        if not symbol_hits and not defined_hits and not api_hits and not text_hits and not module_hits:
            continue
        score = min(
            1.0,
            0.20 * symbol_hits
            + 0.38 * defined_hits
            + 0.45 * api_hits
            + 0.22 * text_hits
            + 0.08 * module_hits,
        )
        if row["artifact_type"] == "rag-script" and not (
            {"rag", "memory", "retrieval", "index", "script", "dump", "eval"} & set(tokenize(query))
        ):
            score *= 0.35
        scored.append((score, row))
    scored.sort(key=lambda item: item[0], reverse=True)
    candidates: Dict[int, Dict] = {}
    for score, row in scored[:limit]:
        candidates[int(row["id"])] = {
            "id": int(row["id"]),
            "path": row["path"],
            "text": row["text"],
            "tags": row["tags"],
            "metadata_json": row["metadata_json"],
            "artifact_type": row["artifact_type"],
            "lexical": 0.0,
            "bm25": None,
            "vector": 0.0,
            "embedding": 0.0,
            "symbol": float(score),
        }
    return candidates


def freshness_bonus(item: Dict, query: str) -> float:
    """Small boost for fresh source material only when the query asks for recency."""
    terms = set(tokenize(query))
    if not terms & RECENCY_TERMS:
        return 0.0
    try:
        metadata = json.loads(item.get("metadata_json") or "{}")
    except json.JSONDecodeError:
        metadata = {}
    try:
        source_modified_ts = float(metadata.get("source_modified_ts") or 0.0)
    except (TypeError, ValueError):
        source_modified_ts = 0.0
    if source_modified_ts <= 0:
        return 0.0
    age_days = max(0.0, (time.time() - source_modified_ts) / 86400.0)
    if age_days <= 2:
        return 0.16
    if age_days <= 14:
        return 0.12
    if age_days <= 45:
        return 0.08
    if age_days <= 120:
        return 0.04
    return 0.0


def metadata_bonus(item: Dict, query: str, task_modes: Set[str] | None = None) -> float:
    terms = set(tokenize(query))
    task_modes = task_modes or set()
    artifact_type = item.get("artifact_type") or ""
    code_query = is_code_shaped_query(query)
    try:
        metadata = json.loads(item.get("metadata_json") or "{}")
    except json.JSONDecodeError:
        metadata = {}
    title = str(metadata.get("title", ""))
    headings = " ".join(str(value) for value in metadata.get("headings", []))
    metadata_text = " ".join(
        [
            artifact_type,
            title,
            headings,
            str(metadata.get("strategy_code", "")),
            str(metadata.get("module_name", "")),
            " ".join(str(value) for value in metadata.get("code_symbols", [])),
            " ".join(str(value) for value in metadata.get("defined_symbols", [])),
            " ".join(str(value) for value in metadata.get("api_paths", [])),
            item.get("tags", ""),
            item.get("path", ""),
        ]
    )
    metadata_terms = set(tokenize(metadata_text))
    status = str(metadata.get("status", "")).lower()
    promotion = str(metadata.get("promotion", "")).lower()
    source_of_truth = str(metadata.get("source_of_truth", "")).lower()
    superseded_by = str(metadata.get("superseded_by", "")).strip()
    bonus = 0.0
    if status in {"deprecated", "archived", "superseded", "inactive"} or "deprecated" in status:
        bonus -= 0.55
    if promotion in {"raw", "unpromoted", "draft"}:
        bonus -= 0.18
    if superseded_by:
        bonus -= 0.35
    if source_of_truth in {"true", "yes", "1"}:
        bonus += 0.08
    if terms:
        bonus += min(0.16, 0.16 * len(terms & metadata_terms) / len(terms))
    broad_terms = {
        "dtm", "risk", "strategy", "live", "frontend", "backend", "broker",
        "market", "rag", "diagnostic", "report", "workflow", "contract",
    }
    if terms & broad_terms and artifact_type in {
        "workflow-map", "runtime-note", "concept", "decision", "source-map",
        "module-note", "dtm-note", "diagnostic-report", "market-day-report",
        "strategy-note", "operating-manual",
    }:
        bonus += 0.08
    if terms & {"chat", "dump", "offload", "memory", "deposit", "ledger", "closeout"} and artifact_type in {
        "chat-dump", "chat-ledger", "decision", "workflow-map", "operating-manual", "project-file", "rag-script",
    }:
        bonus += 0.35
    if terms & {"diagnostic", "diagnostics"} and artifact_type == "diagnostic-report":
        bonus += 0.38
    if "Live Backend Diagnostic Playbook" in title and terms & {"live", "diagnostic", "diagnostics", "trades"}:
        bonus += 0.18
    if terms & {"dtm"} and artifact_type in {"dtm-note", "concept", "runtime-note"}:
        bonus += 0.10
    if "DTM Trade Management" in title and terms & {"dtm", "trade", "management"}:
        bonus += 0.32
    if terms & {"frontend", "futureslive"} and "FuturesLive" in title:
        bonus += 0.18
    if artifact_type == "frontend-source" and "FuturesLive" in title and terms & {"live", "start", "payload", "strategypreset", "riskconfig", "futureslive"}:
        bonus += 0.46
    if artifact_type == "backend-source" and "FuturesLiveRoutes" in title and terms & {"live", "start", "payload", "strategypreset", "riskconfig", "futureslive"}:
        bonus += 0.24
    if "ProjectXRealtimeManager" in title and terms & {"projectx", "realtime", "gateway", "depth"}:
        bonus += 0.28
    if "Strategy Preset Workflow" in title and terms & {"strategy", "preset", "strategypreset"}:
        bonus += 0.34
    if artifact_type == "source-map" and terms & {"source", "parity", "diff"}:
        bonus += 0.36
    if "Frontend Backend Contract" in title and terms & {"frontend", "backend", "contract", "payload"}:
        bonus += 0.32
    if "Frontend Backend Contract" in title and terms & {"cache", "trade", "live"}:
        bonus += 0.24
    if terms & {"mainserver"} and "MainServer" in title:
        bonus += 0.16
    code_symbols = {str(value).lower() for value in metadata.get("code_symbols", [])}
    defined_symbols = {str(value).lower() for value in metadata.get("defined_symbols", [])}
    query_symbols = {
        value.lower()
        for value in re.findall(r"\b[A-Za-z_$][A-Za-z0-9_$]{2,}\b", query)
        if value.lower() not in {"the", "and", "with", "from", "into", "this"}
    }
    symbol_hits = len(code_symbols & query_symbols)
    defined_hits = len(defined_symbols & query_symbols)
    if symbol_hits and artifact_type in {"backend-source", "frontend-source", "frontend-function", "backend-test"}:
        bonus += min(0.30, 0.11 * symbol_hits)
    if defined_hits and artifact_type in {"backend-source", "frontend-source", "frontend-function", "backend-test"}:
        bonus += min(0.50, 0.28 * defined_hits)
    api_paths = set(str(value).lower() for value in metadata.get("api_paths", []))
    query_api_paths = set(value.lower() for value in re.findall(r"/api/[A-Za-z0-9_./{}?=&:-]+", query))
    if api_paths & query_api_paths:
        bonus += 0.24
    if artifact_type in {"rag-script", "backend-source", "frontend-source"} and terms & {"script", "method", "function", "class", "code", "source"}:
        bonus += 0.06
    if artifact_type == "rag-script":
        rag_ops_terms = {
            "rag", "memory", "retrieval", "index", "indexing", "script", "query",
            "chunk", "chunks", "sqlite", "fts5", "build", "refresh", "eval", "health",
        }
        if terms & rag_ops_terms:
            bonus += min(0.28, 0.07 * len(terms & metadata_terms))
        path_lower = str(item.get("path", "")).lower()
        if "index_project.py" in path_lower and terms & {"build", "refresh", "index", "indexing", "sqlite", "fts5", "chunks"}:
            bonus += 0.18
        if "rag_lib.py" in path_lower and terms & {"chunk", "chunks", "sqlite", "fts5", "index", "retrieval"}:
            bonus += 0.12
    if artifact_type == "rag-script" and not (
        terms & {"rag", "memory", "retrieval", "index", "script", "dump", "eval"}
    ):
        bonus -= 0.30
    if artifact_type in CHAT_TRACE_TYPES and not (terms & {"chat", "dump", "ledger", "closeout", "deposit", "history"}):
        bonus -= 0.42
    if artifact_type == "chat-ledger" and terms & {"chat", "ledger", "closeout", "memory"}:
        bonus += 0.42
    if "sprint-routing" in task_modes:
        if artifact_type in {"sprint-plan", "sprint-report"}:
            bonus += 0.90
        if artifact_type in {"rag-script", "chat-dump", "chat-ledger"}:
            bonus -= 0.70
    if "frontend-backend-contract" in task_modes:
        if artifact_type in {"backend-source", "frontend-source", "frontend-function", "runtime-note", "source-map", "architecture-contract"}:
            bonus += 0.42
        if artifact_type in CHAT_TRACE_TYPES:
            bonus -= 0.55
    if "risk-dtm-broker" in task_modes:
        if artifact_type in {"backend-source", "runtime-note", "dtm-note", "diagnostic-report", "market-day-report", "module-note"}:
            bonus += 0.28
        if artifact_type in CHAT_TRACE_TYPES:
            bonus -= 0.35
    if "rag-maintenance" in task_modes and "sprint-routing" not in task_modes:
        if artifact_type in {"rag-script", "rag-design", "workflow-map", "operating-manual"}:
            bonus += 0.24
    if "source-audit" in task_modes:
        if artifact_type in {"source-map", "module-note", "backend-source", "frontend-source", "project-file", "architecture-current"}:
            bonus += 0.34
        if artifact_type in CHAT_TRACE_TYPES:
            bonus -= 0.58
    if "api-contract" in task_modes and artifact_type in {"backend-source", "frontend-source", "frontend-function", "runtime-note"}:
        bonus += 0.28
    source_friendly_mode = bool(task_modes & {"frontend-backend-contract", "source-audit", "api-contract"})
    if artifact_type in {"backend-source", "frontend-source", "backend-test", "frontend-function"} and not code_query and not source_friendly_mode:
        bonus -= 0.15 if item.get("reference", 0.0) > 0 else 0.65
    elif artifact_type in {"backend-source", "frontend-source"} and not (terms & {"code", "source", "method", "function", "class", "endpoint", "payload"}):
        bonus -= 0.03
    return bonus


def exact_match_bonus(item: Dict, query: str) -> float:
    query_clean = query.strip().lower()
    haystack = f"{item.get('path', '')} {item.get('tags', '')} {item.get('text', '')}".lower()
    text = item.get("text", "").lower()
    path_tags = f"{item.get('path', '')} {item.get('tags', '')}".lower()
    try:
        metadata = json.loads(item.get("metadata_json") or "{}")
    except json.JSONDecodeError:
        metadata = {}

    bonus = 0.0
    if query_clean and query_clean in text:
        bonus += 0.18
    if query_clean and query_clean in path_tags:
        bonus += 0.10

    terms = list(dict.fromkeys(tokenize(query)))
    if terms:
        hits = sum(1 for term in terms if term in haystack)
        bonus += min(0.12, 0.12 * hits / len(terms))
        text_hits = sum(1 for term in terms if term in text)
        bonus += min(0.08, 0.08 * text_hits / len(terms))
    query_symbols = {
        value.lower()
        for value in re.findall(r"\b[A-Za-z_$][A-Za-z0-9_$]{2,}\b", query)
    }
    code_symbols = {str(value).lower() for value in metadata.get("code_symbols", [])}
    defined_symbols = {str(value).lower() for value in metadata.get("defined_symbols", [])}
    if query_symbols and code_symbols:
        bonus += min(0.18, 0.08 * len(query_symbols & code_symbols))
    if query_symbols and defined_symbols:
        bonus += min(0.30, 0.16 * len(query_symbols & defined_symbols))
    query_api_paths = set(value.lower() for value in re.findall(r"/api/[A-Za-z0-9_./{}?=&:-]+", query))
    api_paths = set(str(value).lower() for value in metadata.get("api_paths", []))
    if query_api_paths & api_paths:
        bonus += 0.16
    return bonus


def enrich_metadata(conn, candidates: Dict[int, Dict], query: str, task_modes: Set[str] | None = None) -> List[Dict]:
    if not candidates:
        return []
    ids = sorted(candidates.keys())
    placeholders = ",".join("?" for _ in ids)
    rows = conn.execute(
        f"""
        SELECT id, path, chunk_index, line_start, line_end, file_type,
               token_count, text, search_text, context_header, tags,
               metadata_json, artifact_type
        FROM chunks
        WHERE id IN ({placeholders})
        """,
        ids,
    ).fetchall()
    enriched = []
    for row in rows:
        item = candidates[int(row["id"])]
        item.update(
            {
                "chunk_index": row["chunk_index"],
                "line_start": row["line_start"],
                "line_end": row["line_end"],
                "file_type": row["file_type"],
                "token_count": row["token_count"],
                "text": row["text"],
                "search_text": row["search_text"],
                "context_header": row["context_header"],
                "tags": row["tags"],
                "metadata_json": row["metadata_json"],
                "artifact_type": row["artifact_type"],
            }
        )
        item.setdefault("embedding", 0.0)
        item.setdefault("reference", 0.0)
        item.setdefault("symbol", 0.0)
        item["metadata_bonus"] = metadata_bonus(item, query, task_modes)
        item["exact_bonus"] = exact_match_bonus(item, query)
        item["freshness_bonus"] = freshness_bonus(item, query)
        item["direct_evidence"] = (
            item["lexical"]
            + item["vector"]
            + item["embedding"]
            + item["symbol"]
            + item["exact_bonus"]
            + max(item["metadata_bonus"], 0.0)
        )
        raw_score = (
            (0.42 * item["lexical"])
            + (0.32 * item["vector"])
            + (0.12 * item["embedding"])
            + (REFERENCE_WEIGHT * item["reference"])
            + (0.34 * item["symbol"])
            + item["exact_bonus"]
            + item["metadata_bonus"]
            + item["freshness_bonus"]
        )
        if item["reference"] > 0 and item["direct_evidence"] < MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION:
            raw_score = min(raw_score, REFERENCE_ONLY_SCORE_CAP)
        item["score"] = round(raw_score, 6)
        item["authority"] = authority_label(item)
        enriched.append(item)
    enriched.sort(key=lambda item: item["score"], reverse=True)
    return enriched


def normalize_project_path(path: str) -> str:
    """Map historical workspace and nested Java paths to the current flat layout."""
    normalized = path.strip().replace("\\", "/")
    if normalized.startswith(PROJECT_ROOT_PREFIX):
        normalized = normalized[len(PROJECT_ROOT_PREFIX):]
    while normalized.startswith("./"):
        normalized = normalized[2:]
    seen: Set[str] = set()
    while normalized not in seen:
        seen.add(normalized)
        exact = PROJECT_PATH_EXACT_ALIASES.get(normalized)
        if exact is not None:
            normalized = exact
            continue
        for old_prefix, current_prefix in PROJECT_PATH_ALIASES:
            if normalized.startswith(old_prefix):
                normalized = current_prefix + normalized[len(old_prefix):]
                break
        else:
            return normalized
    return normalized


def referenced_paths_from_items(items: List[Dict], limit: int = 12) -> Set[str]:
    paths: Set[str] = set()
    pattern = re.compile(r"`([^`]+?\.(?:md|java|jsx|js|css|py|yml|sh))`")
    wiki_pattern = re.compile(r"\[\[([^|\]#]+)(?:#[^|\]]*)?(?:\|[^\]]*)?\]\]")
    vault_prefixes = {
        "ChatDumps", "Concepts", "DTM", "Decisions", "History", "Maps", "Modules",
        "Reports", "Runtime", "SourceMaps", "Sprints", "Strategies",
    }
    for item in items[:limit]:
        haystack = "\n".join(
            [
                item.get("text", ""),
                item.get("context_header", ""),
                item.get("path", ""),
            ]
        )
        for match in pattern.findall(haystack):
            rel = normalize_project_path(match)
            if rel.startswith(("ProjectBrain/", "production_backend/", "frontend/", "scripts/", "tools/")) or rel in {"AGENTS.md", "README.md"}:
                paths.add(rel)
            elif rel.endswith(".java") and "/" not in rel:
                paths.add(f"production_backend/src/{rel}")
            elif rel.endswith(".jsx") and "/" not in rel:
                paths.add(f"frontend/src/pages/{rel}")
        for match in wiki_pattern.findall(haystack):
            target = match.strip().replace("\\", "/")
            if not target:
                continue
            if not target.endswith(".md"):
                target += ".md"
            first = target.split("/", 1)[0]
            if target.startswith("ProjectBrain/"):
                paths.add(target)
            elif first in vault_prefixes:
                paths.add(f"ProjectBrain/Vault/{target}")
    return paths


def strong_direct_items(items: List[Dict], limit: int = 16) -> List[Dict]:
    """Only graph-expand chunks that earned their rank from the query itself."""
    selected = [
        item for item in items
        if float(item.get("direct_evidence", 0.0)) >= MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION
    ]
    return selected[:limit]


def reference_candidates(conn, initial_items: List[Dict], limit_per_file: int = 2, item_limit: int = 12) -> Dict[int, Dict]:
    paths = referenced_paths_from_items(initial_items, limit=item_limit)
    if not paths:
        return {}
    candidates: Dict[int, Dict] = {}
    for path in sorted(paths):
        rows = conn.execute(
            """
            SELECT id, path, text, tags, metadata_json, artifact_type
            FROM chunks
            WHERE path = ?
            ORDER BY chunk_index
            LIMIT ?
            """,
            (path, limit_per_file),
        ).fetchall()
        for row in rows:
            candidates[int(row["id"])] = {
                "id": int(row["id"]),
                "path": row["path"],
                "text": row["text"],
                "tags": row["tags"],
                "metadata_json": row["metadata_json"],
                "artifact_type": row["artifact_type"],
                "lexical": 0.0,
                "bm25": None,
                "vector": 0.0,
                "embedding": 0.0,
                "reference": 1.0,
            }
    return candidates


def companion_paths_for_source(path: str) -> Set[str]:
    """Return curated module notes that explain a retrieved source file."""
    companions: Set[str] = set()
    normalized = normalize_project_path(path)
    stem = Path(normalized).stem

    backend_prefix = "production_backend/src/"
    if normalized.startswith(backend_prefix) and stem:
        companions.add(f"ProjectBrain/Vault/Modules/{stem}.md")

    if normalized == "frontend/src/pages/FuturesLive.jsx":
        companions.add("ProjectBrain/Vault/Modules/FuturesLive Frontend.md")
    elif normalized == "frontend/src/pages/FuturesBacktest.jsx":
        companions.add("ProjectBrain/Vault/Modules/FuturesBacktest Frontend.md")
    elif normalized == "frontend/src/pages/FuturesBacktestHistory.jsx":
        companions.add("ProjectBrain/Vault/Modules/FuturesBacktestHistory Frontend.md")
    elif normalized.startswith("frontend/src/pages/") and stem:
        companions.add(f"ProjectBrain/Vault/Modules/{stem} Frontend.md")

    return companions


def companion_candidates(conn, initial_items: List[Dict], limit_per_file: int = 1) -> Dict[int, Dict]:
    paths: Set[str] = set()
    for item in initial_items[:20]:
        paths.update(companion_paths_for_source(str(item.get("path", ""))))
    if not paths:
        return {}

    candidates: Dict[int, Dict] = {}
    for path in sorted(paths):
        rows = conn.execute(
            """
            SELECT id, path, text, tags, metadata_json, artifact_type
            FROM chunks
            WHERE path = ?
            ORDER BY chunk_index
            LIMIT ?
            """,
            (path, limit_per_file),
        ).fetchall()
        for row in rows:
            candidates[int(row["id"])] = {
                "id": int(row["id"]),
                "path": row["path"],
                "text": row["text"],
                "tags": row["tags"],
                "metadata_json": row["metadata_json"],
                "artifact_type": row["artifact_type"],
                "lexical": 0.0,
                "bm25": None,
                "vector": 0.0,
                "embedding": 0.0,
                "reference": 1.0,
            }
    return candidates


def adjacent_source_candidates(conn, initial_items: List[Dict], radius: int = 1) -> Dict[int, Dict]:
    """Include neighboring source chunks so method bodies split by chunking stay usable."""
    source_types = {"backend-source", "frontend-source", "frontend-function", "backend-test", "rag-script"}
    candidates: Dict[int, Dict] = {}
    seen = set()
    for item in initial_items[:24]:
        if item.get("artifact_type") not in source_types:
            continue
        path = str(item.get("path", ""))
        try:
            chunk_index = int(item.get("chunk_index"))
        except (TypeError, ValueError):
            continue
        for offset in range(-radius, radius + 1):
            if offset == 0:
                continue
            key = (path, chunk_index + offset)
            if key in seen or key[1] < 0:
                continue
            seen.add(key)
            rows = conn.execute(
                """
                SELECT id, path, text, tags, metadata_json, artifact_type
                FROM chunks
                WHERE path = ? AND chunk_index = ?
                """,
                key,
            ).fetchall()
            for row in rows:
                candidates[int(row["id"])] = {
                    "id": int(row["id"]),
                    "path": row["path"],
                    "text": row["text"],
                    "tags": row["tags"],
                    "metadata_json": row["metadata_json"],
                    "artifact_type": row["artifact_type"],
                    "lexical": 0.0,
                    "bm25": None,
                    "vector": 0.0,
                    "embedding": 0.0,
                    "reference": 0.94,
                }
    return candidates


def targeted_contract_candidates(conn, query: str, task_modes: Set[str]) -> Dict[int, Dict]:
    """Retrieve known source counterparts for high-risk frontend/backend contracts.

    This is intentionally narrow. It prevents live-start route reviews from
    seeing only backend handlers when the actual contract also depends on the
    frontend caller that builds the request payload.
    """
    terms = set(tokenize(query))
    lowered = query.lower()
    if not (task_modes & {"frontend-backend-contract", "api-contract"}):
        return {}
    live_start_query = (
        "/api/futures/live/start" in lowered
        or "futureslive" in terms
        or ({"live", "start"} <= terms)
        or terms & {"strategypreset", "riskconfig"}
    )
    if not live_start_query:
        return {}

    targets = {
        "production_backend/src/FuturesLiveRoutes.java": (
            "%/api/futures/live/start%",
            "%strategyPreset%",
        ),
        "frontend/src/pages/FuturesLive.jsx": (
            "%/api/futures/live/start%",
            "%strategyPreset%",
        ),
    }
    candidates: Dict[int, Dict] = {}
    for path, patterns in targets.items():
        for pattern in patterns:
            rows = conn.execute(
                """
                SELECT id, path, text, tags, metadata_json, artifact_type
                FROM chunks
                WHERE path = ? AND text LIKE ?
                ORDER BY chunk_index
                LIMIT 2
                """,
                (path, pattern),
            ).fetchall()
            for row in rows:
                candidates[int(row["id"])] = {
                    "id": int(row["id"]),
                    "path": row["path"],
                    "text": row["text"],
                    "tags": row["tags"],
                    "metadata_json": row["metadata_json"],
                    "artifact_type": row["artifact_type"],
                    "lexical": 0.0,
                    "bm25": None,
                    "vector": 0.0,
                    "embedding": 0.0,
                    "reference": 0.0,
                    "symbol": 0.88,
                }
    return candidates


def cap_reference_only(items: List[Dict], max_reference_only: int) -> List[Dict]:
    """Keep graph-expanded hints from crowding out direct matches."""
    if max_reference_only <= 0:
        return items
    kept: List[Dict] = []
    reference_only = 0
    for item in items:
        if item.get("reference", 0.0) > 0 and float(item.get("direct_evidence", 0.0)) < MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION:
            if reference_only >= max_reference_only:
                continue
            reference_only += 1
        kept.append(item)
    return kept


def diversify_results(items: List[Dict], top_k: int, per_file: int = 2) -> List[Dict]:
    selected: List[Dict] = []
    counts: Dict[str, int] = {}
    for item in items:
        count = counts.get(item["path"], 0)
        if count >= per_file:
            continue
        selected.append(item)
        counts[item["path"]] = count + 1
        if len(selected) >= top_k:
            return selected
    for item in items:
        if item in selected:
            continue
        selected.append(item)
        if len(selected) >= top_k:
            break
    return selected


def run_query(config, query: str, top_k: int, context_pack: bool = False) -> List[Dict]:
    retrieval = config.get("retrieval", {})
    global REFERENCE_WEIGHT, REFERENCE_ONLY_SCORE_CAP, MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION
    REFERENCE_WEIGHT = float(retrieval.get("reference_weight", REFERENCE_WEIGHT))
    REFERENCE_ONLY_SCORE_CAP = float(retrieval.get("reference_only_score_cap", REFERENCE_ONLY_SCORE_CAP))
    MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION = float(
        retrieval.get("min_direct_evidence_for_graph_expansion", MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION)
    )
    keyword_top_k = int(retrieval.get("keyword_top_k", 20))
    vector_top_k = int(retrieval.get("vector_top_k", 20))
    embedding_top_k = int(retrieval.get("embedding_top_k", 20))
    if context_pack:
        keyword_top_k = max(keyword_top_k, int(retrieval.get("context_keyword_top_k", 90)))
        vector_top_k = max(vector_top_k, int(retrieval.get("context_vector_top_k", 90)))
        embedding_top_k = max(embedding_top_k, int(retrieval.get("context_embedding_top_k", 60)))

    conn = connect_db(config)
    stats = index_stats(conn)
    if stats["chunks"] == 0:
        raise RuntimeError(f"RAG index is empty. Run: python3 {Path(__file__).with_name('index_project.py')}")

    task_modes = classify_task(query)
    expanded = expanded_query(query)
    candidates = lexical_candidates(conn, expanded, keyword_top_k)
    for rowid, item in vector_candidates(conn, expanded, vector_top_k).items():
        if rowid in candidates:
            candidates[rowid]["vector"] = max(candidates[rowid]["vector"], item["vector"])
        else:
            candidates[rowid] = item
    for rowid, item in embedding_candidates(conn, config, expanded, embedding_top_k).items():
        if rowid in candidates:
            candidates[rowid]["embedding"] = max(candidates[rowid].get("embedding", 0.0), item["embedding"])
        else:
            candidates[rowid] = item
    symbol_top_k = max(embedding_top_k, 160 if context_pack else 40)
    for rowid, item in symbol_candidates(conn, expanded, symbol_top_k, gate_query=query).items():
        if rowid in candidates:
            candidates[rowid]["symbol"] = max(candidates[rowid].get("symbol", 0.0), item["symbol"])
        else:
            candidates[rowid] = item
    for rowid, item in targeted_contract_candidates(conn, expanded, task_modes).items():
        if rowid in candidates:
            candidates[rowid]["symbol"] = max(candidates[rowid].get("symbol", 0.0), item["symbol"])
        else:
            candidates[rowid] = item
    enriched = enrich_metadata(conn, candidates, expanded, task_modes)
    if context_pack:
        for rowid, item in reference_candidates(conn, strong_direct_items(enriched, limit=12), item_limit=16).items():
            if rowid in candidates:
                candidates[rowid]["reference"] = max(candidates[rowid].get("reference", 0.0), 1.0)
            else:
                candidates[rowid] = item
        for rowid, item in adjacent_source_candidates(conn, strong_direct_items(enriched, limit=16)).items():
            if rowid in candidates:
                candidates[rowid]["reference"] = max(candidates[rowid].get("reference", 0.0), 0.94)
            else:
                candidates[rowid] = item
        for rowid, item in companion_candidates(conn, strong_direct_items(enriched, limit=16)).items():
            if rowid in candidates:
                candidates[rowid]["reference"] = max(candidates[rowid].get("reference", 0.0), 1.0)
            else:
                candidates[rowid] = item
        enriched = enrich_metadata(conn, candidates, expanded, task_modes)
        for rowid, item in reference_candidates(conn, strong_direct_items(enriched, limit=24), item_limit=40).items():
            if rowid in candidates:
                candidates[rowid]["reference"] = max(candidates[rowid].get("reference", 0.0), 0.95)
            else:
                item["reference"] = 0.95
                candidates[rowid] = item
        enriched = enrich_metadata(conn, candidates, expanded, task_modes)
    if context_pack:
        max_reference_only = int(retrieval.get("max_reference_only_final", 3))
        per_file = 8 if is_code_shaped_query(query) else 2
        return diversify_results(cap_reference_only(enriched, max_reference_only), top_k, per_file=per_file)
    normal_per_file = int(retrieval.get("normal_max_per_file", 3))
    return diversify_results(enriched, top_k, per_file=normal_per_file)


def related_files(results: List[Dict], limit: int = 12) -> List[Dict]:
    grouped: Dict[str, Dict] = {}
    for item in results:
        current = grouped.setdefault(
            item["path"],
            {
                "path": item["path"],
                "artifact_type": item.get("artifact_type", ""),
                "score": 0.0,
                "chunks": 0,
                "title": "",
            },
        )
        current["score"] = max(float(current["score"]), float(item["score"]))
        current["chunks"] += 1
        if not current["title"]:
            try:
                metadata = json.loads(item.get("metadata_json") or "{}")
            except json.JSONDecodeError:
                metadata = {}
            current["title"] = metadata.get("title", "")
    rows = sorted(grouped.values(), key=lambda item: item["score"], reverse=True)
    return rows[:limit]


def stale_index_files(config, limit: int = 12) -> List[Dict]:
    """Return files that are newer on disk than the indexed files table."""
    retrieval = config.get("retrieval", {})
    if not bool(retrieval.get("stale_warning_enabled", True)):
        return []
    root = config_root(config)
    conn = connect_db(config)
    indexed = {
        row["path"]: float(row["mtime"])
        for row in conn.execute("SELECT path, mtime FROM files").fetchall()
    }
    stale: List[Dict] = []
    for path in iter_included_files(config):
        rel = posix_rel(path, root)
        try:
            mtime = float(path.stat().st_mtime)
        except OSError:
            continue
        indexed_mtime = indexed.get(rel)
        if indexed_mtime is None or mtime > indexed_mtime + 1.0:
            stale.append({"path": rel, "indexed_mtime": indexed_mtime, "disk_mtime": mtime})
            if len(stale) >= limit:
                break
    return stale


def main() -> int:
    args = parse_args()
    config = load_config(Path(args.config))
    query = " ".join(args.query).strip()
    default_top_k = int(config.get("retrieval", {}).get("context_final_top_k", 20)) if args.context_pack else int(config.get("retrieval", {}).get("final_top_k", 8))
    top_k = args.top_k or default_top_k
    root = config_root(config)
    stale_files = [] if args.no_stale_warning else stale_index_files(config)
    if stale_files:
        print("WARNING: RAG index may be stale; these included files changed after their indexed mtime:", file=sys.stderr)
        for item in stale_files[:8]:
            print(f"- {item['path']}", file=sys.stderr)
        print(f"Run: python3 {Path(__file__).with_name('index_project.py')}", file=sys.stderr)
        print("", file=sys.stderr)
        if args.fail_on_stale:
            return 3

    try:
        results = run_query(config, query, top_k, context_pack=args.context_pack)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    if args.json:
        payload = []
        for item in results:
            full_path = str((root / item["path"]).resolve())
            payload.append(
                {
                    "score": item["score"],
                    "path": full_path,
                    "relative_path": item["path"],
                    "line_start": item["line_start"],
                    "line_end": item["line_end"],
                    "file_type": item["file_type"],
                    "artifact_type": item.get("artifact_type", ""),
                    "authority": item.get("authority", authority_label(item)),
                    "tags": item["tags"].split(),
                    "context_header": item.get("context_header", ""),
                    "metadata": json.loads(item.get("metadata_json") or "{}"),
                    "direct_evidence": item.get("direct_evidence", 0.0),
                    "reference": item.get("reference", 0.0),
                    "reference_only": bool(item.get("reference", 0.0) > 0 and float(item.get("direct_evidence", 0.0)) < MIN_DIRECT_EVIDENCE_FOR_REFERENCE_EXPANSION),
                    "excerpt": excerpt(item["text"], query),
                }
            )
        print(json.dumps(payload, indent=2))
        return 0

    print(f"# Project Memory Results for: {query}\n")
    if not results:
        print("No indexed chunks matched. Try a broader query or re-index.")
        return 0

    for idx, item in enumerate(results, start=1):
        full_path = (root / item["path"]).resolve()
        print(
            f"{idx}. {full_path}:{item['line_start']}-{item['line_end']} "
            f"(score {item['score']:.3f}, direct {float(item.get('direct_evidence', 0.0)):.3f}, {item['file_type']})"
        )
        tag_preview = " ".join(item["tags"].split()[:12])
        if tag_preview:
            print(f"   tags: {tag_preview}")
        print(f"   authority: {item.get('authority', authority_label(item))}; artifact: {item.get('artifact_type', '')}")
        if args.include_context and item.get("context_header"):
            print(f"   context: {item['context_header'].replace(chr(10), ' | ')}")
        print(f"   {excerpt(item['text'], query)}\n")

    if args.context_pack:
        print("Related files in this context pack:")
        for item in related_files(results):
            full_path = (root / item["path"]).resolve()
            title = f" - {item['title']}" if item.get("title") else ""
            print(f"- {full_path} ({item['artifact_type']}, score {item['score']:.3f}){title}")
        print()

    print("Read the cited source files directly before editing or relying on these results.")
    print(f"Index: {db_path(config)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
