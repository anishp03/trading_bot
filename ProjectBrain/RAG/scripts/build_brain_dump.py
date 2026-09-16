#!/usr/bin/env python3
"""Build the structured ProjectBrain vault from docs and source maps.

This is a one-time/migration-friendly generator. It reads source-of-truth docs,
the local production backend source, and the read-only live backend source, then writes
Obsidian/RAG-friendly notes under ProjectBrain/Vault.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


ROOT = Path("/Users/anishpatel/Documents/SoftwareProject")
VAULT = ROOT / "ProjectBrain" / "Vault"
DEV_BACKEND = ROOT / "production_backend" / "src"
LIVE_BACKEND = ROOT / "live_backend" / "backend" / "src" / "main" / "java" / "com" / "tradingbot"
FRONTEND = ROOT / "frontend" / "src"

HANDOFF_GATEWAY = VAULT / "Handoff" / "Project Memory Gateway.md"
DTM_GATEWAY = VAULT / "DTM" / "DTM Trade Handling Gateway.md"
NEXT_SPRINT = VAULT / "Sprints" / "NextSprint.md"
HOME = VAULT / "Home.md"

STRATEGY_ATLAS = VAULT / "Strategies" / "Strategy Intelligence Atlas.md"
MODULE_ATLAS = VAULT / "Modules" / "Module Workflow Atlas.md"
HISTORY_ATLAS = VAULT / "History" / "Implementation History Atlas.md"
DIAGNOSTIC_PLAYBOOK = VAULT / "Reports" / "Diagnostics" / "Live Backend Diagnostic Playbook.md"
MARKETDAY_PLAYBOOK = VAULT / "Reports" / "MarketDays" / "Market Day Review Playbook.md"
DECISION_LEDGER = VAULT / "Decisions" / "Decision Ledger.md"

LEGACY_RENAMED_NOTES = [
    VAULT / "Handoff" / ("Implementation" + "Handoff.md"),
    VAULT / "DTM" / ("tradeHandling" + " upgrade.md"),
    VAULT / "Strategies" / ("READ" + "ME.md"),
    VAULT / "Modules" / ("READ" + "ME.md"),
    VAULT / "History" / ("READ" + "ME.md"),
    VAULT / "Reports" / "Diagnostics" / ("READ" + "ME.md"),
    VAULT / "Reports" / "MarketDays" / ("READ" + "ME.md"),
    VAULT / "Decisions" / ("READ" + "ME.md"),
]

STRATEGY_CODES = [
    "ORB", "ORB2", "LORB", "OMOM", "SWEEP", "SWEEP2", "PDB", "VWAP", "VRCL", "MRVWAP",
    "FVG", "CMOM", "AFT", "MIM", "IPB", "KELT", "KREV", "MSCALP", "SHDW", "ECHO",
    "WFT", "TLAD", "RCB", "VPB", "EIA", "COPEN", "IDXCONF", "MYMORB2", "MYMBR", "MCLTC",
]


@dataclass
class StrategyInfo:
    code: str
    name: str
    kind: str
    primary_methods: List[str]
    settings_keywords: List[str]


STRATEGIES: Dict[str, StrategyInfo] = {
    "ORB": StrategyInfo("ORB", "Opening Range Breakout", "direct detector", ["findOrbSignal"], ["orb", "orbBreakout"]),
    "ORB2": StrategyInfo("ORB2", "Opening Range Retest", "direct detector", ["findOrbRetestSignals"], ["orbRetest", "enableOrbRetest"]),
    "LORB": StrategyInfo("LORB", "Late ORB Continuation", "direct detector", ["findLateOrbContinuationSignals"], ["lateOrbContinuation"]),
    "OMOM": StrategyInfo("OMOM", "Compressed Opening Momentum", "direct detector", ["findOpeningMomentumSignals"], ["openingMomentum"]),
    "SWEEP": StrategyInfo("SWEEP", "Prior-Day Liquidity Sweep", "direct detector", ["findSweepSignals"], ["sweep", "enableEarlySweep", "enableLateSweep"]),
    "SWEEP2": StrategyInfo("SWEEP2", "Confirmed Prior-Day Sweep", "direct detector branch", ["findSweepSignals"], ["sweep", "enableSweepSecondChance"]),
    "PDB": StrategyInfo("PDB", "Prior-Day Breakout Retest", "direct detector", ["findPriorDayBreakoutSignals"], ["priorDayBreakout"]),
    "VWAP": StrategyInfo("VWAP", "VWAP Trend Pullback", "direct detector", ["findVwapPullbackSignals"], ["vwapMin", "vwapMax", "vwapRequire"]),
    "VRCL": StrategyInfo("VRCL", "VWAP Reclaim Continuation", "direct detector", ["findVwapReclaimSignals"], ["vwapReclaim"]),
    "MRVWAP": StrategyInfo("MRVWAP", "VWAP Mean Reversion", "direct detector", ["findMeanReversionSignals"], ["meanReversion"]),
    "FVG": StrategyInfo("FVG", "Fair Value Gap Reclaim", "direct detector", ["findFvgSignals"], ["fvg"]),
    "CMOM": StrategyInfo("CMOM", "Close Momentum", "direct detector", ["findCloseMomentumSignals"], ["closeMomentum"]),
    "AFT": StrategyInfo("AFT", "Afternoon Continuation", "direct detector", ["findAfternoonContinuationSignals"], ["afternoon"]),
    "MIM": StrategyInfo("MIM", "Market Intraday Momentum", "direct detector", ["findMarketIntradayMomentumSignals"], ["marketIntradayMomentum"]),
    "IPB": StrategyInfo("IPB", "Opening Impulse Pullback", "direct detector branch", ["findMarketIntradayMomentumSignals"], ["marketImpulsePullback", "marketIntradayMomentum"]),
    "KELT": StrategyInfo("KELT", "Keltner ATR Breakout Scalp", "direct detector", ["findKeltnerScalpSignals"], ["keltner"]),
    "KREV": StrategyInfo("KREV", "Keltner Band Reclaim Reversion", "direct detector", ["findKeltnerReversionSignals"], ["keltner"]),
    "MSCALP": StrategyInfo("MSCALP", "Micro Trend Scalp", "direct detector", ["findMicroScalpSignals"], ["microScalp"]),
    "SHDW": StrategyInfo("SHDW", "Mini-Confirmed Micro Shadow", "source-event strategy", ["addMicroShadowSignalEvents", "addMicroShadowSignalPair", "microShadowTargetConfirms"], ["microShadow"]),
    "ECHO": StrategyInfo("ECHO", "Profit-Buffered Micro Echo", "source-event strategy", ["addMicroEchoSignalEvents", "addMicroEchoSignalPair", "microEchoTargetConfirms"], ["microEcho"]),
    "WFT": StrategyInfo("WFT", "Winner Follow-Through", "source-trade event strategy", ["queueWinnerFollowThroughSignal", "winnerFollowThroughConfirms"], ["winnerFollowThrough"]),
    "TLAD": StrategyInfo("TLAD", "Trend Ladder Pullback", "direct detector", ["findTrendLadderSignals"], ["trendLadder"]),
    "RCB": StrategyInfo("RCB", "Range Compression Breakout", "direct detector", ["findRangeCompressionBreakoutSignals"], ["rangeCompression"]),
    "VPB": StrategyInfo("VPB", "Prior Value Area Reclaim", "direct detector", ["findValueAreaReclaimSignals"], ["valueArea"]),
    "EIA": StrategyInfo("EIA", "MCL EIA Continuation", "direct detector", ["findMclEiaContinuationSignals"], ["mclEia"]),
    "COPEN": StrategyInfo("COPEN", "MCL Crude Session Open", "direct detector", ["findMclCrudeSessionOpenSignals"], ["mclCrudeOpen"]),
    "IDXCONF": StrategyInfo("IDXCONF", "MYM Index Confirmation", "direct detector", ["findMymIndexConfirmationSignals"], ["mymIndexConfirmation"]),
    "MYMORB2": StrategyInfo("MYMORB2", "MYM ORB Retest", "direct detector", ["findMymOrbRetestSignals"], ["mymOrbRetest"]),
    "MYMBR": StrategyInfo("MYMBR", "MYM Breadth Fade", "source-event strategy", ["addMymBreadthConfirmationSignalEvents", "mymBreadthAlignedMarkets", "mymBreadthEvent"], ["mymBreadth", "mymBreadthConfirmation"]),
    "MCLTC": StrategyInfo("MCLTC", "MCL Trend Fade", "direct detector", ["findMclTrendContinuationSignals"], ["mclTrend", "mclTrendContinuation"]),
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def abs_line(path: Path, line: int) -> str:
    return f"{path}:{line}"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def live_backend_source_for(dev_file: Path) -> Path:
    """Resolve a flat dev source file to the read-only live package tree."""
    package_match = re.search(r"^package\s+com\.tradingbot(?:\.([A-Za-z0-9_.]+))?;", read(dev_file), re.MULTILINE)
    if package_match and package_match.group(1):
        return LIVE_BACKEND / Path(package_match.group(1).replace(".", "/")) / dev_file.name
    return LIVE_BACKEND / dev_file.name


def line_number_for(text: str, pattern: str) -> Optional[int]:
    for i, line in enumerate(text.splitlines(), start=1):
        if pattern in line:
            return i
    return None


def java_methods(path: Path) -> Dict[str, Tuple[int, int, str]]:
    lines = read(path).splitlines()
    methods: Dict[str, Tuple[int, int, str]] = {}
    signature_re = re.compile(r"\b(?:public|private|protected)\s+static\s+(?:[\w<>\[\], ?]+)\s+(\w+)\s*\(")
    i = 0
    while i < len(lines):
        match = signature_re.search(lines[i])
        if not match:
            i += 1
            continue
        name = match.group(1)
        start = i
        brace = 0
        seen_open = False
        j = i
        while j < len(lines):
            line = re.sub(r"\"(?:\\.|[^\"])*\"", "\"\"", lines[j])
            brace += line.count("{")
            if "{" in line:
                seen_open = True
            brace -= line.count("}")
            if seen_open and brace == 0:
                break
            j += 1
        body = "\n".join(lines[start : min(j + 1, len(lines))])
        methods[name] = (start + 1, min(j + 1, len(lines)), body)
        i = j + 1
    return methods


def extract_class_range(path: Path, class_name: str) -> Optional[Tuple[int, int, str]]:
    lines = read(path).splitlines()
    start = None
    for i, line in enumerate(lines):
        if f"class {class_name}" in line:
            start = i
            break
    if start is None:
        return None
    brace = 0
    seen_open = False
    for j in range(start, len(lines)):
        stripped = re.sub(r"\"(?:\\.|[^\"])*\"", "\"\"", lines[j])
        brace += stripped.count("{")
        if "{" in stripped:
            seen_open = True
        brace -= stripped.count("}")
        if seen_open and brace == 0:
            return start + 1, j + 1, "\n".join(lines[start : j + 1])
    return start + 1, len(lines), "\n".join(lines[start:])


def clean_slug(text: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "-", text.strip()).strip("-").lower()
    return slug or "note"


def section_by_heading(text: str, heading_regex: str) -> Dict[str, Tuple[int, int, str]]:
    lines = text.splitlines()
    matches: List[Tuple[str, int]] = []
    regex = re.compile(heading_regex)
    for i, line in enumerate(lines):
        m = regex.match(line)
        if m:
            matches.append((m.group(1).strip(), i))
    sections: Dict[str, Tuple[int, int, str]] = {}
    for idx, (title, start) in enumerate(matches):
        end = matches[idx + 1][1] if idx + 1 < len(matches) else len(lines)
        sections[title] = (start + 1, end, "\n".join(lines[start:end]).strip())
    return sections


def extract_strategy_context_from_nextsprint(code: str, next_text: str) -> Dict[str, str]:
    lines = next_text.splitlines()
    context: Dict[str, str] = {}

    # Research/audit section by ### CODE - Title.
    for title, (start, end, body) in section_by_heading(next_text, r"^###\s+(.+)$").items():
        if title.startswith(code + " ") or title == code or title.startswith(code + " -"):
            context["research_section"] = body
            context["research_lines"] = f"{start}-{end}"

    # Strategy trade finding paragraphs in corrected run.
    finding_lines = []
    capture = False
    for i, line in enumerate(lines, start=1):
        if line.startswith(f"- `{code}`:"):
            finding_lines = [line]
            capture = True
            continue
        if capture:
            if line.startswith("- `") or line.startswith("### "):
                break
            finding_lines.append(line)
    if finding_lines:
        context["trade_finding"] = "\n".join(finding_lines).strip()

    # Ranked queue item / detailed implementation item.
    queue_matches = []
    for line in lines:
        if re.search(rf"`{re.escape(code)}`", line) and ("[ ]" in line or "[x]" in line):
            if "No corrected-run trade rows" in line:
                continue
            queue_matches.append(line)
    if queue_matches:
        context["queue_items"] = "\n".join(queue_matches)

    return context


def source_references_for(info: StrategyInfo, fm_methods: Dict[str, Tuple[int, int, str]], fm_path: Path) -> List[str]:
    refs = []
    for method in info.primary_methods:
        if method in fm_methods:
            start, end, _ = fm_methods[method]
            refs.append(f"- `{method}`: `{rel(fm_path)}:{start}-{end}`")
    return refs


def condition_scan(info: StrategyInfo, fm_methods: Dict[str, Tuple[int, int, str]]) -> List[str]:
    rows: List[str] = []
    interesting = list(info.settings_keywords) + [info.code, info.name.split()[0]]
    for method in info.primary_methods:
        if method not in fm_methods:
            continue
        start, _, body = fm_methods[method]
        for offset, raw in enumerate(body.splitlines()):
            line = raw.strip()
            if not line:
                continue
            has_signal = "signal(" in line or "events.add(" in line
            has_condition = line.startswith("if ") or line.startswith("if(") or line.startswith("for ") or "continue;" in line or "return " in line
            has_keyword = any(key and key in line for key in interesting)
            if has_signal or (has_condition and has_keyword):
                cleaned = re.sub(r"\s+", " ", line)
                rows.append(f"- L{start + offset}: `{cleaned}`")
    return rows[:140]


def settings_scan(info: StrategyInfo, fm_text: str) -> List[str]:
    rows = []
    lines = fm_text.splitlines()
    for i, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped.startswith("public "):
            continue
        if any(key in stripped for key in info.settings_keywords):
            rows.append(f"- L{i}: `{stripped}`")
    return rows[:90]


def build_signal_dispatch_section(fm_methods: Dict[str, Tuple[int, int, str]], fm_path: Path) -> str:
    start, end, body = fm_methods.get("buildSignals", (0, 0, ""))
    rows = [f"Primary dispatch: `{rel(fm_path)}:{start}-{end}`", ""]
    for line in body.splitlines():
        stripped = line.strip()
        if "find" in stripped or "settings." in stripped and ".enabled" in stripped:
            rows.append(f"- `{stripped}`")
    return "\n".join(rows)


def build_strategy_notes() -> None:
    fm_path = DEV_BACKEND / "FuturesManager.java"
    fm_text = read(fm_path)
    live_fm_path = LIVE_BACKEND / "FuturesManager.java"
    fm_methods = java_methods(fm_path)
    next_text = read(NEXT_SPRINT)
    dev_live_same = sha(fm_path) == sha(live_fm_path)

    strategy_dir = VAULT / "Strategies"
    index_rows = [
        "# Strategy Intelligence Atlas",
        "",
        "Tags: `strategy-presets`, `strategy-diagnostics`, `backtest`, `live-start`",
        "",
        "This atlas connects each strategy to its detector code, settings surface, backtest/live parity rules, sprint intent, and known report evidence. Start here when doing one-strategy improvement work.",
        "",
        f"Dev/live backend source parity at generation time: `{'MATCH' if dev_live_same else 'DIFF'}` for `FuturesManager.java`.",
        "",
        "## Strategy Notes",
    ]

    dispatch = build_signal_dispatch_section(fm_methods, fm_path)

    for code in STRATEGY_CODES:
        info = STRATEGIES[code]
        context = extract_strategy_context_from_nextsprint(code, next_text)
        refs = source_references_for(info, fm_methods, fm_path)
        conditions = condition_scan(info, fm_methods)
        settings = settings_scan(info, fm_text)
        retrieval_summary = []
        if context.get("queue_items"):
            retrieval_summary.append(context["queue_items"].splitlines()[0])
        if context.get("trade_finding"):
            retrieval_summary.append(context["trade_finding"])
        if context.get("research_section"):
            research_lines = [
                line for line in context["research_section"].splitlines()
                if line.startswith("- Research target:") or line.startswith("- Gap:") or line.startswith("- Decision")
            ]
            retrieval_summary.extend(research_lines[:3])

        note = [
            f"# {code} - {info.name}",
            "",
            f"Tags: `strategy-{code.lower()}`, `strategy-diagnostics`, `backtest`, `live-start`, `backtest-live-integrity`",
            "",
            "## Retrieval Summary",
            "",
            f"Start here when improving `{code}` or editing its detector. This note connects current code, settings, backtest evidence, live parity, and NextSprint upgrade context.",
            "",
            *(retrieval_summary[:4] or ["- No strategy-specific sprint summary was found; inspect source map and NextSprint manually."]),
            "",
            "## Workflow Links",
            "",
            "- [[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
            "- [[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "- [[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
            "- [[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
            "- [[Runtime/DTM And Order Flow|DTM And Order Flow]]",
            "- [[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
            "- [[SourceMaps/FuturesManager Method Map|FuturesManager Method Map]]",
            "- [[Modules/FuturesManager|FuturesManager Module Note]]",
            "- [[Sprints/NextSprint|Next Sprint]]",
            "",
            "## Classification",
            "",
            f"- Strategy code: `{code}`",
            f"- Name: `{info.name}`",
            f"- Type: `{info.kind}`",
            f"- Dev/live backend source parity: `{'MATCH' if dev_live_same else 'DIFF'}` for backend Java source checked during dump.",
            "",
            "## Backtest / Live Integrity Rule",
            "",
            "Backtest and live must use the same strategy rules, detector logic, preset settings, signal timing, and candidate/risk handoff unless a live-only execution layer is explicitly documented. Entry Optimizer and DTM are live execution/management layers and must log their decisions separately from strategy-signal quality.",
            "",
            "## Current Source Map",
            "",
            *(refs or ["- No primary method mapping found. Check source manually."]),
            "",
            "Shared live/backtest dispatch:",
            "",
            dispatch,
            "",
            "Important live pipeline references:",
            "",
            "- `prepareLivePortfolioSignalEvents(...)`: `production_backend/src/FuturesManager.java:16249-16362`",
            "- `runLiveRealtimeCycle(...)` / candidate submit loop: `production_backend/src/FuturesManager.java:14519-15060`",
            "- `validateLivePortfolioSignal(...)`: `production_backend/src/FuturesManager.java:16550-16920`",
            "- `liveEntryTradeReasoningJson(...)`: `production_backend/src/FuturesManager.java:12650-12890`",
            "- Strategy diagnostics targets: `production_backend/src/FuturesManager.java:9707-9760`",
            "",
            "## Current Settings Surface",
            "",
            *(settings or ["- No direct setting fields found by keyword scan; inspect the source map above."]),
            "",
            "## Current Detector / Event Gates From Code",
            "",
            *(conditions or ["- No direct condition scan available; inspect the mapped source method directly."]),
            "",
        ]
        if context.get("research_section"):
            note.extend([
                "## NextSprint Research Section",
                "",
                f"Source: `ProjectBrain/Vault/Sprints/NextSprint.md:{context.get('research_lines')}`",
                "",
                context["research_section"],
                "",
            ])
        if context.get("trade_finding"):
            note.extend([
                "## Corrected Run Trade Finding",
                "",
                context["trade_finding"],
                "",
            ])
        if context.get("queue_items"):
            note.extend([
                "## Improvement Queue Items",
                "",
                context["queue_items"],
                "",
            ])
        note.extend([
            "## Improvement Guardrails",
            "",
            "- Do not add narrow filters that merely mimic the historical backtest window.",
            "- Prefer durable market-structure improvements: context, liquidity, trend, volume, risk geometry, freshness, and invalidation quality.",
            "- Classify losses as false positives only when evidence shows the strategy accepted invalid structure or violated its own rules.",
            "- Every strategy change must state whether live bot parity is preserved and how it was verified.",
        ])
        write(strategy_dir / f"{code}.md", "\n".join(note))
        index_rows.append(f"- [[{code}|{code} - {info.name}]]")

    readme = "\n".join(index_rows) + "\n\n## Shared Dispatch\n\n" + dispatch
    write(STRATEGY_ATLAS, readme)


def build_source_maps() -> None:
    fm_path = DEV_BACKEND / "FuturesManager.java"
    fm_methods = java_methods(fm_path)
    lines = [
        "# FuturesManager Method Map",
        "",
        "Tags: `source-map`, `backend`, `strategy-diagnostics`, `live-start`",
        "",
        "Generated from dev backend source. Dev and live backend Java source hashes are recorded in `Backend Source Parity`.",
        "",
        "## Strategy And Live Methods",
    ]
    interesting = [
        "buildSignals", "findOrbSignal", "findOrbRetestSignals", "findLateOrbContinuationSignals",
        "findOpeningMomentumSignals", "findSweepSignals", "findPriorDayBreakoutSignals",
        "findVwapPullbackSignals", "findVwapReclaimSignals", "findMeanReversionSignals",
        "findFvgSignals", "findCloseMomentumSignals", "findAfternoonContinuationSignals",
        "findMarketIntradayMomentumSignals", "findKeltnerScalpSignals", "findKeltnerReversionSignals",
        "findMicroScalpSignals", "findTrendLadderSignals", "findRangeCompressionBreakoutSignals",
        "findValueAreaReclaimSignals", "findMclEiaContinuationSignals", "findMclCrudeSessionOpenSignals",
        "findMymIndexConfirmationSignals", "findMymOrbRetestSignals", "findMclTrendContinuationSignals",
        "addMymBreadthConfirmationSignalEvents", "addMicroShadowSignalEvents", "addMicroEchoSignalEvents",
        "queueWinnerFollowThroughSignal", "prepareLivePortfolioSignalEvents", "validateLivePortfolioSignal",
        "liveEntryTradeReasoningJson", "runLiveRealtimeCycle", "startLive", "generateFuturesPortfolioBacktest",
    ]
    for name in interesting:
        if name in fm_methods:
            start, end, _ = fm_methods[name]
            lines.append(f"- `{name}`: `production_backend/src/FuturesManager.java:{start}-{end}`")
    write(VAULT / "SourceMaps" / "FuturesManager Method Map.md", "\n".join(lines))

    source_lines = [
        "# Backend Source Parity",
        "",
        "Tags: `source-map`, `live-backend-diagnostic`, `deployment`, `backtest-live-integrity`",
        "",
        "This note records dev backend versus read-only live backend source hashes from the brain dump. `live_backend` was inspected read-only.",
        "",
        "## Hash Comparison",
        "",
    ]
    for dev_file in sorted(DEV_BACKEND.glob("*.java")):
        live_file = live_backend_source_for(dev_file)
        if not live_file.exists():
            source_lines.append(f"- `{dev_file.name}`: live file missing")
            continue
        dev_hash = sha(dev_file)
        live_hash = sha(live_file)
        status = "MATCH" if dev_hash == live_hash else "DIFF"
        source_lines.append(f"- `{dev_file.name}`: `{status}` dev `{dev_hash[:12]}` live `{live_hash[:12]}`")
    source_lines.extend([
        "",
        "## Interpretation",
        "",
        "- A hash match means the checked Java source file is identical in dev and live source trees at generation time.",
        "- This does not prove the running live process has reloaded the source; process/jar runtime state still requires a live diagnostic report.",
        "- Do not edit `live_backend`; use these hashes only for read-only parity evidence.",
    ])
    write(VAULT / "SourceMaps" / "Backend Source Parity.md", "\n".join(source_lines))


def module_workflow_links(module_name: str) -> List[str]:
    common = ["[[SourceMaps/Backend Source Parity|Backend Source Parity]]"]
    mapping = {
        "FuturesManager": [
            "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
            "[[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
            "[[Runtime/DTM And Order Flow|DTM And Order Flow]]",
            "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
            "[[SourceMaps/FuturesManager Method Map|FuturesManager Method Map]]",
        ],
        "MainServer": [
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
            "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
        ],
        "DatabaseManager": [
            "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
            "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
            "[[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
        ],
        "FuturesConnectionManager": [
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Runtime/DTM And Order Flow|DTM And Order Flow]]",
        ],
        "ProjectXRealtimeManager": [
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Reports/Diagnostics/Live Backend Diagnostic Playbook|Live Backend Diagnostic Playbook]]",
        ],
        "LiveRuntimeState": [
            "[[Runtime/DTM And Order Flow|DTM And Order Flow]]",
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
        ],
        "RuntimePaths": [
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Reports/Diagnostics/Live Backend Diagnostic Playbook|Live Backend Diagnostic Playbook]]",
        ],
        "StrategyManager": [
            "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
            "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
        ],
        "LiveBotManager": [
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
        ],
        "AccountManager": [
            "[[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
            "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
        ],
        "AlpacaManager": [
            "[[Decisions/Legacy Alpaca Equity Workflow Removed|Legacy Alpaca Equity Workflow Removed]]",
        ],
    }
    return mapping.get(module_name, []) + common


def module_summary(module_name: str) -> str:
    summaries = {
        "FuturesManager": "Central futures engine: strategy settings/presets, backtests, live candidate generation, diagnostics, live validation/sizing, DTM/order-flow hooks, trade reasoning, and source-event strategies.",
        "MainServer": "HTTP/API surface: routes frontend requests into FuturesManager, including live start/stop, strategy presets, backtests, diagnostics, accounts, and frontend contract payloads.",
        "DatabaseManager": "Database ownership and schema utilities used by backend persistence. Read this before changing stored settings, migrations, or contract fields.",
        "FuturesConnectionManager": "Topstep/ProjectX account, order, position, and realtime connection integration surface.",
        "ProjectXRealtimeManager": "Realtime market-data transport and ProjectX feed handling.",
        "LiveRuntimeState": "In-memory live runtime state used by live broker/order/DTM reconciliation paths.",
        "RuntimePaths": "Runtime path resolution for dev/live file locations and data/cache locations.",
        "StrategyManager": "Legacy equity strategy manager surface; not the current futures strategy preset/live path.",
        "LiveBotManager": "Legacy equity live-bot orchestration surface. Current futures live trading runs through FuturesManager.",
        "AccountManager": "Account/profile management surface.",
        "AlpacaManager": "Legacy Alpaca/equity helper retained only for old stock data/code references. Public Alpaca routes are removed; current live futures trading uses FuturesManager, FuturesConnectionManager, and ProjectX/TopstepX.",
    }
    return summaries.get(module_name, "Backend module. Read linked workflows before editing.")


def build_module_notes() -> None:
    modules_dir = VAULT / "Modules"
    index = [
        "# Module Workflow Atlas",
        "",
        "Tags: `module-map`, `source-map`, `frontend-backend-contract`",
        "",
        "This atlas routes source edits into the exact workflows and notes that explain the module's role in the trading system.",
        "",
        "## Backend Modules",
    ]
    for dev_file in sorted(DEV_BACKEND.glob("*.java")):
        module_name = dev_file.stem
        live_file = live_backend_source_for(dev_file)
        status = "MATCH" if live_file.exists() and sha(dev_file) == sha(live_file) else "DIFF_OR_MISSING"
        methods = java_methods(dev_file)
        method_rows = []
        for name, (start, end, _) in sorted(methods.items(), key=lambda item: item[1][0])[:120]:
            method_rows.append(f"- `{name}`: `production_backend/src/{dev_file.name}:{start}-{end}`")
        links = module_workflow_links(module_name)
        note = [
            f"# {module_name}",
            "",
            f"Tags: `module-map`, `backend`, `{module_name.lower()}`",
            "",
            "## Editing Workflow",
            "",
            f"If editing `{rel(dev_file)}`, read these notes first:",
            "",
            *(f"- {link}" for link in links),
            "",
            "## Role",
            "",
            module_summary(module_name),
            "",
            "## Dev / Live Source Parity",
            "",
            f"- Status: `{status}`",
            f"- Dev source: `{rel(dev_file)}`",
            f"- Live source inspected read-only: `{rel(live_file)}`" if live_file.exists() else "- Live source missing.",
            "",
            "## Method Map",
            "",
            *(method_rows or ["- No static/public/private method map generated."]),
        ]
        write(modules_dir / f"{module_name}.md", "\n".join(note))
        index.append(f"- [[{module_name}|{module_name}]]")

    index.extend([
        "",
        "## Frontend Modules",
        "",
        "- [[FuturesLive Frontend|FuturesLive.jsx]]",
        "- [[FuturesBacktest Frontend|FuturesBacktest.jsx]]",
        "- [[FuturesStrategy Frontend|FuturesStrategy.jsx]]",
        "- [[Documents Frontend|Documents.jsx]]",
    ])

    frontend_notes = {
        "FuturesLive Frontend": (
            FRONTEND / "pages" / "FuturesLive.jsx",
            "Live Futures UI: live start payload, account/strategy/risk controls, live logs, market monitor, trade tables, chart merge/cache, and live trade-cache display.",
            [
                "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
                "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
                "[[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
                "[[Runtime/DTM And Order Flow|DTM And Order Flow]]",
                "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
                "[[Reports/Diagnostics/Live Backend Diagnostic Playbook|Live Backend Diagnostic Playbook]]",
                "[[Reports/MarketDays/Market Day Review Playbook|Market Day Review Playbook]]",
            ],
        ),
        "FuturesBacktest Frontend": (
            FRONTEND / "pages" / "FuturesBacktest.jsx",
            "Backtest UI: portfolio generation payload, Strategy Config selection, risk/account inputs, and backtest data update controls.",
            [
                "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
                "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
                "[[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
                "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
                "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
            ],
        ),
        "FuturesStrategy Frontend": (
            FRONTEND / "pages" / "FuturesStrategy.jsx",
            "Strategy Config editor: preset/symbol settings, strategy toggles, detector fields, save restrictions, and frontend/backend field contract.",
            [
                "[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
                "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
                "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
                "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
            ],
        ),
        "Documents Frontend": (
            FRONTEND / "pages" / "Documents.jsx",
            "Frontend strategy documentation page. Keep this aligned with backend strategy codes and Strategy Memory notes.",
            [
                "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
                "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
                "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
            ],
        ),
    }
    for title, (path, role, links) in frontend_notes.items():
        note = [
            f"# {title}",
            "",
            "Tags: `module-map`, `frontend`, `frontend-backend-contract`",
            "",
            "## Editing Workflow",
            "",
            f"If editing `{rel(path)}`, read these notes first:",
            "",
            *(f"- {link}" for link in links),
            "",
            "## Role",
            "",
            role,
            "",
            "## Source",
            "",
            f"- `{rel(path)}`",
        ]
        write(modules_dir / f"{title}.md", "\n".join(note))
    write(MODULE_ATLAS, "\n".join(index))


def build_runtime_notes() -> None:
    fm_path = DEV_BACKEND / "FuturesManager.java"
    main_path = DEV_BACKEND / "MainServer.java"
    fm_text = read(fm_path)
    main_text = read(main_path)
    fm_methods = java_methods(fm_path)
    main_methods = java_methods(main_path)

    runtime = VAULT / "Runtime"
    write(runtime / "Backtest Live Integrity.md", "\n".join([
        "# Backtest Live Integrity",
        "",
        "Tags: `backtest-live-integrity`, `backtest`, `live-start`, `strategy-diagnostics`",
        "",
        "Backtest and live trading must use the same strategy rules, detector logic, preset settings, signal timing, and candidate/risk handoff unless a live-only execution layer is explicitly documented.",
        "",
        "## Current Code Evidence",
        "",
        "- Backtest signal generation dispatches through `buildSignals(...)`: `production_backend/src/FuturesManager.java:20736-20825`.",
        "- Live realtime candidates call `prepareLivePortfolioSignalEvents(...)`, which calls the same detector stack and shifts detector signals to next-bar execution: `production_backend/src/FuturesManager.java:16249-16362`.",
        "- Live candidate validation then runs `validateLivePortfolioSignal(...)`: `production_backend/src/FuturesManager.java:16550-16920`.",
        "- Live start receives selected account, strategy preset, risk config, DTM, and entry optimizer state through `MainServer.startLiveTrading(...)` and `FuturesManager.startLive(...)`.",
        "",
        "## Live-Only Layers",
        "",
        "- Entry Optimizer may pass/block structurally valid candidates using order-flow/depth context.",
        "- DTM may manage open positions after entry.",
        "- Both layers must log decisions so market-day reports can separate strategy-signal quality from live execution/management effects.",
    ]))

    write(runtime / "Live Bot Pipeline.md", "\n".join([
        "# Live Bot Pipeline",
        "",
        "Tags: `live-start`, `candidate-generation`, `risk-config`, `broker-reconcile`, `ledger-trade-cache`",
        "",
        "## Intended Flow",
        "",
        "`Strategy Config preset -> candidate generation -> Risk Config sizing/guards -> duplicate/exposure checks -> broker submit/manage -> ledger/trade cache -> UI logs`",
        "",
        "## Key Source References",
        "",
        "- Live start endpoint: `production_backend/src/FuturesLiveRoutes.java` (`startLiveTrading`).",
        "- Live session start: `production_backend/src/FuturesManager.java` (`startLive`).",
        "- Realtime live scan/candidate loop: `production_backend/src/FuturesManager.java:14519-15060`.",
        "- Candidate event preparation: `prepareLivePortfolioSignalEvents(...)`.",
        "- Candidate validation/sizing: `validateLivePortfolioSignal(...)`.",
        "- Reason JSON: `liveEntryTradeReasoningJson(...)`.",
    ]))

    write(runtime / "Strategy Preset Workflow.md", "\n".join([
        "# Strategy Preset Workflow",
        "",
        "Tags: `strategy-presets`, `frontend-backend-contract`, `backtest-live-integrity`",
        "",
        "## Current Presets From Source",
        "",
        "- `backtestbias92k`: frozen/windowed control.",
        "- `biasfree92k`: broad bias-free comparison preset.",
        "- `bestbiasfree`: optimized bias-free preset replacing older all-enabled naming.",
        "",
        "## Key Source References",
        "",
        "- Preset constants and policy: `production_backend/src/FuturesManager.java:95-110`.",
        "- Preset seeding/policy application: `production_backend/src/FuturesManager.java:1338-1385`.",
        "- Preset API JSON: `getStrategyPresetsJson(...)`.",
        "- Backtest UI sends `strategyPreset`: `frontend/src/pages/FuturesBacktest.jsx`.",
        "- Live UI sends `strategyPreset`: `frontend/src/pages/FuturesLive.jsx`.",
        "",
        "## Integrity Rule",
        "",
        "Backtest generation and live start must both use the selected Strategy Config preset directly. Do not revive copy-to-live as the strategy source.",
    ]))

    write(runtime / "Risk Sizing And Guards.md", "\n".join([
        "# Risk Sizing And Guards",
        "",
        "Tags: `risk-config`, `candidate-generation`, `broker-reconcile`, `live-start`",
        "",
        "Risk Config is separate from Strategy Config and selected Topstep account. Risk controls the live envelope after strategy candidate generation.",
        "",
        "## Key Live Validation Gates",
        "",
        "- Broker exposure and stale reconcile checks.",
        "- Executable price and decayed reward/risk checks.",
        "- Duplicate/open position collision checks.",
        "- Correlated-family exposure checks.",
        "- Max positions, contracts, aggregate mini units, per-strategy daily limits.",
        "- Daily loss and trailing drawdown budget checks.",
        "- Risk-budget sizing and rejection diagnostics.",
        "",
        "Primary source: `validateLivePortfolioSignal(...)` in `FuturesManager.java`.",
    ]))

    write(runtime / "DTM And Order Flow.md", "\n".join([
        "# DTM And Order Flow",
        "",
        "Tags: `dtm`, `order-flow`, `live-start`, `market-day-report`",
        "",
        "Entry Optimizer and DTM are live execution/management layers. They must not obscure whether the strategy engine produced a valid market-structure signal.",
        "",
        "## DTM Roles",
        "",
        "- Hydrate actual broker fills for open-position management.",
        "- Track MFE/MAE using intrabar high/low extremes.",
        "- Move stop to breakeven, trail, partial close, target-extend, hold runner, or cut early when rules support it.",
        "- Log actual DTM actions. Do not add noisy user-facing no-activity logs.",
        "",
        "## Order Flow Roles",
        "",
        "- Pre-entry Level 2/order-flow optimizer can pass or block live candidates.",
        "- Missing/stale depth should fall back according to configured behavior rather than silently killing all trading.",
        "- Market-day reports must attribute optimizer/DTM effects separately from strategy-signal quality.",
    ]))

    write(runtime / "Frontend Backend Contract.md", "\n".join([
        "# Frontend Backend Contract",
        "",
        "Tags: `frontend-backend-contract`, `strategy-presets`, `risk-config`, `live-start`",
        "",
        "Frontend CRUD fields must map to the exact backend field that stores and serves them. Avoid duplicate config names, parallel payload shapes, or silent legacy aliases.",
        "",
        "## Critical Surfaces",
        "",
        "- Strategy Config dropdowns in Backtest, Strategy, and Live pages.",
        "- Risk Config dropdown on Live page.",
        "- Live start payload: account, strategy preset, risk config/profile, symbols, DTM, entry optimizer.",
        "- Trade reason JSON flowing from backend decisions into Live Trade Tables and log drawer.",
        "- Local browser trade-cache merge is fallback; backend live trade cache remains authoritative.",
    ]))


def build_history_notes() -> None:
    history_dir = VAULT / "History"
    history_dir.mkdir(parents=True, exist_ok=True)

    source_path = HANDOFF_GATEWAY if HANDOFF_GATEWAY.exists() else VAULT / "Handoff" / ("Implementation" + "Handoff.md")
    if source_path.exists():
        handoff_text = read(source_path)
        sections = section_by_heading(handoff_text, r"^###\s+(.+)$")
    else:
        handoff_text = ""
        sections = {}

    # Only extract from a historical blob. Once the handoff is a compact gateway,
    # keep the existing split history notes and regenerate the atlas from them.
    if sections and "This file is now a compact" not in handoff_text:
        for title, (start, end, body) in sections.items():
            slug = clean_slug(title)
            path = history_dir / f"{slug}.md"
            write(path, "\n".join([
                f"# {title}",
                "",
                "Tags: `history`, `implementation-handoff`",
                "",
                f"Source: `{rel(source_path)}:{start}-{end}`",
                "",
                body,
            ]))

    index = [
        "# Implementation History Atlas",
        "",
        "Tags: `history`, `implementation-handoff`, `deployment`",
        "",
        "This atlas routes old implementation context into focused notes. Use it when a future task needs to understand why a workflow, deployment rule, trade-log behavior, or runtime boundary exists.",
        "",
    ]
    for path in sorted(history_dir.glob("*.md")):
        if path.name in {HISTORY_ATLAS.name, "READ" + "ME.md"}:
            continue
        first_line = read(path).splitlines()[0] if path.exists() else path.stem
        title = first_line.lstrip("# ").strip() or path.stem
        index.append(f"- [[{path.stem}|{title}]]")
    write(HISTORY_ATLAS, "\n".join(index))


def build_dtm_notes() -> None:
    source_path = DTM_GATEWAY if DTM_GATEWAY.exists() else VAULT / "DTM" / ("tradeHandling" + " upgrade.md")
    detailed_path = VAULT / "DTM" / "2026-05-27 Trade Handling Upgrade.md"
    report_path = VAULT / "Reports" / "MarketDays" / "2026-05-26 Live DTM Trade Review.md"
    if source_path.exists():
        text = read(source_path)
    elif detailed_path.exists():
        text = read(detailed_path)
    else:
        text = ""
    using_existing_detail = False
    if (("This file is now a compact pointer" in text) or ("This gateway replaces" in text)) and detailed_path.exists():
        existing = read(detailed_path)
        if "## Exact Trade Evidence" in existing or "## Root Findings" in existing:
            text = existing
            using_existing_detail = True
    if using_existing_detail:
        write(detailed_path, text)
    else:
        write(detailed_path, "\n".join([
            "# 2026-05-27 Trade Handling Upgrade",
            "",
            "Tags: `dtm`, `broker-reconcile`, `ledger-trade-cache`, `market-day-report`",
            "",
            "This note preserves the detailed DTM/trade-handling upgrade context that used to live in the old compact trade-handling note.",
            "",
            text,
        ]))

    # Extract exact trade sections into a market-day style report.
    exact_start = text.find("## Exact Trade Evidence")
    implementation_start = text.find("## Implementation Plan")
    if implementation_start < 0:
        implementation_start = text.find("## Dev Source Changes")
    if exact_start < 0 and report_path.exists() and "This gateway replaces" not in read(report_path):
        return
    exact_body = text[exact_start:implementation_start].strip() if exact_start >= 0 and implementation_start > exact_start else text
    write(report_path, "\n".join([
        "# 2026-05-26 Live DTM Trade Review",
        "",
        "Tags: `market-day-report`, `dtm`, `broker-reconcile`, `ledger-trade-cache`",
        "",
        "This report was extracted from the DTM/trade-handling upgrade note so future market-day work can retrieve the exact trade evidence directly.",
        "",
        exact_body,
    ]))


def slim_legacy_notes() -> None:
    write(HANDOFF_GATEWAY, "\n".join([
        "# Project Memory Gateway",
        "",
        "Last updated: 2026-05-28, America/New_York",
        "",
        "This gateway replaces the old implementation handoff blob. It routes each chat into the focused ProjectBrain notes that explain the exact subsystem, strategy, workflow, or historical decision needed for work.",
        "",
        "## Start Here",
        "",
        "- [[Home|Project Brain Home]]",
        "- [[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
        "- [[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
        "- [[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]",
        "- [[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]",
        "- [[Runtime/DTM And Order Flow|DTM And Order Flow]]",
        "- [[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
        "- [[SourceMaps/Backend Source Parity|Backend Source Parity]]",
        "- [[SourceMaps/FuturesManager Method Map|FuturesManager Method Map]]",
        "- [[Modules/Module Workflow Atlas|Module Workflow Atlas]]",
        "- [[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
        "- [[History/Implementation History Atlas|Implementation History Atlas]]",
        "- [[Sprints/NextSprint|Next Sprint]]",
        "",
        "## Non-Negotiable Current Boundaries",
        "",
        "- Active local verification apps: `/Users/anishpatel/Documents/SoftwareProject/production_backend` and `/Users/anishpatel/Documents/SoftwareProject/frontend`.",
        "- Read-only live runtime: `/Users/anishpatel/Documents/SoftwareProject/live_backend`.",
        "- Do not hand-edit `live_backend`.",
        "- Backtest/live strategy integrity is mandatory.",
        "- Strategy improvements must generalize by market structure, not curve-fit a historical window.",
        "- Secrets must never be stored in tracked docs or RAG notes.",
    ]))

    write(DTM_GATEWAY, "\n".join([
        "# DTM Trade Handling Gateway",
        "",
        "This gateway replaces the old trade-handling upgrade scratch file. It routes DTM, broker reconciliation, ledger/trade-cache, and market-day review work into the focused notes below.",
        "",
        "- [[2026-05-27 Trade Handling Upgrade]]",
        "- [[Reports/MarketDays/2026-05-26 Live DTM Trade Review|2026-05-26 Live DTM Trade Review]]",
        "- [[Runtime/DTM And Order Flow|DTM And Order Flow]]",
        "",
        "## Retrieval Bridges",
        "",
        "- `PENDING_BROKER_RECONCILE`, MGC ORB, MGC OMOM, KREV stale entry, NQ/MNQ correlated exposure, and one-contract DTM target-extension findings live in [[Reports/MarketDays/2026-05-26 Live DTM Trade Review|2026-05-26 Live DTM Trade Review]].",
        "- Broker-fill hydration, intrabar MFE/MAE, target extension, executable entry checks, correlated-family guards, and live monitor candle fixes live in [[2026-05-27 Trade Handling Upgrade]].",
        "",
        "Keep future DTM architecture notes under `ProjectBrain/Vault/DTM/` and daily trade reviews under `ProjectBrain/Vault/Reports/MarketDays/`.",
    ]))


def build_collection_guides() -> None:
    decision_links = []
    for path in sorted((VAULT / "Decisions").glob("*.md")):
        if path.name == DECISION_LEDGER.name:
            continue
        title = read(path).splitlines()[0].lstrip("# ").strip() if path.exists() else path.stem
        decision_links.append(f"- [[{path.stem}|{title}]]")

    write(DECISION_LEDGER, "\n".join([
        "# Decision Ledger",
        "",
        "Tags: `decisions`, `project-memory`, `rejected-hypothesis`",
        "",
        "Use this ledger when a chat produces a durable project decision, a rejected hypothesis future chats should not repeat, or a confirmed behavior rule that changes how work should be done.",
        "",
        "Each decision note should include:",
        "",
        "- Date.",
        "- Decision or rejected hypothesis.",
        "- Context and evidence.",
        "- Scope of impact.",
        "- Follow-up work, if any.",
        "",
        "Suggested tags: `deployment`, `strategy-presets`, `risk-config`, `frontend-backend-contract`, `backtest`.",
        "",
        "## Decision Notes",
        "",
        *(decision_links or ["- No decision notes found yet."]),
        "",
        "## Workflow Links",
        "",
        "- [[Home|Project Brain Home]]",
        "- [[Handoff/Project Memory Gateway|Project Memory Gateway]]",
        "- [[Templates/Decision Note Template|Decision Note Template]]",
    ]))

    write(DIAGNOSTIC_PLAYBOOK, "\n".join([
        "# Live Backend Diagnostic Playbook",
        "",
        "Tags: `live-backend-diagnostic`, `market-data`, `strategy-diagnostics`, `risk-config`, `broker-reconcile`, `dtm`, `frontend-backend-contract`",
        "",
        "Use this playbook for read-only diagnostics on the running live backend. Its job is to prove whether no trades or unexpected behavior came from valid selectivity, market data gaps, config mismatch, risk rejection, broker/reconcile state, DTM/order-flow gates, or a real logic defect.",
        "",
        "Every diagnostic report should include:",
        "",
        "- Date and time range inspected.",
        "- Live runtime health and selected account/config state.",
        "- Market data, strategy generation, risk sizing, broker, DTM, and frontend/backend contract findings.",
        "- Evidence used, without secrets.",
        "- Conclusion on whether no-trade behavior was valid selectivity or a defect.",
        "- Dev-first implementation plan for confirmed defects.",
    ]))

    write(MARKETDAY_PLAYBOOK, "\n".join([
        "# Market Day Review Playbook",
        "",
        "Tags: `market-day-report`, `strategy-presets`, `risk-config`, `dtm`, `broker-reconcile`, `ledger-trade-cache`, `market-data`",
        "",
        "Use this playbook to reconstruct a trading day from live trades, candles, broker fills, logs, signal decisions, DTM actions, and config state.",
        "",
        "Every market-day report should include:",
        "",
        "- Check all of the trades commited during the day, and analayze weather the strategy engine actually follwoed correct marketstructure or was it a flase signal and from there determine weather it was out logic's fault and it was a false positive. or the market structure supported our trade and this was just unlucky and no change needs to be made to the strategy logic, our logic is following the strategy rules accurately.",
        "- Date, account, symbols, Strategy Config, Risk Config, DTM state, and entry optimizer state.",
        "- Trade inventory with fills, planned levels, actual exits, PnL, MFE, MAE, and exit reason.",
        "- Market-structure review for each trade.",
        "- Entry, exit, DTM, risk, and broker handling review.",
        "- Evidence used, without secrets.",
        "- Confirmed issues, rejected hypotheses, projected impact, and dev-first implementation plan.",
    ]))


def replace_section(text: str, heading: str, lines: List[str]) -> str:
    pattern = re.compile(rf"\n## {re.escape(heading)}\n.*?(?=\n## |\Z)", re.DOTALL)
    section = "\n## " + heading + "\n\n" + "\n".join(lines).rstrip() + "\n"
    if pattern.search(text):
        return pattern.sub(section.rstrip(), text).rstrip() + "\n"
    return text.rstrip() + section


def add_graph_links(path: Path, links: Iterable[str]) -> None:
    unique: List[str] = []
    for link in links:
        if link not in unique:
            unique.append(link)
    if not unique or not path.exists():
        return
    text = read(path)
    lines = [f"- {link}" for link in unique]
    write(path, replace_section(text, "Graph Links", lines))


def history_workflow_links(path: Path) -> List[str]:
    text = read(path).lower()
    slug = path.stem.lower()
    links = [
        "[[History/Implementation History Atlas|Implementation History Atlas]]",
        "[[Handoff/Project Memory Gateway|Project Memory Gateway]]",
    ]
    if "dtm" in text or "order-flow" in text or "broker" in text or "reconcile" in text:
        links.extend([
            "[[Runtime/DTM And Order Flow|DTM And Order Flow]]",
            "[[DTM/DTM Trade Handling Gateway|DTM Trade Handling Gateway]]",
            "[[Reports/MarketDays/Market Day Review Playbook|Market Day Review Playbook]]",
        ])
    if "live" in text or "runtime" in text or "session" in text:
        links.extend([
            "[[Runtime/Live Bot Pipeline|Live Bot Pipeline]]",
            "[[Reports/Diagnostics/Live Backend Diagnostic Playbook|Live Backend Diagnostic Playbook]]",
        ])
    if "risk" in text or "topstep" in text or "drawdown" in text or "account" in text:
        links.append("[[Runtime/Risk Sizing And Guards|Risk Sizing And Guards]]")
    if "preset" in text or "strategy config" in text:
        links.append("[[Runtime/Strategy Preset Workflow|Strategy Preset Workflow]]")
    if "backtest" in text or "strategy" in text:
        links.extend([
            "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
            "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
        ])
    if "frontend" in text or "chart" in text or "ui" in text or "log" in slug or "cards" in slug or "reason" in slug:
        links.extend([
            "[[Runtime/Frontend Backend Contract|Frontend Backend Contract]]",
            "[[Modules/FuturesLive Frontend|FuturesLive Frontend]]",
        ])
    return links


def decision_workflow_links(path: Path) -> List[str]:
    text = read(path).lower()
    links = [
        "[[Decisions/Decision Ledger|Decision Ledger]]",
        "[[Home|Project Brain Home]]",
    ]
    if "rag" in text or "brain" in text or "memory" in text or "vault" in text:
        links.extend([
            "[[Maps/RAG Workflow|RAG Workflow]]",
            "[[Maps/Memory Architecture|Memory Architecture]]",
            "[[Handoff/Project Memory Gateway|Project Memory Gateway]]",
        ])
    if "agents" in text or "operating manual" in text:
        links.append("[[Handoff/Project Memory Gateway|Project Memory Gateway]]")
    if "backtest" in text or "strategy" in text:
        links.extend([
            "[[Runtime/Backtest Live Integrity|Backtest Live Integrity]]",
            "[[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]",
        ])
    if "module" in text:
        links.append("[[Modules/Module Workflow Atlas|Module Workflow Atlas]]")
    return links


def sync_graph_links() -> None:
    for path in sorted((VAULT / "History").glob("*.md")):
        if path.name == HISTORY_ATLAS.name:
            continue
        add_graph_links(path, history_workflow_links(path))

    for path in sorted((VAULT / "Decisions").glob("*.md")):
        if path.name == DECISION_LEDGER.name:
            continue
        add_graph_links(path, decision_workflow_links(path))

    add_graph_links(VAULT / "Maps" / "Memory Architecture.md", [
        "[[Home|Project Brain Home]]",
        "[[Handoff/Project Memory Gateway|Project Memory Gateway]]",
        "[[Maps/RAG Workflow|RAG Workflow]]",
        "[[Decisions/Decision Ledger|Decision Ledger]]",
    ])
    add_graph_links(VAULT / "Maps" / "RAG Workflow.md", [
        "[[Home|Project Brain Home]]",
        "[[Maps/Memory Architecture|Memory Architecture]]",
        "[[Handoff/Project Memory Gateway|Project Memory Gateway]]",
        "[[Decisions/Decision Ledger|Decision Ledger]]",
    ])
    add_graph_links(VAULT / "Templates" / "Decision Note Template.md", [
        "[[Decisions/Decision Ledger|Decision Ledger]]",
        "[[Home|Project Brain Home]]",
    ])


def update_home() -> None:
    text = read(HOME)
    replacement = """# Project Brain

This vault is the human-readable memory layer for the trading console project. It is designed for Obsidian-style navigation and for the local RAG index to retrieve project context.

## Core Notes

- [[Handoff/Project Memory Gateway|Project Memory Gateway]]
- [[Strategies/Strategy Intelligence Atlas|Strategy Intelligence Atlas]]
- [[Runtime/Backtest Live Integrity|Backtest Live Integrity]]
- [[Sprints/NextSprint|Next Sprint]]
- [[DTM/2026-05-27 Trade Handling Upgrade|DTM Trade Handling Upgrade]]

## Runtime

- [[Runtime/Live Bot Pipeline]]
- [[Runtime/Strategy Preset Workflow]]
- [[Runtime/Risk Sizing And Guards]]
- [[Runtime/DTM And Order Flow]]
- [[Runtime/Frontend Backend Contract]]

## Source Maps

- [[SourceMaps/Backend Source Parity]]
- [[SourceMaps/FuturesManager Method Map]]

## Module Workflows

- [[Modules/Module Workflow Atlas|Module Workflow Atlas]]

## Reports And Decisions

- [[Reports/Diagnostics/Live Backend Diagnostic Playbook|Live Backend Diagnostic Playbook]]
- [[Reports/MarketDays/Market Day Review Playbook|Market Day Review Playbook]]
- [[Decisions/Decision Ledger|Decision Ledger]]
- [[History/Implementation History Atlas|Implementation History Atlas]]

## Knowledge Areas

- [[Concepts/Live Bot Flow]]
- [[Concepts/Strategy Presets]]
- [[Concepts/Risk Configs]]
- [[Concepts/DTM Trade Management]]

## Rule

The vault is durable memory. Generated indexes under `ProjectBrain/RAG` are only rebuildable caches.
"""
    write(HOME, replacement)


def remove_legacy_note_names() -> None:
    for path in LEGACY_RENAMED_NOTES:
        if path.exists():
            path.unlink()


def main() -> int:
    build_strategy_notes()
    build_source_maps()
    build_module_notes()
    build_runtime_notes()
    build_history_notes()
    build_dtm_notes()
    slim_legacy_notes()
    build_collection_guides()
    sync_graph_links()
    update_home()
    remove_legacy_note_names()
    print("Built structured ProjectBrain dump.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
