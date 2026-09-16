#!/usr/bin/env python3
"""Create or append evidence-gated ProjectBrain chat memory.

By default this script appends durable, curated memory to one consolidated
ledger at ProjectBrain/Vault/ChatDumps/Chat Memory Ledger.md.
It deliberately does not scrape raw chat and does not infer facts on its own.
Codex must pass a concise summary, categorized facts, and source/evidence links.
"""

from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Tuple

from graph_link_repair import ensure_graph_links, infer_graph_links


ROOT = Path("/Users/anishpatel/Documents/SoftwareProject")
VAULT = ROOT / "ProjectBrain" / "Vault"
CHAT_DUMPS = VAULT / "ChatDumps"
CHAT_LEDGER = CHAT_DUMPS / "Chat Memory Ledger.md"

DEFAULT_TAGS = ["chat-dump", "project-memory"]
LEDGER_TAGS = ["chat-ledger", "project-memory"]
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?i)\b(api[_-]?key|secret|token|password|credential)\b\s*[:=]\s*\S+"),
    re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"),
]


def now_local() -> str:
    return datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %Z")


def today() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return slug[:80] or "chat-memory"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create or append an evidence-gated ProjectBrain chat memory dump."
    )
    parser.add_argument("--title", required=True, help="Short title for this chat memory.")
    parser.add_argument(
        "--summary",
        default="",
        help="One or two sentence curated summary of durable work completed.",
    )
    parser.add_argument(
        "--tag",
        action="append",
        default=[],
        help="Tag to attach to the dump. Can be supplied multiple times.",
    )
    parser.add_argument(
        "--fact",
        action="append",
        default=[],
        help="Categorized fact as 'category.micro-topic::evidence-backed fact'. Can be supplied multiple times.",
    )
    parser.add_argument(
        "--link",
        action="append",
        default=[],
        help="Source path, Obsidian wiki link, endpoint, eval, or command output proving the memory. Can be supplied multiple times.",
    )
    parser.add_argument(
        "--date",
        default=today(),
        help="Date prefix for the dump file, default today.",
    )
    parser.add_argument(
        "--append",
        action="store_true",
        help="Append a new update section if a per-chat dump already exists. The consolidated ledger always appends.",
    )
    parser.add_argument(
        "--per-chat-file",
        action="store_true",
        help="Use the legacy one-file-per-chat dump layout instead of the consolidated chat ledger.",
    )
    parser.add_argument(
        "--allow-unsourced",
        action="store_true",
        help="Allow facts without source links. Use only for explicit user preferences or non-source-backed chat decisions.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the generated markdown without writing.",
    )
    return parser.parse_args()


def normalize_category(value: str) -> Tuple[str, str]:
    category = re.sub(r"\s+", " ", value.strip()) or "uncategorized"
    category = category.replace("\\", "/")
    parts = [part.strip() for part in re.split(r"[./|>]+", category) if part.strip()]
    if not parts:
        return "uncategorized", "general"
    primary = parts[0]
    micro_topic = " / ".join(parts[1:]) if len(parts) > 1 else "general"
    return primary, micro_topic


def parse_facts(values: List[str]) -> Dict[str, Dict[str, List[str]]]:
    facts: Dict[str, Dict[str, List[str]]] = {}
    for raw in values:
        if "::" in raw:
            category, text = raw.split("::", 1)
        else:
            category, text = "uncategorized", raw
        primary, micro_topic = normalize_category(category)
        text = text.strip()
        if not text:
            continue
        facts.setdefault(primary, {}).setdefault(micro_topic, []).append(text)
    return facts


def stdin_notes() -> str:
    if sys.stdin.isatty():
        return ""
    return sys.stdin.read().strip()


def contains_secret_material(text: str) -> bool:
    return any(pattern.search(text) for pattern in SECRET_PATTERNS)


def yaml_list(values: List[str]) -> List[str]:
    return [f"  - {value}" for value in values]


def frontmatter(title: str, tags: List[str], fact_count: int, source_count: int) -> str:
    lines: List[str] = [
        "---",
        "artifact_type: chat-dump",
        "status: active",
        "source_of_truth: false",
        f"created_at: {now_local()}",
        f"updated_at: {now_local()}",
        f"title: {title}",
        f"facts_count: {fact_count}",
        f"source_count: {source_count}",
        "tags:",
    ]
    lines.extend(yaml_list(tags))
    lines.append("---")
    lines.append("")
    lines.append(f"# {title}")
    lines.append("")
    return "\n".join(lines)


def ledger_frontmatter(tags: List[str], source_count: int) -> str:
    lines: List[str] = [
        "---",
        "artifact_type: chat-ledger",
        "status: active",
        "source_of_truth: false",
        "authority_lane: chat-trace",
        "subsystem: rag.chat-memory",
        f"created_at: {now_local()}",
        f"updated_at: {now_local()}",
        "title: Chat Memory Ledger",
        f"source_count: {source_count}",
        "tags:",
    ]
    lines.extend(yaml_list(tags))
    lines.append("---")
    lines.append("")
    lines.append("# Chat Memory Ledger")
    lines.append("")
    lines.append(
        "This append-only ledger stores curated chat memory cards. It is a trace lane, not source truth; direct source files and promoted notes remain authoritative."
    )
    lines.append("")
    return "\n".join(lines)


def section(args: argparse.Namespace, facts: Dict[str, Dict[str, List[str]]], notes: str) -> str:
    lines: List[str] = []
    timestamp = now_local()
    if args.per_chat_file:
        lines.append(f"## Chat Update - {timestamp}")
    else:
        lines.append(f"## Chat Update - {timestamp} - {args.title.strip()}")
    lines.append("")
    lines.append("### Summary")
    lines.append(args.summary.strip() or "No summary supplied.")
    lines.append("")
    lines.append("### Evidence Standard")
    lines.append("- Facts in this dump are curated memory cards, not raw transcript scrape.")
    lines.append("- Source links below must be inspected directly before relying on a conclusion.")
    lines.append("- Retrieved memory remains a pointer, not proof.")
    lines.append("")
    lines.append("### Categorized Memory Cards")
    if facts:
        lines.append("")
        lines.append("#### Category Index")
        for category in sorted(facts):
            micro_topics = sorted(facts[category])
            if micro_topics == ["general"]:
                lines.append(f"- `{category}`")
            else:
                lines.append(f"- `{category}`: " + ", ".join(f"`{topic}`" for topic in micro_topics))
        lines.append("")
        for category in sorted(facts):
            lines.append(f"#### {category}")
            for micro_topic in sorted(facts[category]):
                if micro_topic != "general":
                    lines.append(f"##### {micro_topic}")
                for fact in facts[category][micro_topic]:
                    lines.append(f"- {fact}")
    else:
        lines.append("- No categorized facts supplied.")
    lines.append("")
    lines.append("### Source / Evidence Links")
    if args.link:
        for link in args.link:
            lines.append(f"- {link}")
    else:
        lines.append("- No source links supplied.")
    lines.append("")
    lines.append("### Working Notes")
    if notes:
        lines.append(notes)
    else:
        lines.append("- No raw working notes supplied.")
    lines.append("")
    lines.append("### Promotion Checklist")
    lines.extend(
        [
            "- Promote accepted decisions or rejected hypotheses to `ProjectBrain/Vault/Decisions/`.",
            "- Promote live diagnostics to `ProjectBrain/Vault/Reports/Diagnostics/`.",
            "- Promote market-day reports to `ProjectBrain/Vault/Reports/MarketDays/`.",
            "- Promote durable subsystem explanations to `ProjectBrain/Vault/Concepts/`.",
            "- Do not promote routine reads, transient commands, raw logs, or secret-bearing material.",
        ]
    )
    lines.append("")
    return "\n".join(lines)


def update_frontmatter(existing: str, tags: List[str], source_count: int) -> str:
    if not existing.startswith("---\n"):
        return existing
    end = existing.find("\n---\n", 4)
    if end < 0:
        return existing
    fm = existing[: end + 5]
    body = existing[end + 5 :]
    fm = re.sub(r"^updated_at: .*$", f"updated_at: {now_local()}", fm, flags=re.MULTILINE)
    fm = re.sub(r"^source_count: .*$", f"source_count: {source_count}", fm, flags=re.MULTILINE)
    if "tags:\n" in fm:
        # Keep existing tags, do not attempt destructive YAML rewrite.
        pass
    return fm + body


def main() -> int:
    args = parse_args()
    base_tags = DEFAULT_TAGS if args.per_chat_file else LEDGER_TAGS
    tags = sorted(set(base_tags + [tag.strip() for tag in args.tag if tag.strip()]))
    facts = parse_facts(args.fact)
    notes = stdin_notes()

    all_text = "\n".join([args.title, args.summary, *args.fact, *args.link, notes])
    if contains_secret_material(all_text):
        print("Refusing to write possible secret-bearing material into permanent RAG.", file=sys.stderr)
        return 4

    has_facts = any(facts.values())
    if has_facts and not args.link and not args.allow_unsourced:
        print(
            "Refusing to write facts without at least one --link evidence/source pointer. "
            "Use --allow-unsourced only for explicit user preferences or source-free chat decisions.",
            file=sys.stderr,
        )
        return 3

    if not args.summary.strip() and not has_facts and not notes.strip():
        print("No summary, facts, or notes supplied; no memory dump was written.", file=sys.stderr)
        return 2

    fact_count = sum(len(values) for micro in facts.values() for values in micro.values())
    target = CHAT_DUMPS / f"{args.date}-{slugify(args.title)}.md" if args.per_chat_file else CHAT_LEDGER
    body = section(args, facts, notes)
    content = (
        frontmatter(args.title, tags, fact_count, len(args.link))
        if args.per_chat_file
        else ledger_frontmatter(tags, len(args.link))
    ) + body
    content = ensure_graph_links(content, infer_graph_links(target, content))
    if args.dry_run:
        print(content.rstrip() + "\n")
        return 0

    CHAT_DUMPS.mkdir(parents=True, exist_ok=True)
    if target.exists() and not args.per_chat_file:
        existing = update_frontmatter(target.read_text(encoding="utf-8").rstrip(), tags, len(args.link))
        content = ensure_graph_links(existing + "\n\n" + body, infer_graph_links(target, existing + "\n\n" + body))
        target.write_text(content.rstrip() + "\n", encoding="utf-8")
        action = "Appended"
    elif target.exists() and args.append:
        existing = update_frontmatter(target.read_text(encoding="utf-8").rstrip(), tags, len(args.link))
        content = ensure_graph_links(existing + "\n\n" + body, infer_graph_links(target, existing + "\n\n" + body))
        target.write_text(content, encoding="utf-8")
        action = "Appended"
    elif target.exists():
        print(
            f"Refusing to overwrite existing dump without --append: {target}",
            file=sys.stderr,
        )
        return 2
    else:
        target.write_text(content.rstrip() + "\n", encoding="utf-8")
        action = "Created"

    label = "chat memory dump" if args.per_chat_file else "chat memory ledger"
    print(f"{action} {label}: {target}")
    print("Run index_project.py after reviewing the dump, or use chat_rag_cycle.py closeout.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
