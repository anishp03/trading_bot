#!/usr/bin/env python3
"""One-command startup and closeout wrapper for ProjectBrain RAG.

This script makes the AGENTS.md workflow mechanically repeatable:
- startup: create current-chat scratch, refresh the index, retrieve relevant context.
- closeout: write curated durable memory if supplied, refresh the index, close scratch.

It intentionally does not auto-summarize or invent facts from raw chat. Codex must
pass curated facts and source links during closeout. That design prevents the RAG
from indexing hallucinations as future source material.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import List, Optional


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path("/Users/anishpatel/Documents/SoftwareProject")

CURRENT_CHAT = SCRIPT_DIR / "current_chat_memory.py"
INDEX = SCRIPT_DIR / "index_project.py"
QUERY = SCRIPT_DIR / "query_project.py"
DEPOSIT = SCRIPT_DIR / "deposit_chat_memory.py"
GRAPH_REPAIR = SCRIPT_DIR / "graph_link_repair.py"

BROAD_CONTEXT_TERMS = {
    "diagnostic", "diagnostics", "why", "no trades", "live", "dtm", "risk",
    "strategy", "frontend", "backend", "contract", "broker", "reconcile",
    "architecture", "rag", "index", "retrieval", "source map", "deployment",
}


def run_step(label: str, command: List[str], input_text: Optional[str] = None, allow_fail: bool = False) -> int:
    print(f"\n## {label}")
    print("$ " + " ".join(command))
    completed = subprocess.run(
        command,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.stdout:
        print(completed.stdout.rstrip())
    if completed.stderr:
        print(completed.stderr.rstrip(), file=sys.stderr)
    if completed.returncode != 0 and not allow_fail:
        print(f"FAILED: {label} exited with {completed.returncode}", file=sys.stderr)
        raise SystemExit(completed.returncode)
    return completed.returncode


def read_stdin() -> str:
    if sys.stdin.isatty():
        return ""
    return sys.stdin.read().strip()


def active_current_chat_title() -> str:
    completed = subprocess.run(
        [sys.executable, str(CURRENT_CHAT), "status", "--json"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0 or not completed.stdout.strip():
        return ""
    try:
        state = json.loads(completed.stdout)
    except json.JSONDecodeError:
        return ""
    return str(state.get("title", "")).strip()


def needs_context_pack(task: str) -> bool:
    lowered = task.lower()
    if len(task.split()) >= 14:
        return True
    return any(term in lowered for term in BROAD_CONTEXT_TERMS)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the ProjectBrain RAG chat startup or closeout loop.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    startup = subparsers.add_parser("startup", help="Start current-chat scratch, refresh index, and retrieve context.")
    startup.add_argument("--title", required=True, help="Short chat title.")
    startup.add_argument("--task", required=True, help="One-sentence task summary / retrieval seed.")
    startup.add_argument("--top-k", type=int, default=8)
    startup.add_argument("--context-top-k", type=int, default=16)
    startup.add_argument("--context-pack", action="store_true", help="Force context-pack retrieval.")
    startup.add_argument("--no-auto-context-pack", action="store_true", help="Do not infer context-pack need from the task.")
    startup.add_argument("--include-context", action="store_true", default=True, help="Include contextual headers in broad retrieval output.")
    startup.add_argument("--continue-active", action="store_true", help="Continue active current-chat scratch instead of creating a fresh session.")
    startup.add_argument("--skip-index", action="store_true", help="Skip startup index refresh. Use only for fast debugging.")
    startup.add_argument("--query-current", action="store_true", help="Query current-chat scratch after startup retrieval.")

    closeout = subparsers.add_parser("closeout", help="Write curated memory, refresh index, and close current-chat scratch.")
    closeout.add_argument("--title", required=True, help="Short chat memory title.")
    closeout.add_argument("--summary", default="", help="Curated durable summary.")
    closeout.add_argument("--tag", action="append", default=[])
    closeout.add_argument("--fact", action="append", default=[], help="category.micro-topic::evidence-backed fact")
    closeout.add_argument("--link", action="append", default=[], help="source/evidence path, wiki link, endpoint, or eval")
    closeout.add_argument("--append", action="store_true", help="Append if today's chat dump already exists.")
    closeout.add_argument("--allow-unsourced", action="store_true", help="Allow facts without source links for explicit source-free decisions/preferences.")
    closeout.add_argument("--no-memory", action="store_true", help="No durable memory was created; refresh index and close scratch only.")
    closeout.add_argument("--keep-active", action="store_true", help="Do not close current-chat scratch after indexing.")
    closeout.add_argument("--keep-scratch-markdown", action="store_true", help="Close scratch but keep .md in sessions for debugging.")
    closeout.add_argument("--reason", default="Automatic RAG closeout after memory write-back")

    status = subparsers.add_parser("status", help="Show current-chat status.")
    status.add_argument("--json", action="store_true")

    return parser.parse_args()


def startup(args: argparse.Namespace) -> int:
    start_cmd = [sys.executable, str(CURRENT_CHAT), "start", "--title", args.title, "--task", args.task]
    if args.continue_active:
        start_cmd.append("--continue-active")
    run_step("current-chat startup", start_cmd)

    if not args.skip_index:
        run_step("incremental RAG index refresh", [sys.executable, str(INDEX)])

    run_step(
        "permanent RAG retrieval",
        [sys.executable, str(QUERY), args.task, "--top-k", str(args.top_k), "--fail-on-stale"],
        allow_fail=False,
    )

    broad = args.context_pack or (not args.no_auto_context_pack and needs_context_pack(args.task))
    if broad:
        cmd = [sys.executable, str(QUERY), args.task, "--context-pack", "--top-k", str(args.context_top_k), "--fail-on-stale"]
        if args.include_context:
            cmd.append("--include-context")
        run_step("compact context-pack retrieval", cmd)

    if args.query_current or args.continue_active:
        run_step(
            "current-chat scratch retrieval",
            [sys.executable, str(CURRENT_CHAT), "query", args.task, "--top-k", "8"],
            allow_fail=True,
        )

    print("\nRAG startup complete. Read cited files directly before editing or relying on conclusions.")
    return 0


def closeout(args: argparse.Namespace) -> int:
    notes = read_stdin()
    has_memory_payload = bool(args.summary.strip() or args.fact or notes.strip())

    if args.fact and not args.link and not args.allow_unsourced:
        print(
            "Refusing closeout memory write: --fact was supplied without --link evidence. "
            "Add at least one source path/wiki link/eval/endpoint, or use --allow-unsourced for explicit source-free decisions.",
            file=sys.stderr,
        )
        return 3

    if args.no_memory or not has_memory_payload:
        print("\n## memory write-back")
        print("No durable memory supplied; skipping permanent chat dump write.")
        memory_written = False
    else:
        cmd = [sys.executable, str(DEPOSIT), "--title", args.title, "--summary", args.summary]
        for tag in args.tag:
            cmd.extend(["--tag", tag])
        for fact in args.fact:
            cmd.extend(["--fact", fact])
        for link in args.link:
            cmd.extend(["--link", link])
        if args.append:
            cmd.append("--append")
        if args.allow_unsourced:
            cmd.append("--allow-unsourced")
        run_step("curated permanent memory deposit", cmd, input_text=notes if notes else None)
        memory_written = True

    run_step("vault graph link repair", [sys.executable, str(GRAPH_REPAIR), "--apply", "--ensure-section"])
    run_step("post-closeout RAG index refresh", [sys.executable, str(INDEX)])

    if not args.keep_active:
        expected_title = active_current_chat_title()
        cmd = [
            sys.executable,
            str(CURRENT_CHAT),
            "closeout",
            "--reason",
            args.reason,
        ]
        if expected_title:
            cmd.extend(["--expected-title", expected_title])
        if args.keep_scratch_markdown:
            cmd.append("--keep-markdown")
        run_step("current-chat scratch closeout", cmd, allow_fail=True)

    if memory_written:
        print("\nRAG: updated and indexed")
    else:
        print("\nRAG: no durable memory to write; index refreshed")
    return 0


def status(args: argparse.Namespace) -> int:
    cmd = [sys.executable, str(CURRENT_CHAT), "status"]
    if args.json:
        cmd.append("--json")
    return run_step("current-chat status", cmd, allow_fail=True)


def main() -> int:
    args = parse_args()
    if args.command == "startup":
        return startup(args)
    if args.command == "closeout":
        return closeout(args)
    if args.command == "status":
        return status(args)
    raise AssertionError(args.command)


if __name__ == "__main__":
    raise SystemExit(main())
