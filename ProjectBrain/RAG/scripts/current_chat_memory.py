#!/usr/bin/env python3
"""Maintain an isolated current-chat memory layer.

This layer is deliberately outside the permanent ProjectBrain vault index. It
is a scratch component for the active Codex chat: append visible turn notes,
query them after context compaction, and close them out during automatic RAG
write-back before the final response for completed work.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Tuple


ROOT = Path("/Users/anishpatel/Documents/SoftwareProject")
CURRENT_CHAT_DIR = ROOT / "ProjectBrain" / "RAG" / "CurrentChat"
SESSIONS_DIR = CURRENT_CHAT_DIR / "sessions"
ARCHIVE_DIR = CURRENT_CHAT_DIR / "archive"
STATE_PATH = CURRENT_CHAT_DIR / "state.json"
ADMIN_PATH = CURRENT_CHAT_DIR / "Admin.md"


def now_stamp() -> str:
    return datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %Z")


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return slug[:80] or "current-chat"


def tokenize(text: str) -> List[str]:
    return [
        token.lower()
        for token in re.findall(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)?|\d+(?:\.\d+)?", text)
        if len(token) > 1
    ]


def default_admin_text() -> str:
    return """# Current Chat Memory Admin

Purpose: keep the active Codex chat as an isolated, temporary RAG component.
It is the active scratch node beside the permanent ProjectBrain graph.

Rules:

- This directory is scratch memory, not permanent project memory.
- Do not include this directory in `ProjectBrain/RAG/memory.yml`.
- At new chat startup, create a fresh session with `current_chat_memory.py start`.
- During a chat, append concise visible-turn notes or pasted raw transcript with `append`.
- Before strong claims in the current chat, query both permanent RAG and the active current-chat session.
- After context compaction or resume, immediately run `current_chat_memory.py query "<current task>" --top-k 8` and reinsert the relevant results into working context.
- Before the final response for completed work, automatically use the active session plus the visible Codex chat to create curated permanent memory, refresh the permanent index, then close out the active session so scratch Markdown no longer appears as live graph context. The user should not need to ask for `update the RAG`.
- Never store secrets, credentials, `.env.example` values, broker tokens, account secrets, or raw sensitive runtime artifacts here.

Graph Links:

- [[Vault/Maps/RAG Workflow|RAG Workflow]]
- [[Vault/Maps/Memory Architecture|Memory Architecture]]
- [[Vault/Decisions/2026-05-28-current-chat-floating-rag-layer|Current Chat Floating RAG Layer]]
"""


def ensure_dirs() -> None:
    SESSIONS_DIR.mkdir(parents=True, exist_ok=True)
    ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
    if not ADMIN_PATH.exists():
        ADMIN_PATH.write_text(default_admin_text(), encoding="utf-8")


def load_state() -> Dict:
    if not STATE_PATH.exists():
        return {}
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def save_state(state: Dict) -> None:
    ensure_dirs()
    STATE_PATH.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def archived_path_for(path: Path) -> Path:
    return ARCHIVE_DIR / f"{path.name}.archived"


def append_archive_footer(path: Path, reason: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated = text
    updated = re.sub(r"^Status: active-floating$", "Status: archived-floating", updated, flags=re.MULTILINE)
    updated = re.sub(r"^Promotion: unpromoted$", "Promotion: archived", updated, flags=re.MULTILINE)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        text = updated
    if "\n## Archive\n" in text and "Archived:" in text:
        return
    with path.open("a", encoding="utf-8") as handle:
        handle.write(
            "\n".join(
                [
                    "",
                    "## Archive",
                    "",
                    f"Archived: {now_stamp()}",
                    f"Reason: {reason.strip() or 'Automatic RAG closeout completed'}",
                    "",
                ]
            )
        )


def move_session_out_of_graph(path: Path, reason: str) -> Path:
    append_archive_footer(path, reason)
    target = archived_path_for(path)
    if target.exists():
        target = ARCHIVE_DIR / f"{path.stem}-{datetime.now().astimezone().strftime('%H%M%S')}.md.archived"
    path.replace(target)
    return target


def active_path(required: bool = True) -> Path | None:
    state = load_state()
    raw_path = state.get("active_session")
    if not raw_path:
        if required:
            raise SystemExit("No active current-chat session. Run current_chat_memory.py start first.")
        return None
    path = Path(raw_path)
    if not path.exists() and required:
        raise SystemExit(f"Active current-chat session is missing: {path}")
    return path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Manage isolated current-chat memory.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    start = subparsers.add_parser("start", help="Create a fresh current-chat session.")
    start.add_argument("--title", default="Current Chat", help="Short title for this chat.")
    start.add_argument("--task", default="", help="One-sentence task summary.")
    start.add_argument(
        "--continue-active",
        action="store_true",
        help="Reuse the current active session instead of creating a new floating component.",
    )
    start.add_argument(
        "--replace-active",
        action="store_true",
        help="Deprecated alias for the default fresh-session behavior.",
    )

    append = subparsers.add_parser("append", help="Append a visible turn note to the active session.")
    append.add_argument("--role", choices=["user", "assistant", "system", "tool", "note"], default="note")
    append.add_argument("--topic", default="general", help="Micro-topic for retrieval.")
    append.add_argument("--text", default="", help="Text to append. Reads stdin when omitted.")

    query = subparsers.add_parser("query", help="Search the active current-chat session.")
    query.add_argument("query", help="Search query.")
    query.add_argument("--top-k", type=int, default=8)

    status = subparsers.add_parser("status", help="Show active session status.")
    status.add_argument("--json", action="store_true")

    export = subparsers.add_parser("export", help="Print active session content for automatic RAG closeout/write-back.")
    export.add_argument("--tail", type=int, default=0, help="Only show the last N lines.")

    closeout = subparsers.add_parser(
        "closeout",
        aliases=["archive"],
        help="Close the active session after automatic permanent RAG write-back.",
    )
    closeout.add_argument("--reason", default="Automatic RAG closeout completed")
    closeout.add_argument(
        "--expected-title",
        default="",
        help="Refuse to close if the active session title does not match. Guards concurrent Codex threads.",
    )
    closeout.add_argument(
        "--expected-task",
        default="",
        help="Refuse to close if the active session task does not match. Guards concurrent Codex threads.",
    )
    closeout.add_argument(
        "--keep-markdown",
        action="store_true",
        help="Leave the closed session as .md under sessions. Use only for debugging.",
    )

    sweep = subparsers.add_parser(
        "sweep",
        help="Move archived/superseded session Markdown files out of graph-visible scratch storage.",
    )
    sweep.add_argument("--dry-run", action="store_true")

    return parser.parse_args()


def session_header(title: str, task: str) -> str:
    lines = [
        f"# Current Chat - {title}",
        "",
        f"Started: {now_stamp()}",
        "Status: active-floating",
        "Promotion: unpromoted",
        "Permanent RAG: disconnected",
        "",
        "## Task",
        task.strip() or "No task summary supplied.",
        "",
        "## Turn Log",
        "",
    ]
    return "\n".join(lines)


def start_session(args: argparse.Namespace) -> int:
    ensure_dirs()
    existing = active_path(required=False)
    if existing and existing.exists() and args.continue_active:
        print(f"Continuing active current-chat session: {existing}")
        return 0
    if existing and existing.exists():
        with existing.open("a", encoding="utf-8") as handle:
            handle.write(
                "\n".join(
                    [
                        "",
                        "## Superseded",
                        "",
                        f"Superseded: {now_stamp()}",
                        "Reason: a fresh current-chat session was started.",
                        "",
                    ]
                )
            )
        archived = move_session_out_of_graph(existing, "Superseded by a fresh current-chat session.")
        print(f"Archived superseded current-chat session: {archived}")

    date_prefix = datetime.now().astimezone().strftime("%Y-%m-%d-%H%M%S")
    path = SESSIONS_DIR / f"{date_prefix}-{slugify(args.title)}.md"
    path.write_text(session_header(args.title, args.task), encoding="utf-8")
    save_state(
        {
            "active_session": str(path),
            "title": args.title,
            "task": args.task,
            "started_at": now_stamp(),
            "status": "active-floating",
        }
    )
    print(f"Started current-chat session: {path}")
    return 0


def append_turn(args: argparse.Namespace) -> int:
    ensure_dirs()
    path = active_path(required=True)
    assert path is not None
    text = args.text.strip()
    if not text and not sys.stdin.isatty():
        text = sys.stdin.read().strip()
    if not text:
        print("No text supplied for append.", file=sys.stderr)
        return 2

    entry = "\n".join(
        [
            f"### {now_stamp()} - {args.role} - {args.topic.strip() or 'general'}",
            "",
            text,
            "",
        ]
    )
    with path.open("a", encoding="utf-8") as handle:
        handle.write(entry)
    print(f"Appended current-chat memory: {path}")
    return 0


def split_entries(text: str) -> List[Tuple[str, str]]:
    matches = list(re.finditer(r"^### .+$", text, flags=re.MULTILINE))
    entries: List[Tuple[str, str]] = []
    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        block = text[start:end].strip()
        heading = block.splitlines()[0]
        entries.append((heading, block))
    if not entries and text.strip():
        entries.append(("Session", text.strip()))
    return entries


def score_entry(query_terms: List[str], block: str) -> float:
    terms = tokenize(block)
    if not terms:
        return 0.0
    counts: Dict[str, int] = {}
    for term in terms:
        counts[term] = counts.get(term, 0) + 1
    score = 0.0
    for term in query_terms:
        if term in counts:
            score += 2.0 + min(counts[term], 5) * 0.25
        for candidate in counts:
            if term != candidate and (term in candidate or candidate in term):
                score += 0.35
    return score


def query_session(args: argparse.Namespace) -> int:
    ensure_dirs()
    path = active_path(required=True)
    assert path is not None
    text = path.read_text(encoding="utf-8")
    query_terms = tokenize(args.query)
    scored = [
        (score_entry(query_terms, block), heading, block)
        for heading, block in split_entries(text)
    ]
    scored = [item for item in scored if item[0] > 0]
    scored.sort(key=lambda item: item[0], reverse=True)

    print(f"# Current Chat Results for: {args.query}")
    print(f"Session: {path}")
    if not scored:
        print("No matching current-chat entries found.")
        return 0
    for rank, (score, heading, block) in enumerate(scored[: max(args.top_k, 1)], start=1):
        excerpt = re.sub(r"\s+", " ", block).strip()
        if len(excerpt) > 900:
            excerpt = excerpt[:897] + "..."
        print(f"\n{rank}. {heading} (score {score:.2f})")
        print(excerpt)
    return 0


def show_status(args: argparse.Namespace) -> int:
    ensure_dirs()
    state = load_state()
    path = active_path(required=False)
    if args.json:
        print(json.dumps(state, indent=2, sort_keys=True))
        return 0
    if not path:
        print("No active current-chat session.")
        return 0
    print(f"Active current-chat session: {path}")
    print(f"Status: {state.get('status', 'unknown')}")
    print(f"Task: {state.get('task', '')}")
    return 0


def export_session(args: argparse.Namespace) -> int:
    path = active_path(required=True)
    assert path is not None
    lines = path.read_text(encoding="utf-8").splitlines()
    if args.tail and args.tail > 0:
        lines = lines[-args.tail :]
    print("\n".join(lines))
    return 0


def closeout_session(args: argparse.Namespace) -> int:
    path = active_path(required=True)
    assert path is not None
    state = load_state()
    if args.expected_title and state.get("title") != args.expected_title:
        print(
            "Refusing to close current-chat session because active title does not match "
            f"expected title {args.expected_title!r}: {state.get('title')!r}",
            file=sys.stderr,
        )
        return 4
    if args.expected_task and state.get("task") != args.expected_task:
        print(
            "Refusing to close current-chat session because active task does not match "
            f"expected task {args.expected_task!r}: {state.get('task')!r}",
            file=sys.stderr,
        )
        return 4
    archive_reason = args.reason.strip() or "Automatic RAG closeout completed"
    if args.keep_markdown:
        append_archive_footer(path, archive_reason)
        archived = path
    else:
        archived = move_session_out_of_graph(path, archive_reason)
    state["status"] = "archived-floating"
    state["archived_at"] = now_stamp()
    state["archive_reason"] = archive_reason
    state["archived_session"] = str(archived)
    state.pop("active_session", None)
    save_state(state)
    print(f"Closed out current-chat session: {archived}")
    if not args.keep_markdown:
        print("Moved scratch Markdown out of graph-visible sessions storage.")
    return 0


def sweep_sessions(args: argparse.Namespace) -> int:
    ensure_dirs()
    state = load_state()
    active = Path(state["active_session"]).resolve() if state.get("active_session") else None
    moved = 0
    skipped = 0
    for path in sorted(SESSIONS_DIR.glob("*.md")):
        if active and path.resolve() == active:
            skipped += 1
            continue
        text = path.read_text(encoding="utf-8")
        should_move = (
            "Status: archived-floating" in text
            or "## Superseded" in text
            or "## Archive" in text
            or "Promotion: promoted" in text
            or "Promotion: promoted-empty" in text
        )
        if not should_move:
            skipped += 1
            continue
        if args.dry_run:
            print(f"Would archive graph-visible scratch session: {path}")
        else:
            archived = move_session_out_of_graph(path, "Swept after automatic permanent RAG write-back.")
            print(f"Archived graph-visible scratch session: {archived}")
        moved += 1
    print(f"Sweep complete: moved={moved}, skipped={skipped}")
    return 0


def main() -> int:
    args = parse_args()
    if args.command == "start":
        return start_session(args)
    if args.command == "append":
        return append_turn(args)
    if args.command == "query":
        return query_session(args)
    if args.command == "status":
        return show_status(args)
    if args.command == "export":
        return export_session(args)
    if args.command in {"closeout", "archive"}:
        return closeout_session(args)
    if args.command == "sweep":
        return sweep_sessions(args)
    raise AssertionError(args.command)


if __name__ == "__main__":
    raise SystemExit(main())
