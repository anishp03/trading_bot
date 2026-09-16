#!/usr/bin/env python3
"""Summarize the indexed ProjectBrain RAG corpus by artifact lane.

This is read-only. It helps catch corpus drift such as chat traces crowding out
source files or one file contributing too many chunks to normal retrieval.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List

from rag_lib import DEFAULT_CONFIG, connect_db, load_config


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Inventory the local RAG corpus by artifact type.")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    parser.add_argument("--top-files", type=int, default=15)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def rows_by_artifact(conn) -> List[Dict]:
    return [
        dict(row)
        for row in conn.execute(
            """
            SELECT artifact_type,
                   COUNT(DISTINCT path) AS files,
                   COUNT(*) AS chunks,
                   SUM(token_count) AS tokens
            FROM chunks
            GROUP BY artifact_type
            ORDER BY chunks DESC, files DESC
            """
        ).fetchall()
    ]


def top_files(conn, limit: int) -> List[Dict]:
    return [
        dict(row)
        for row in conn.execute(
            """
            SELECT path,
                   artifact_type,
                   COUNT(*) AS chunks,
                   SUM(token_count) AS tokens
            FROM chunks
            GROUP BY path, artifact_type
            ORDER BY chunks DESC, tokens DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    ]


def main() -> int:
    args = parse_args()
    config = load_config(Path(args.config))
    conn = connect_db(config)
    artifact_rows = rows_by_artifact(conn)
    file_rows = top_files(conn, args.top_files)
    total_files = sum(int(row["files"] or 0) for row in artifact_rows)
    total_chunks = sum(int(row["chunks"] or 0) for row in artifact_rows)
    total_tokens = sum(int(row["tokens"] or 0) for row in artifact_rows)
    payload = {
        "totals": {
            "artifact_type_file_count_sum": total_files,
            "chunks": total_chunks,
            "tokens": total_tokens,
        },
        "artifact_types": artifact_rows,
        "top_files": file_rows,
    }
    if args.json:
        print(json.dumps(payload, indent=2))
        return 0

    print("# RAG Corpus Inventory")
    print("")
    print(f"Chunks: {total_chunks}")
    print(f"Tokens: {total_tokens}")
    print("")
    print("## Artifact Types")
    for row in artifact_rows:
        print(
            f"- {row['artifact_type']}: {row['files']} files, "
            f"{row['chunks']} chunks, {row['tokens']} tokens"
        )
    print("")
    print("## Largest Indexed Files")
    for row in file_rows:
        print(f"- {row['path']} ({row['artifact_type']}): {row['chunks']} chunks, {row['tokens']} tokens")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
