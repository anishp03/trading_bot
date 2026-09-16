#!/usr/bin/env python3
"""Shared local RAG utilities.

This module intentionally avoids third-party dependencies. It uses the small
YAML subset present in memory.yml, SQLite FTS5 for BM25 retrieval, and a local
token-vector reranker for dependency-free semantic-ish recall.
"""

from __future__ import annotations

import fnmatch
import glob
import hashlib
import json
import math
import os
import re
import sqlite3
import time
from datetime import datetime, timezone
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


RAG_DIR = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = RAG_DIR / "memory.yml"
DEFAULT_DB_NAME = "project_memory.sqlite3"
SCHEMA_VERSION = "4"
MAX_FILE_BYTES = 2_000_000

TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)?|\d+(?:\.\d+)?")
CAMEL_RE = re.compile(r"(?<!^)(?=[A-Z])")

STOPWORDS = {
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do",
    "for", "from", "has", "have", "if", "in", "into", "is", "it", "its",
    "not", "of", "on", "or", "our", "that", "the", "their", "then", "this",
    "to", "was", "we", "when", "with", "you", "your",
}

QUERY_EXPANSIONS = {
    "dtm": [
        "dynamic trade manager", "order flow", "entry optimizer",
        "broker reconcile", "ledger trade cache", "live trade handling",
        "DTM Trade Management",
    ],
    "strategy": [
        "strategy presets", "candidate generation", "backtest live integrity",
        "detector logic", "market structure",
    ],
    "risk": [
        "risk config", "sizing guards", "daily loss", "trailing drawdown",
        "aggregate exposure", "correlated family", "validateLivePortfolioSignal",
        "DAILY_LOSS_GUARD", "TRAILING_DRAWDOWN_GUARD",
        "CORRELATED_SYMBOL_ALREADY_OPEN",
    ],
    "live": [
        "live start", "live bot pipeline", "runtime status",
        "broker reconcile", "market data",
    ],
    "frontend": ["frontend backend contract", "FuturesLive", "FuturesBacktest"],
    "backend": ["MainServer", "FuturesManager", "DatabaseManager"],
    "broker": ["Topstep", "ProjectX", "orders", "positions", "fills"],
    "market": ["market data", "candles", "realtime feed", "ProjectX"],
    "realtime": [
        "ProjectXRealtimeManager", "GatewayDepth", "includeDepth",
        "subscribedContracts", "getStatusJson",
    ],
    "cache": ["trade cache", "Frontend Backend Contract", "live trade cache"],
    "preset": ["Strategy Preset Workflow", "strategy presets", "strategyPreset"],
    "rag": ["ProjectBrain", "memory deposit", "retrieval eval", "chat dump"],
    "diagnostic": ["Live Backend Diagnostic Playbook", "diagnostics", "no trades"],
    "diagnostics": ["Live Backend Diagnostic Playbook", "diagnostic", "no trades"],
}


def parse_scalar(value: str):
    value = value.strip()
    if not value:
        return ""
    if (value.startswith('"') and value.endswith('"')) or (
        value.startswith("'") and value.endswith("'")
    ):
        return value[1:-1]
    lowered = value.lower()
    if lowered == "true":
        return True
    if lowered == "false":
        return False
    try:
        if "." in value:
            return float(value)
        return int(value)
    except ValueError:
        return value


def load_config(config_path: Path = DEFAULT_CONFIG) -> Dict:
    """Load the small YAML subset used by ProjectBrain/RAG/memory.yml."""
    data: Dict = {}
    stack: List[Tuple[int, object]] = [(-1, data)]

    for raw_line in config_path.read_text(encoding="utf-8").splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        line = raw_line.strip()

        while stack and indent <= stack[-1][0]:
            stack.pop()
        parent = stack[-1][1]

        if line.startswith("- "):
            if not isinstance(parent, list):
                raise ValueError(f"List item without list parent in {config_path}: {raw_line}")
            parent.append(parse_scalar(line[2:].strip()))
            continue

        if ":" not in line:
            raise ValueError(f"Unsupported config line in {config_path}: {raw_line}")

        key, value = line.split(":", 1)
        key = key.strip()
        value = value.strip()

        if value:
            if isinstance(parent, dict):
                parent[key] = parse_scalar(value)
            else:
                raise ValueError(f"Mapping item under list is not supported: {raw_line}")
            continue

        child: object
        next_is_list = _next_content_is_list(config_path, raw_line)
        child = [] if next_is_list else {}
        if isinstance(parent, dict):
            parent[key] = child
        else:
            raise ValueError(f"Nested mapping under list is not supported: {raw_line}")
        stack.append((indent, child))

    return data


def _next_content_is_list(config_path: Path, current_line: str) -> bool:
    lines = config_path.read_text(encoding="utf-8").splitlines()
    try:
        idx = lines.index(current_line)
    except ValueError:
        return False
    current_indent = len(current_line) - len(current_line.lstrip(" "))
    for candidate in lines[idx + 1 :]:
        if not candidate.strip() or candidate.lstrip().startswith("#"):
            continue
        indent = len(candidate) - len(candidate.lstrip(" "))
        if indent <= current_indent:
            return False
        return candidate.strip().startswith("- ")
    return False


def config_root(config: Dict) -> Path:
    return Path(str(config.get("root", RAG_DIR.parents[1]))).expanduser().resolve()


def storage_dir(config: Dict) -> Path:
    return Path(str(config.get("storage", RAG_DIR / "storage"))).expanduser().resolve()


def db_path(config: Dict) -> Path:
    return storage_dir(config) / DEFAULT_DB_NAME


def expand_braces(pattern: str) -> List[str]:
    match = re.search(r"\{([^{}]+)\}", pattern)
    if not match:
        return [pattern]
    prefix = pattern[: match.start()]
    suffix = pattern[match.end() :]
    expanded: List[str] = []
    for part in match.group(1).split(","):
        expanded.extend(expand_braces(prefix + part + suffix))
    return expanded


def posix_rel(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root).as_posix()


def matches_pattern(rel_path: str, pattern: str) -> bool:
    normalized = pattern.strip().strip('"').strip("'").replace("\\", "/")
    if normalized.endswith("/**"):
        prefix = normalized[:-3]
        return rel_path == prefix or rel_path.startswith(prefix + "/")
    return fnmatch.fnmatch(rel_path, normalized) or Path(rel_path).match(normalized)


def is_excluded(rel_path: str, exclude_patterns: Sequence[str]) -> bool:
    return any(matches_pattern(rel_path, pattern) for pattern in exclude_patterns)


def iter_included_files(config: Dict) -> List[Path]:
    root = config_root(config)
    include_patterns = config.get("include", [])
    exclude_patterns = config.get("exclude", [])
    files = set()

    for pattern in include_patterns:
        for expanded in expand_braces(str(pattern)):
            for matched in glob.glob(str(root / expanded), recursive=True):
                path = Path(matched)
                if not path.is_file():
                    continue
                rel = posix_rel(path, root)
                if is_excluded(rel, exclude_patterns):
                    continue
                if path.stat().st_size > MAX_FILE_BYTES:
                    continue
                files.add(path.resolve())

    return sorted(files, key=lambda p: posix_rel(p, root))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_text(path: Path) -> Optional[str]:
    try:
        raw = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in raw[:4096]:
        return None
    for encoding in ("utf-8", "utf-8-sig", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return None


def split_identifier(token: str) -> List[str]:
    pieces = []
    for part in re.split(r"[_\-.]+", token):
        pieces.extend(CAMEL_RE.sub(" ", part).split())
    return [piece.lower() for piece in pieces if piece]


def tokenize(text: str) -> List[str]:
    tokens: List[str] = []
    for raw in TOKEN_RE.findall(text):
        lowered = raw.lower()
        if len(lowered) > 1 and lowered not in STOPWORDS:
            tokens.append(lowered)
        for piece in split_identifier(raw):
            if len(piece) > 1 and piece not in STOPWORDS:
                tokens.append(piece)
    return tokens


def expanded_query(query: str) -> str:
    terms = set(tokenize(query))
    additions: List[str] = []
    for trigger, expansion_terms in QUERY_EXPANSIONS.items():
        if trigger in terms:
            additions.extend(expansion_terms)
    if not additions:
        return query
    return query + " " + " ".join(dict.fromkeys(additions))


def token_count(text: str) -> int:
    return len(tokenize(text))


def sparse_terms(text: str, limit: int = 250) -> Dict[str, float]:
    counts = Counter(tokenize(text))
    if not counts:
        return {}
    total = sum(counts.values())
    weighted = {
        term: round((1.0 + math.log(count)) / math.sqrt(total), 6)
        for term, count in counts.items()
    }
    return dict(sorted(weighted.items(), key=lambda item: item[1], reverse=True)[:limit])


def cosine(query_terms: Dict[str, float], doc_terms: Dict[str, float]) -> float:
    if not query_terms or not doc_terms:
        return 0.0
    dot = sum(weight * doc_terms.get(term, 0.0) for term, weight in query_terms.items())
    q_norm = math.sqrt(sum(weight * weight for weight in query_terms.values()))
    d_norm = math.sqrt(sum(weight * weight for weight in doc_terms.values()))
    if not q_norm or not d_norm:
        return 0.0
    return dot / (q_norm * d_norm)


def file_type(path: Path) -> str:
    suffix = path.suffix.lower().lstrip(".")
    if suffix in {"md", "markdown"}:
        return "markdown"
    if suffix in {"java", "js", "jsx", "ts", "tsx", "css"}:
        return suffix
    if suffix in {"yml", "yaml"}:
        return "yaml"
    return suffix or "text"


def artifact_type_for(rel_path: str) -> str:
    path = rel_path.replace("\\", "/")
    if path == "AGENTS.md":
        return "operating-manual"
    if path == "ProjectBrain/Vault/Sprints/NextSprint.md":
        return "sprint-plan"
    if path.startswith("ProjectBrain/Vault/Reports/Sprints/"):
        return "sprint-report"
    if path == "ProjectBrain/Vault/ChatDumps/Chat Memory Ledger.md":
        return "chat-ledger"
    if path.startswith("ProjectBrain/Vault/ChatDumps/"):
        return "chat-dump"
    if path.startswith("ProjectBrain/Vault/Reports/Diagnostics/"):
        stem = Path(path).stem.lower()
        if any(term in stem for term in ("rag", "retrieval", "hardening", "memory")):
            return "rag-design"
        return "diagnostic-report"
    if path.startswith("ProjectBrain/Vault/Architecture/CurrentBot/"):
        return "architecture-current"
    if path.startswith("ProjectBrain/Vault/Architecture/Contracts/"):
        return "architecture-contract"
    if path.startswith("ProjectBrain/Vault/Research/Active/"):
        return "research-active"
    if path.startswith("ProjectBrain/Vault/Research/Archive/"):
        return "research-archived"
    if path.startswith("ProjectBrain/Vault/Reports/MarketDays/"):
        return "market-day-report"
    if path.startswith("ProjectBrain/Vault/Decisions/"):
        return "decision"
    if path.startswith("ProjectBrain/Vault/Concepts/"):
        return "concept"
    if path.startswith("ProjectBrain/Vault/Strategies/"):
        return "strategy-note"
    if path.startswith("ProjectBrain/Vault/Modules/"):
        return "module-note"
    if path.startswith("ProjectBrain/Vault/Runtime/"):
        return "runtime-note"
    if path.startswith("ProjectBrain/Vault/SourceMaps/"):
        return "source-map"
    if path.startswith("ProjectBrain/Vault/Maps/"):
        return "workflow-map"
    if path.startswith("ProjectBrain/Vault/DTM/"):
        return "dtm-note"
    if path.startswith("ProjectBrain/Vault/History/"):
        return "history-note"
    if path.startswith("ProjectBrain/RAG/scripts/"):
        return "rag-script"
    if path.startswith("production_backend/src/"):
        return "backend-source"
    if path.startswith("production_backend/tests/"):
        return "backend-test"
    if path.startswith("frontend/src/"):
        return "frontend-source"
    if path.startswith("frontend/functions/"):
        return "frontend-function"
    return "project-file"


def title_for(path: Path, rel_path: str, text: str) -> str:
    for line in text.splitlines()[:80]:
        match = re.match(r"^#\s+(.+?)\s*$", line)
        if match:
            return match.group(1).strip()
    return Path(rel_path).stem


def frontmatter_for(text: str) -> Dict[str, object]:
    """Parse a small Obsidian/YAML frontmatter subset.

    The parser is intentionally conservative and dependency-free. It supports
    scalar values plus simple list values. Unsupported structures are ignored
    rather than guessed; RAG ranking should never invent metadata.
    """
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}
    end = None
    for idx, line in enumerate(lines[1:80], start=1):
        if line.strip() == "---":
            end = idx
            break
    if end is None:
        return {}
    payload: Dict[str, object] = {}
    current_key = ""
    for raw in lines[1:end]:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw.startswith("  - ") and current_key:
            payload.setdefault(current_key, [])
            if isinstance(payload[current_key], list):
                payload[current_key].append(str(parse_scalar(raw.strip()[2:].strip())))
            continue
        if ":" not in raw:
            continue
        key, value = raw.split(":", 1)
        key = key.strip().replace("-", "_")
        value = value.strip()
        current_key = key
        if not key:
            continue
        if value == "":
            payload[key] = []
        elif value.startswith("[") and value.endswith("]"):
            parts = [item.strip().strip('"\'') for item in value[1:-1].split(",") if item.strip()]
            payload[key] = parts
        else:
            payload[key] = parse_scalar(value)
    return payload


def metadata_scalar(metadata: Dict[str, object], key: str) -> str:
    value = metadata.get(key, "")
    if isinstance(value, list):
        return " ".join(str(item) for item in value)
    if value is None:
        return ""
    return str(value)


def heading_context(lines: List[str], line_start: int, line_end: int) -> List[str]:
    headings: List[Tuple[int, str]] = []
    for idx, line in enumerate(lines, start=1):
        if idx > line_end:
            break
        match = re.match(r"^(#{1,6})\s+(.+?)\s*$", line)
        if not match:
            continue
        level = len(match.group(1))
        title = match.group(2).strip()
        headings = [(existing_level, existing_title) for existing_level, existing_title in headings if existing_level < level]
        headings.append((level, title))
    return [title for _, title in headings[-4:]]


def code_symbols_for_chunk(path: Path, chunk_text: str) -> List[str]:
    suffix = path.suffix.lower()
    if suffix not in {".java", ".js", ".jsx", ".ts", ".tsx", ".py"}:
        return []
    symbols = set()
    for match in re.finditer(r"\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\(", chunk_text):
        name = match.group(1)
        if name in {"if", "for", "while", "switch", "catch", "return", "new", "super"}:
            continue
        symbols.add(name)
    for match in re.finditer(r"\b(?:class|interface|enum|record|function)\s+([A-Za-z_$][A-Za-z0-9_$]*)", chunk_text):
        symbols.add(match.group(1))
    for match in re.finditer(r"\b(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=", chunk_text):
        symbols.add(match.group(1))
    for match in re.finditer(r"\b([A-Z][A-Z0-9_]{3,})\b", chunk_text):
        symbols.add(match.group(1))
    for match in re.finditer(r'["`]([A-Za-z_$][A-Za-z0-9_$]{2,})["`]', chunk_text):
        symbols.add(match.group(1))
    return sorted(symbols)[:80]


def defined_symbols_for_chunk(path: Path, chunk_text: str) -> List[str]:
    suffix = path.suffix.lower()
    symbols = set()
    if suffix == ".java":
        for line in chunk_text.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith(("//", "*", "@")):
                continue
            class_match = re.search(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*)", stripped)
            if class_match:
                symbols.add(class_match.group(1))
            if "(" not in stripped or stripped.startswith(("if ", "for ", "while ", "switch ", "catch ", "return ")):
                continue
            prefix = stripped.split("(", 1)[0].strip()
            pieces = re.split(r"\s+", prefix)
            if len(pieces) < 2:
                continue
            name = pieces[-1]
            if re.match(r"^[A-Za-z_$][A-Za-z0-9_$]*$", name) and name not in {"if", "for", "while", "switch", "catch", "return", "new"}:
                symbols.add(name)
    elif suffix in {".js", ".jsx", ".ts", ".tsx"}:
        patterns = [
            r"\bfunction\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(",
            r"\b(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s*)?\(",
            r"\b(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s*)?[A-Za-z_$][A-Za-z0-9_$]*\s*=>",
        ]
        for pattern in patterns:
            symbols.update(match.group(1) for match in re.finditer(pattern, chunk_text))
    elif suffix == ".py":
        symbols.update(match.group(1) for match in re.finditer(r"\b(?:def|class)\s+([A-Za-z_][A-Za-z0-9_]*)", chunk_text))
    return sorted(symbols)[:40]


def api_paths_for_chunk(chunk_text: str) -> List[str]:
    paths = set()
    for match in re.finditer(r'["`](/api/[A-Za-z0-9_./{}?=&:-]+)', chunk_text):
        paths.add(match.group(1))
    return sorted(paths)[:40]


def metadata_for(path: Path, rel_path: str, text: str, chunk: Dict, tags: str) -> Dict:
    lines = text.splitlines()
    headings = heading_context(lines, int(chunk["line_start"]), int(chunk["line_end"]))
    title = title_for(path, rel_path, text)
    artifact_type = artifact_type_for(rel_path)
    parts = Path(rel_path).parts
    strategy_code = ""
    module_name = ""
    if len(parts) >= 4 and parts[0:3] == ("ProjectBrain", "Vault", "Strategies"):
        strategy_code = Path(parts[-1]).stem.upper()
    if len(parts) >= 4 and parts[0:3] == ("ProjectBrain", "Vault", "Modules"):
        module_name = Path(parts[-1]).stem
    chunk_body = str(chunk.get("text", ""))
    frontmatter = frontmatter_for(text)
    frontmatter_tags = frontmatter.get("tags", [])
    if isinstance(frontmatter_tags, str):
        frontmatter_tags = [frontmatter_tags]
    try:
        stat = path.stat()
        source_modified_ts = float(stat.st_mtime)
        source_size = int(stat.st_size)
        source_modified_at = datetime.fromtimestamp(source_modified_ts, timezone.utc).isoformat()
    except OSError:
        source_modified_ts = 0.0
        source_size = 0
        source_modified_at = ""
    return {
        "artifact_type": artifact_type,
        "title": title,
        "headings": headings,
        "tags": sorted(set(tags.split()) | {str(tag).strip().lower() for tag in frontmatter_tags if str(tag).strip()}),
        "strategy_code": strategy_code,
        "module_name": module_name,
        "status": metadata_scalar(frontmatter, "status"),
        "promotion": metadata_scalar(frontmatter, "promotion"),
        "source_of_truth": metadata_scalar(frontmatter, "source_of_truth"),
        "authority_lane": metadata_scalar(frontmatter, "authority_lane"),
        "subsystem": metadata_scalar(frontmatter, "subsystem"),
        "created_at": metadata_scalar(frontmatter, "created_at"),
        "updated_at": metadata_scalar(frontmatter, "updated_at"),
        "superseded_by": metadata_scalar(frontmatter, "superseded_by"),
        "evidence": metadata_scalar(frontmatter, "evidence"),
        "code_symbols": code_symbols_for_chunk(path, chunk_body),
        "defined_symbols": defined_symbols_for_chunk(path, chunk_body),
        "api_paths": api_paths_for_chunk(chunk_body),
        "source_path": rel_path,
        "source_modified_at": source_modified_at,
        "source_modified_ts": source_modified_ts,
        "source_size": source_size,
    }


def context_header(metadata: Dict) -> str:
    fields = [
        f"Source path: {metadata.get('source_path', '')}",
        f"Artifact type: {metadata.get('artifact_type', '')}",
        f"Title: {metadata.get('title', '')}",
    ]
    if metadata.get("strategy_code"):
        fields.append(f"Strategy code: {metadata['strategy_code']}")
    if metadata.get("module_name"):
        fields.append(f"Module: {metadata['module_name']}")
    if metadata.get("status"):
        fields.append(f"Status: {metadata['status']}")
    if metadata.get("updated_at"):
        fields.append(f"Updated: {metadata['updated_at']}")
    if metadata.get("source_of_truth"):
        fields.append(f"Source of truth: {metadata['source_of_truth']}")
    if metadata.get("authority_lane"):
        fields.append(f"Authority lane: {metadata['authority_lane']}")
    if metadata.get("subsystem"):
        fields.append(f"Subsystem: {metadata['subsystem']}")
    if metadata.get("superseded_by"):
        fields.append(f"Superseded by: {metadata['superseded_by']}")
    headings = metadata.get("headings") or []
    if headings:
        fields.append("Headings: " + " > ".join(str(item) for item in headings))
    symbols = metadata.get("code_symbols") or []
    if symbols:
        fields.append("Code symbols: " + " ".join(str(item) for item in symbols[:50]))
    defined_symbols = metadata.get("defined_symbols") or []
    if defined_symbols:
        fields.append("Defined symbols: " + " ".join(str(item) for item in defined_symbols[:30]))
    api_paths = metadata.get("api_paths") or []
    if api_paths:
        fields.append("API paths: " + " ".join(str(item) for item in api_paths[:25]))
    tags = metadata.get("tags") or []
    if tags:
        fields.append("Tags: " + " ".join(str(item) for item in tags[:30]))
    return "\n".join(fields)


def dense_embedding(text: str, dimensions: int = 256) -> List[float]:
    vector = [0.0] * max(16, dimensions)
    for token in tokenize(text):
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        bucket = int.from_bytes(digest[:4], "big") % len(vector)
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[bucket] += sign
    norm = math.sqrt(sum(value * value for value in vector))
    if not norm:
        return vector
    return [round(value / norm, 6) for value in vector]


def dense_cosine(left: Sequence[float], right: Sequence[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(a * a for a in left))
    right_norm = math.sqrt(sum(b * b for b in right))
    if not left_norm or not right_norm:
        return 0.0
    return dot / (left_norm * right_norm)


def embeddings_enabled(config: Dict) -> bool:
    embeddings = config.get("embeddings", {})
    return bool(embeddings.get("enabled", False))


def embedding_dimensions(config: Dict) -> int:
    embeddings = config.get("embeddings", {})
    return int(embeddings.get("dimensions", 256))


def chunk_text(path: Path, text: str, config: Dict) -> List[Dict]:
    chunking = config.get("chunking", {})
    suffix = path.suffix.lower()
    if suffix in {".md", ".markdown"}:
        target = int(chunking.get("markdown_target_tokens", chunking.get("target_tokens", 550)))
        max_tokens = int(chunking.get("markdown_max_tokens", chunking.get("max_tokens", 850)))
        overlap = int(chunking.get("markdown_overlap_tokens", chunking.get("overlap_tokens", 90)))
    elif suffix in {".java", ".js", ".jsx", ".ts", ".tsx", ".py"}:
        target = int(chunking.get("code_target_tokens", chunking.get("target_tokens", 700)))
        max_tokens = int(chunking.get("code_max_tokens", chunking.get("max_tokens", 1000)))
        overlap = int(chunking.get("code_overlap_tokens", chunking.get("overlap_tokens", 120)))
    else:
        target = int(chunking.get("target_tokens", 550))
        max_tokens = int(chunking.get("max_tokens", 850))
        overlap = int(chunking.get("overlap_tokens", 90))
    min_heading_flush = int(chunking.get("min_heading_flush_tokens", max(80, target // 3)))
    lines = text.splitlines()
    if not lines:
        return []

    chunks: List[Dict] = []
    current: List[Tuple[int, str]] = []
    current_tokens = 0

    def flush() -> None:
        nonlocal current, current_tokens
        if not current:
            return
        body = "\n".join(line for _, line in current).strip()
        if body:
            chunks.append(
                {
                    "line_start": current[0][0],
                    "line_end": current[-1][0],
                    "text": body,
                    "token_count": token_count(body),
                }
            )
        if overlap <= 0:
            current = []
            current_tokens = 0
            return
        kept: List[Tuple[int, str]] = []
        kept_tokens = 0
        for line_no, line in reversed(current):
            line_tokens = token_count(line)
            if kept and kept_tokens + line_tokens > overlap:
                break
            kept.append((line_no, line))
            kept_tokens += line_tokens
        current = list(reversed(kept))
        current_tokens = kept_tokens

    for line_no, line in enumerate(lines, start=1):
        line_tokens = max(1, token_count(line))
        heading_break = path.suffix.lower() in {".md", ".markdown"} and re.match(r"^#{1,6}\s+", line)
        if current and heading_break and current_tokens >= min_heading_flush:
            flush()
        if current and current_tokens + line_tokens > max_tokens:
            flush()
        current.append((line_no, line))
        current_tokens += line_tokens
        if current_tokens >= target and line.strip() == "":
            flush()

    flush()

    for idx, chunk in enumerate(chunks):
        chunk["chunk_index"] = idx
        chunk["file_type"] = file_type(path)
    return chunks


def ensure_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS files (
            path TEXT PRIMARY KEY,
            sha256 TEXT NOT NULL,
            mtime REAL NOT NULL,
            size INTEGER NOT NULL,
            indexed_at REAL NOT NULL,
            chunk_count INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS chunks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            path TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            line_start INTEGER NOT NULL,
            line_end INTEGER NOT NULL,
            file_type TEXT NOT NULL,
            token_count INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            text TEXT NOT NULL,
            search_text TEXT NOT NULL DEFAULT '',
            context_header TEXT NOT NULL DEFAULT '',
            metadata_json TEXT NOT NULL DEFAULT '{}',
            artifact_type TEXT NOT NULL DEFAULT '',
            embedding_json TEXT NOT NULL DEFAULT '',
            terms_json TEXT NOT NULL,
            tags TEXT NOT NULL
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
            path,
            text,
            tags,
            tokenize='porter unicode61'
        );
        CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path);
        CREATE INDEX IF NOT EXISTS idx_chunks_file_type ON chunks(file_type);
        """
    )
    columns = {
        row[1] for row in conn.execute("PRAGMA table_info(chunks)").fetchall()
    }
    migrations = {
        "search_text": "ALTER TABLE chunks ADD COLUMN search_text TEXT NOT NULL DEFAULT ''",
        "context_header": "ALTER TABLE chunks ADD COLUMN context_header TEXT NOT NULL DEFAULT ''",
        "metadata_json": "ALTER TABLE chunks ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'",
        "artifact_type": "ALTER TABLE chunks ADD COLUMN artifact_type TEXT NOT NULL DEFAULT ''",
        "embedding_json": "ALTER TABLE chunks ADD COLUMN embedding_json TEXT NOT NULL DEFAULT ''",
    }
    for column, statement in migrations.items():
        if column not in columns:
            conn.execute(statement)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_chunks_artifact_type ON chunks(artifact_type)")
    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES ('schema_version', ?)",
        (SCHEMA_VERSION,),
    )
    conn.commit()


def connect_db(config: Dict) -> sqlite3.Connection:
    storage_dir(config).mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path(config))
    conn.row_factory = sqlite3.Row
    ensure_schema(conn)
    return conn


def delete_file_chunks(conn: sqlite3.Connection, rel_path: str) -> None:
    rowids = [row["id"] for row in conn.execute("SELECT id FROM chunks WHERE path = ?", (rel_path,))]
    for rowid in rowids:
        conn.execute("DELETE FROM chunks_fts WHERE rowid = ?", (rowid,))
    conn.execute("DELETE FROM chunks WHERE path = ?", (rel_path,))
    conn.execute("DELETE FROM files WHERE path = ?", (rel_path,))


def tags_for(path: Path, rel_path: str, text: str) -> str:
    tags = set(part.lower() for part in Path(rel_path).parts[:-1])
    tags.add(file_type(path))
    tags.add(artifact_type_for(rel_path))
    tags.update(term for term in tokenize(rel_path) if len(term) > 2)
    for explicit_tag in re.findall(r"`([a-zA-Z0-9][a-zA-Z0-9_-]+)`", text[:3000]):
        tags.add(explicit_tag.lower())
    return " ".join(sorted(tags))


def excerpt(text: str, query: str, max_chars: int = 700) -> str:
    clean = re.sub(r"\s+", " ", text).strip()
    if len(clean) <= max_chars:
        return clean
    query_tokens = tokenize(query)
    lower = clean.lower()
    positions = [lower.find(token) for token in query_tokens if lower.find(token) >= 0]
    if positions:
        start = max(0, min(positions) - max_chars // 3)
    else:
        start = 0
    end = min(len(clean), start + max_chars)
    prefix = "... " if start > 0 else ""
    suffix = " ..." if end < len(clean) else ""
    return prefix + clean[start:end].strip() + suffix


def fts_query(query: str) -> str:
    terms = []
    for term in tokenize(query):
        if len(term) < 2:
            continue
        safe = term.replace('"', "")
        terms.append(f'"{safe}"')
    return " OR ".join(dict.fromkeys(terms))


def read_json_dict(value: str) -> Dict[str, float]:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return {}
    if not isinstance(parsed, dict):
        return {}
    return {str(k): float(v) for k, v in parsed.items()}


def index_stats(conn: sqlite3.Connection) -> Dict[str, int]:
    return {
        "files": conn.execute("SELECT COUNT(*) FROM files").fetchone()[0],
        "chunks": conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0],
    }


def now_iso() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S %Z")
