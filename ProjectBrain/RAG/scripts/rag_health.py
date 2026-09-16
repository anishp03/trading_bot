#!/usr/bin/env python3
"""ProjectBrain RAG health checks.

Checks the local-only RAG installation for stale index entries, accidental
CurrentChat inclusion, empty indexes, and optional retrieval eval failures.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Dict, List

from rag_lib import config_root, connect_db, db_path, index_stats, iter_included_files, load_config, posix_rel
from query_project import stale_index_files
from graph_link_repair import has_wikilinks


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_CONFIG = SCRIPT_DIR.parents[0] / "memory.yml"
EVAL = SCRIPT_DIR / "eval_rag.py"
INDEX = SCRIPT_DIR / "index_project.py"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check ProjectBrain RAG health.")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--eval", action="store_true", help="Run retrieval evals as part of health check.")
    parser.add_argument("--rebuild-if-empty", action="store_true", help="Run a reset rebuild when the index has zero chunks.")
    return parser.parse_args()


def current_chat_included(config: Dict) -> List[str]:
    root = config_root(config)
    offenders: List[str] = []
    for path in iter_included_files(config):
        rel = posix_rel(path, root)
        if rel.startswith("ProjectBrain/RAG/CurrentChat/"):
            offenders.append(rel)
    return offenders


def run_eval(config_path: Path) -> Dict:
    completed = subprocess.run(
        [sys.executable, str(EVAL), "--config", str(config_path), "--json"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    payload: Dict = {"returncode": completed.returncode, "stderr": completed.stderr.strip()}
    try:
        payload.update(json.loads(completed.stdout or "{}"))
    except json.JSONDecodeError:
        payload["stdout"] = completed.stdout.strip()
    return payload


def floating_vault_notes(config: Dict, limit: int = 25) -> List[str]:
    root = config_root(config)
    offenders: List[str] = []
    for path in iter_included_files(config):
        rel = posix_rel(path, root)
        if not rel.startswith("ProjectBrain/Vault/") or path.suffix != ".md":
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if not has_wikilinks(text):
            offenders.append(rel)
            if len(offenders) >= limit:
                break
    return offenders


def rebuild(config_path: Path) -> int:
    completed = subprocess.run(
        [sys.executable, str(INDEX), "--config", str(config_path), "--reset", "--stats"],
        text=True,
    )
    return completed.returncode


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    config = load_config(config_path)
    conn = connect_db(config)
    stats = index_stats(conn)

    if stats["chunks"] == 0 and args.rebuild_if_empty:
        rc = rebuild(config_path)
        if rc != 0:
            print(f"Rebuild failed with exit code {rc}", file=sys.stderr)
            return rc
        conn = connect_db(config)
        stats = index_stats(conn)

    stale = stale_index_files(config, limit=25)
    offenders = current_chat_included(config)
    floating = floating_vault_notes(config)
    eval_payload = run_eval(config_path) if args.eval else None

    ok = bool(stats["chunks"] > 0) and not stale and not offenders and not floating
    if eval_payload is not None:
        ok = ok and eval_payload.get("returncode") == 0

    payload = {
        "ok": ok,
        "db": str(db_path(config)),
        "stats": stats,
        "stale_files": stale,
        "current_chat_included": offenders,
        "floating_vault_notes": floating,
        "eval": eval_payload,
    }

    if args.json:
        print(json.dumps(payload, indent=2, sort_keys=True))
    else:
        print(f"RAG health: {'OK' if ok else 'ATTENTION'}")
        print(f"Index: {payload['db']}")
        print(f"Files indexed: {stats['files']}  Chunks: {stats['chunks']}")
        if stale:
            print("Stale or missing index entries:")
            for item in stale:
                print(f"- {item['path']}")
        if offenders:
            print("CurrentChat files are accidentally included in permanent RAG:")
            for path in offenders:
                print(f"- {path}")
        if floating:
            print("Vault notes have no wikilinks and may float in graph view:")
            for path in floating:
                print(f"- {path}")
        if eval_payload is not None:
            print(f"Eval returncode: {eval_payload.get('returncode')}")
            if eval_payload.get("total") is not None:
                print(f"Eval passed: {eval_payload.get('passed')}/{eval_payload.get('total')}")
        if not ok:
            print("Recommended next command:")
            print(f"python3 {INDEX} --reset --stats")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
