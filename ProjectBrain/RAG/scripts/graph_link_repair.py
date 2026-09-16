#!/usr/bin/env python3
"""Repair Obsidian graph links for ProjectBrain vault notes.

The RAG index can retrieve standalone Markdown notes, but Obsidian graph view
and reference expansion need explicit wikilinks. This script adds deterministic
hub links to notes that would otherwise float away from the main memory graph.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable, List, Sequence


ROOT = Path("/Users/anishpatel/Documents/SoftwareProject")
VAULT = ROOT / "ProjectBrain" / "Vault"

DEFAULT_HUBS = [
    "[[Handoff/Project Memory Gateway|Project Memory Gateway]]",
]

PATH_HUBS = {
    "chatdumps": [
        "[[ChatDumps/Chat Memory Dumps|Chat Memory Dumps]]",
        "[[Maps/RAG Workflow|RAG Workflow]]",
    ],
    "concepts": [
        "[[Home|Project Brain Home]]",
        "[[Maps/Memory Architecture|Memory Architecture]]",
    ],
    "decisions": [
        "[[Decisions/Decision Ledger|Decision Ledger]]",
        "[[Maps/Memory Architecture|Memory Architecture]]",
    ],
    "dtm": [
        "[[DTM/DTM Trade Handling Gateway|DTM Trade Handling Gateway]]",
        "[[Runtime/DTM And Order Flow|DTM And Order Flow]]",
    ],
    "history": [
        "[[History/Implementation History Atlas|Implementation History Atlas]]",
    ],
    "maps": [
        "[[Home|Project Brain Home]]",
        "[[Maps/Memory Architecture|Memory Architecture]]",
        "[[Maps/RAG Workflow|RAG Workflow]]",
    ],
    "modules": [
        "[[Modules/Module Workflow Atlas|Module Workflow Atlas]]",
    ],
    "reports/diagnostics": [
        "[[Reports/Diagnostics/Live Backend Diagnostic Playbook|Live Backend Diagnostic Playbook]]",
        "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
    ],
    "reports/marketdays": [
        "[[Reports/MarketDays/Market Day Review Playbook|Market Day Review Playbook]]",
        "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
    ],
    "reports/strategyresearch": [
        "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
        "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
    ],
    "runtime": [
        "[[Home|Project Brain Home]]",
        "[[Maps/Memory Architecture|Memory Architecture]]",
    ],
    "sourcemaps": [
        "[[SourceMaps/Backend Source Parity|Backend Source Parity]]",
    ],
    "sprints": [
        "[[Sprints/NextSprint|Next Sprint]]",
    ],
    "strategies": [
        "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
        "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
    ],
    "templates": [
        "[[Home|Project Brain Home]]",
    ],
}

KEYWORD_HUBS = [
    (
        ("rag", "retrieval", "index", "memory", "vault", "graph", "chat-dump", "floating"),
        [
            "[[Maps/RAG Workflow|RAG Workflow]]",
            "[[Maps/Memory Architecture|Memory Architecture]]",
        ],
    ),
    (
        ("live-backend", "live backend", "live-start", "session", "broker", "reconcile", "ledger", "order-flow"),
        [
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Reports/Diagnostics/Live Backend Diagnostic Playbook|Live Backend Diagnostic Playbook]]",
        ],
    ),
    (
        ("candidate-generation", "market-data", "captured", "feed", "stale", "decay", "latency"),
        [
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
        ],
    ),
    (
        ("backtest", "parity", "strategy-research", "market-structure", "strategy", "signal"),
        [
            "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
            "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
        ],
    ),
    (
        ("risk-config", "risk", "drawdown", "topstep", "account", "sizing"),
        [
            "[[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
        ],
    ),
    (
        ("dtm", "managed stop", "runner", "protect", "target extension"),
        [
            "[[Runtime/DTM And Order Flow|DTM And Order Flow]]",
            "[[DTM/DTM Trade Handling Gateway|DTM Trade Handling Gateway]]",
        ],
    ),
    (
        ("frontend", "ui", "chart", "contract", "display"),
        [
            "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
        ],
    ),
    (
        ("preset", "strategy config", "bestbiasfree"),
        [
            "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
        ],
    ),
    (
        ("market-day", "market day", "trade review", "daily trade"),
        [
            "[[Reports/MarketDays/Market Day Review Playbook|Market Day Review Playbook]]",
        ],
    ),
]

GRAPH_HEADING_RE = re.compile(r"\n## Graph Links\n.*?(?=\n## |\Z)", re.DOTALL)
ANY_GRAPH_HEADING_RE = re.compile(r"\n#{2,6} Graph Links\n", re.DOTALL)
WIKILINK_RE = re.compile(r"\[\[[^\]]+\]\]")


def unique_links(values: Iterable[str]) -> List[str]:
    links: List[str] = []
    for value in values:
        cleaned = value.strip()
        if cleaned and cleaned not in links:
            links.append(cleaned)
    return links


def vault_relative(path: Path) -> str:
    try:
        return path.resolve().relative_to(VAULT).as_posix()
    except ValueError:
        return path.as_posix()


def path_hubs(path: Path) -> List[str]:
    rel = vault_relative(path).lower()
    links: List[str] = []
    for prefix, hubs in PATH_HUBS.items():
        if rel.startswith(prefix + "/") or rel == prefix:
            links.extend(hubs)
    return links


def infer_graph_links(path: Path, text: str) -> List[str]:
    semantic_text = re.split(r"\n#{2,6} Promotion Checklist\n", text, maxsplit=1)[0]
    semantic_text = re.split(r"\n#{2,6} Graph Links\n", semantic_text, maxsplit=1)[0]
    haystack = f"{vault_relative(path)}\n{semantic_text[:12000]}".lower()
    links: List[str] = []
    links.extend(DEFAULT_HUBS)
    links.extend(path_hubs(path))
    for keywords, hubs in KEYWORD_HUBS:
        if any(keyword in haystack for keyword in keywords):
            links.extend(hubs)
    return unique_links(links)


def existing_graph_links(text: str) -> List[str]:
    match = GRAPH_HEADING_RE.search(text)
    if not match:
        return []
    return unique_links(WIKILINK_RE.findall(match.group(0)))


def has_wikilinks(text: str) -> bool:
    return bool(WIKILINK_RE.search(text))


def has_graph_links_section(text: str) -> bool:
    return bool(ANY_GRAPH_HEADING_RE.search(text))


def ensure_graph_links(text: str, links: Sequence[str]) -> str:
    merged = unique_links([*existing_graph_links(text), *links])
    if not merged:
        return text
    section = "\n## Graph Links\n\n" + "\n".join(f"- {link}" for link in merged) + "\n"
    if GRAPH_HEADING_RE.search(text):
        return GRAPH_HEADING_RE.sub(section.rstrip(), text).rstrip() + "\n"
    return text.rstrip() + section


def iter_vault_markdown(paths: Sequence[Path]) -> List[Path]:
    if paths:
        resolved: List[Path] = []
        for path in paths:
            full = path if path.is_absolute() else ROOT / path
            if full.is_dir():
                resolved.extend(sorted(full.rglob("*.md")))
            else:
                resolved.append(full)
        return [path for path in resolved if path.exists() and path.suffix == ".md"]
    return sorted(VAULT.rglob("*.md"))


def repair_candidates(paths: Sequence[Path], ensure_section: bool) -> List[Path]:
    candidates: List[Path] = []
    for path in iter_vault_markdown(paths):
        text = path.read_text(encoding="utf-8", errors="replace")
        if not has_wikilinks(text) or (ensure_section and not has_graph_links_section(text)):
            candidates.append(path)
    return candidates


def repair_file(path: Path, apply: bool) -> bool:
    text = path.read_text(encoding="utf-8", errors="replace")
    repaired = ensure_graph_links(text, infer_graph_links(path, text))
    changed = repaired != text
    if changed and apply:
        path.write_text(repaired, encoding="utf-8")
    return changed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Repair missing ProjectBrain vault graph links.")
    parser.add_argument("--apply", action="store_true", help="Write repairs. Without this, print a dry-run summary.")
    parser.add_argument("--check", action="store_true", help="Return nonzero when floating notes remain.")
    parser.add_argument(
        "--ensure-section",
        action="store_true",
        help="Also add a top-level Graph Links section to notes that already have inline wikilinks.",
    )
    parser.add_argument("--path", action="append", default=[], help="File or directory to scan. Defaults to ProjectBrain/Vault.")
    parser.add_argument("--limit", type=int, default=80, help="Maximum candidate paths to print.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = [Path(value) for value in args.path]
    candidates = repair_candidates(paths, args.ensure_section)
    changed = 0
    for path in candidates:
        if repair_file(path, args.apply):
            changed += 1

    floating_after: List[Path] = []
    for path in iter_vault_markdown(paths):
        text = path.read_text(encoding="utf-8", errors="replace")
        if not has_wikilinks(text):
            floating_after.append(path)

    verb = "Repaired" if args.apply else "Would repair"
    print(f"{verb} {changed} ProjectBrain vault note(s).")
    if candidates:
        print("Candidate notes:")
        for path in candidates[: args.limit]:
            print(f"- {path}")
        if len(candidates) > args.limit:
            print(f"- ... {len(candidates) - args.limit} more")
    print(f"Floating notes remaining: {len(floating_after)}")
    if floating_after:
        for path in floating_after[: args.limit]:
            print(f"- {path}")

    if args.check and floating_after:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
