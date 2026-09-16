#!/usr/bin/env python3
"""Evaluate whether local RAG retrieval finds expected project memory files."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List

from rag_lib import DEFAULT_CONFIG, config_root, load_config
from query_project import run_query


DEFAULT_CASES = Path(__file__).resolve().parents[1] / "evals" / "rag_retrieval_cases.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run lightweight local RAG retrieval evals.")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    parser.add_argument("--cases", default=str(DEFAULT_CASES))
    parser.add_argument("--top-k", type=int, default=8)
    parser.add_argument("--json", action="store_true", help="Emit JSON results.")
    return parser.parse_args()


def load_cases(path: Path) -> List[Dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError(f"Expected a list of cases in {path}")
    return payload


def matching_rank(
    results: List[Dict],
    expected_paths: List[str],
    expected_path_prefixes: List[str],
) -> int:
    for rank, item in enumerate(results, start=1):
        path = item["path"]
        if any(path == expected or path.endswith(expected) for expected in expected_paths):
            return rank
        if any(path.startswith(prefix) for prefix in expected_path_prefixes):
            return rank
    return 0


def missing_required_paths(results: List[Dict], required_paths: List[str]) -> List[str]:
    top_paths = [item["path"] for item in results]
    missing = []
    for expected in required_paths:
        if not any(path == expected or path.endswith(expected) for path in top_paths):
            missing.append(expected)
    return missing


def path_matches(path: str, expected: str) -> bool:
    return path == expected or path.endswith(expected)


def present_forbidden_paths(
    results: List[Dict],
    forbidden_paths: List[str],
    forbidden_path_prefixes: List[str],
) -> List[str]:
    bad: List[str] = []
    for item in results:
        path = item["path"]
        if any(path_matches(path, forbidden) for forbidden in forbidden_paths):
            bad.append(path)
        elif any(path.startswith(prefix) for prefix in forbidden_path_prefixes):
            bad.append(path)
    return sorted(set(bad))


def missing_required_artifact_types(results: List[Dict], required_artifact_types: List[str]) -> List[str]:
    present = {str(item.get("artifact_type", "")) for item in results}
    return [artifact for artifact in required_artifact_types if artifact not in present]


def present_forbidden_artifact_types(results: List[Dict], forbidden_artifact_types: List[str]) -> List[str]:
    forbidden = set(forbidden_artifact_types)
    present = {str(item.get("artifact_type", "")) for item in results if str(item.get("artifact_type", "")) in forbidden}
    return sorted(present)


def scope_results(results: List[Dict], top_n: int | None) -> List[Dict]:
    if top_n is None or top_n <= 0:
        return results
    return results[:top_n]


def missing_required_paths_top_n(results: List[Dict], spec: Dict) -> List[str]:
    top = scope_results(results, int(spec.get("top_n", 0)))
    return missing_required_paths(top, [str(path) for path in spec.get("paths", [])])


def missing_required_artifact_types_top_n(results: List[Dict], spec: Dict) -> List[str]:
    top = scope_results(results, int(spec.get("top_n", 0)))
    return missing_required_artifact_types(top, [str(value) for value in spec.get("artifact_types", [])])


def present_forbidden_artifact_types_top_n(results: List[Dict], spec: Dict) -> List[str]:
    top = scope_results(results, int(spec.get("top_n", 0)))
    return present_forbidden_artifact_types(top, [str(value) for value in spec.get("artifact_types", [])])


def max_file_count_violation(results: List[Dict], max_per_file: int) -> Dict[str, int]:
    if max_per_file <= 0:
        return {}
    counts: Dict[str, int] = {}
    for item in results:
        counts[item["path"]] = counts.get(item["path"], 0) + 1
    return {path: count for path, count in counts.items() if count > max_per_file}


def missing_required_content(results: List[Dict], required_content: List[Dict]) -> List[Dict]:
    missing = []
    for check in required_content:
        expected_path = str(check.get("path", ""))
        contains = [str(term).lower() for term in check.get("contains", [])]
        if not expected_path or not contains:
            missing.append(check)
            continue
        matching_text: List[str] = []
        for item in results:
            if not path_matches(item["path"], expected_path):
                continue
            matching_text.append(
                " ".join(
                    [
                        str(item.get("text", "")),
                        str(item.get("context_header", "")),
                        str(item.get("metadata_json", "")),
                    ]
                )
            )
        haystack = " ".join(matching_text).lower()
        found = bool(matching_text) and all(term in haystack for term in contains)
        if not found:
            missing.append(check)
    return missing


def missing_exclusive_content(results: List[Dict], exclusive_content: List[Dict]) -> List[Dict]:
    """Require all terms to appear in one retrieved chunk.

    Most source checks should use required_content, which aggregates by file.
    This stricter form is available for cases where one chunk must stand alone.
    """
    missing = []
    for check in exclusive_content:
        expected_path = str(check.get("path", ""))
        contains = [str(term).lower() for term in check.get("contains", [])]
        if not expected_path or not contains:
            missing.append(check)
            continue
        found = False
        for item in results:
            if not path_matches(item["path"], expected_path):
                continue
            haystack = (
                " ".join(
                    [
                        str(item.get("text", "")),
                        str(item.get("context_header", "")),
                        str(item.get("metadata_json", "")),
                    ]
                )
                .lower()
            )
            if all(term in haystack for term in contains):
                found = True
                break
        if not found:
            missing.append(check)
    return missing


def main() -> int:
    args = parse_args()
    config = load_config(Path(args.config))
    root = config_root(config)
    cases = load_cases(Path(args.cases))

    outcomes = []
    for case in cases:
        query = str(case.get("query", "")).strip()
        expected_paths = [str(path) for path in case.get("expected_paths", [])]
        expected_path_prefixes = [
            str(path) for path in case.get("expected_path_prefixes", [])
        ]
        required_paths = [str(path) for path in case.get("required_paths", [])]
        forbidden_paths = [str(path) for path in case.get("forbidden_paths", [])]
        forbidden_path_prefixes = [str(path) for path in case.get("forbidden_path_prefixes", [])]
        required_artifact_types = [str(value) for value in case.get("required_artifact_types", [])]
        forbidden_artifact_types = [str(value) for value in case.get("forbidden_artifact_types", [])]
        required_paths_top_n = dict(case.get("required_paths_top_n", {}))
        required_artifact_types_top_n = dict(case.get("required_artifact_types_top_n", {}))
        forbidden_artifact_types_top_n = dict(case.get("forbidden_artifact_types_top_n", {}))
        max_per_file = int(case.get("max_per_file", 0) or 0)
        max_rank = int(case.get("max_rank", 0) or 0)
        required_content = list(case.get("required_content", []))
        exclusive_content = list(case.get("exclusive_content", []))
        context_pack = bool(case.get("context_pack", False))
        top_k = int(case.get("top_k", args.top_k))
        if not query or not (
            expected_paths
            or expected_path_prefixes
            or required_paths
            or forbidden_paths
            or forbidden_path_prefixes
            or required_artifact_types
            or forbidden_artifact_types
            or required_paths_top_n
            or required_artifact_types_top_n
            or forbidden_artifact_types_top_n
            or max_per_file
            or max_rank
            or required_content
            or exclusive_content
        ):
            outcomes.append(
                {
                    "name": case.get("name", "unnamed"),
                    "passed": False,
                    "reason": "case must include query and expected paths or prefixes",
                }
            )
            continue

        results = run_query(config, query, top_k, context_pack=context_pack)
        rank = matching_rank(results, expected_paths, expected_path_prefixes)
        missing = missing_required_paths(results, required_paths)
        missing_top_paths = missing_required_paths_top_n(results, required_paths_top_n)
        forbidden_present = present_forbidden_paths(results, forbidden_paths, forbidden_path_prefixes)
        missing_artifacts = missing_required_artifact_types(results, required_artifact_types)
        missing_top_artifacts = missing_required_artifact_types_top_n(results, required_artifact_types_top_n)
        forbidden_artifacts = present_forbidden_artifact_types(results, forbidden_artifact_types)
        forbidden_top_artifacts = present_forbidden_artifact_types_top_n(results, forbidden_artifact_types_top_n)
        missing_content = missing_required_content(results, required_content)
        missing_exclusive = missing_exclusive_content(results, exclusive_content)
        file_count_violations = max_file_count_violation(results, max_per_file)
        rank_violation = bool(max_rank and (not rank or rank > max_rank))
        has_positive_assertion = bool(
            rank
            or required_paths
            or required_paths_top_n
            or required_artifact_types
            or required_artifact_types_top_n
            or required_content
            or exclusive_content
        )
        has_negative_assertion = bool(
            forbidden_paths
            or forbidden_path_prefixes
            or forbidden_artifact_types
            or forbidden_artifact_types_top_n
            or max_per_file
            or max_rank
        )
        passed = (
            (has_positive_assertion or has_negative_assertion)
            and not missing
            and not missing_top_paths
            and not forbidden_present
            and not missing_artifacts
            and not missing_top_artifacts
            and not forbidden_artifacts
            and not forbidden_top_artifacts
            and not missing_content
            and not missing_exclusive
            and not file_count_violations
            and not rank_violation
        )
        outcomes.append(
            {
                "name": case.get("name", query),
                "query": query,
                "passed": passed,
                "matched_rank": rank or None,
                "expected_paths": expected_paths,
                "expected_path_prefixes": expected_path_prefixes,
                "required_paths": required_paths,
                "missing_required_paths": missing,
                "required_paths_top_n": required_paths_top_n,
                "missing_required_paths_top_n": missing_top_paths,
                "forbidden_paths": forbidden_paths,
                "forbidden_path_prefixes": forbidden_path_prefixes,
                "present_forbidden_paths": forbidden_present,
                "required_artifact_types": required_artifact_types,
                "missing_required_artifact_types": missing_artifacts,
                "required_artifact_types_top_n": required_artifact_types_top_n,
                "missing_required_artifact_types_top_n": missing_top_artifacts,
                "forbidden_artifact_types": forbidden_artifact_types,
                "present_forbidden_artifact_types": forbidden_artifacts,
                "forbidden_artifact_types_top_n": forbidden_artifact_types_top_n,
                "present_forbidden_artifact_types_top_n": forbidden_top_artifacts,
                "max_per_file": max_per_file or None,
                "file_count_violations": file_count_violations,
                "max_rank": max_rank or None,
                "rank_violation": rank_violation,
                "required_content": required_content,
                "missing_required_content": missing_content,
                "exclusive_content": exclusive_content,
                "missing_exclusive_content": missing_exclusive,
                "context_pack": context_pack,
                "top_paths": [item["path"] for item in results],
            }
        )

    passed = sum(1 for outcome in outcomes if outcome["passed"])
    total = len(outcomes)

    if args.json:
        print(json.dumps({"passed": passed, "total": total, "cases": outcomes}, indent=2))
    else:
        print(f"RAG retrieval eval: {passed}/{total} passed")
        for outcome in outcomes:
            status = "PASS" if outcome["passed"] else "FAIL"
            rank = outcome.get("matched_rank")
            if rank:
                rank_text = f"rank {rank}"
            elif outcome.get("required_paths") and not outcome.get("missing_required_paths"):
                rank_text = "required paths present"
            else:
                rank_text = "no expected path in top results"
            print(f"- {status} {outcome['name']}: {rank_text}")
            if not outcome["passed"]:
                for path in outcome.get("missing_required_paths", []):
                    print(f"  missing: {path}")
                for path in outcome.get("present_forbidden_paths", []):
                    print(f"  forbidden present: {path}")
                for artifact in outcome.get("missing_required_artifact_types", []):
                    print(f"  missing artifact type: {artifact}")
                for artifact in outcome.get("present_forbidden_artifact_types", []):
                    print(f"  forbidden artifact type present: {artifact}")
                for check in outcome.get("missing_required_content", []):
                    print(f"  missing content: {check}")
                for check in outcome.get("missing_exclusive_content", []):
                    print(f"  missing exclusive content: {check}")
                for path in outcome.get("top_paths", [])[:5]:
                    print(f"  top: {(root / path).resolve()}")

    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
