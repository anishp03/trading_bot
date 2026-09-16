#!/usr/bin/env python3
"""Build or refresh the local project RAG index."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
from pathlib import Path

from rag_lib import (
    chunk_text,
    config_root,
    context_header,
    connect_db,
    dense_embedding,
    db_path,
    delete_file_chunks,
    embedding_dimensions,
    embeddings_enabled,
    index_stats,
    iter_included_files,
    load_config,
    metadata_for,
    posix_rel,
    read_text,
    sha256_file,
    sparse_terms,
    storage_dir,
    tags_for,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Index project memory into SQLite FTS5.")
    parser.add_argument("--config", default=str(Path(__file__).resolve().parents[1] / "memory.yml"))
    parser.add_argument("--reset", action="store_true", help="Delete the existing index before rebuilding.")
    parser.add_argument("--dry-run", action="store_true", help="Show files that would be indexed without writing.")
    parser.add_argument("--stats", action="store_true", help="Print index stats after indexing.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config = load_config(Path(args.config))
    root = config_root(config)
    store = storage_dir(config)
    db_file = db_path(config)

    if args.reset and db_file.exists() and not args.dry_run:
        db_file.unlink()
        wal = Path(str(db_file) + "-wal")
        shm = Path(str(db_file) + "-shm")
        for sidecar in (wal, shm):
            if sidecar.exists():
                sidecar.unlink()

    files = iter_included_files(config)
    if args.dry_run:
        for path in files:
            print(posix_rel(path, root))
        print(f"Would index {len(files)} files.")
        return 0

    store.mkdir(parents=True, exist_ok=True)
    conn = connect_db(config)
    current_paths = {posix_rel(path, root) for path in files}

    existing_paths = {
        row["path"] for row in conn.execute("SELECT path FROM files").fetchall()
    }
    for stale_path in sorted(existing_paths - current_paths):
        delete_file_chunks(conn, stale_path)

    indexed = 0
    skipped = 0
    removed = len(existing_paths - current_paths)
    chunk_total = 0
    failures = []

    for path in files:
        rel_path = posix_rel(path, root)
        try:
            stat = path.stat()
            digest = sha256_file(path)
            existing = conn.execute(
                "SELECT sha256, chunk_count FROM files WHERE path = ?", (rel_path,)
            ).fetchone()
            has_contextual_chunks = conn.execute(
                """
                SELECT 1
                FROM chunks
                WHERE path = ?
                  AND search_text != ''
                  AND context_header != ''
                  AND metadata_json != '{}'
                  AND metadata_json LIKE '%"code_symbols"%'
                  AND metadata_json LIKE '%"defined_symbols"%'
                  AND metadata_json LIKE '%"api_paths"%'
                LIMIT 1
                """,
                (rel_path,),
            ).fetchone()
            if existing and existing["sha256"] == digest and has_contextual_chunks:
                conn.execute(
                    """
                    UPDATE files
                    SET mtime = ?, size = ?, indexed_at = ?
                    WHERE path = ?
                    """,
                    (stat.st_mtime, stat.st_size, time.time(), rel_path),
                )
                skipped += 1
                continue

            text = read_text(path)
            if text is None:
                skipped += 1
                continue

            chunks = chunk_text(path, text, config)
            delete_file_chunks(conn, rel_path)
            tag_text = tags_for(path, rel_path, text)

            for chunk in chunks:
                metadata = metadata_for(path, rel_path, text, chunk, tag_text)
                header = context_header(metadata)
                search_text = header + "\n\n" + chunk["text"]
                terms_json = json.dumps(sparse_terms(search_text), sort_keys=True)
                embedding_json = ""
                if embeddings_enabled(config):
                    embedding_json = json.dumps(
                        dense_embedding(search_text, embedding_dimensions(config))
                    )
                cursor = conn.execute(
                    """
                    INSERT INTO chunks(
                        path, chunk_index, line_start, line_end, file_type,
                        token_count, sha256, text, search_text, context_header,
                        metadata_json, artifact_type, embedding_json, terms_json, tags
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        rel_path,
                        chunk["chunk_index"],
                        chunk["line_start"],
                        chunk["line_end"],
                        chunk["file_type"],
                        chunk["token_count"],
                        digest,
                        chunk["text"],
                        search_text,
                        header,
                        json.dumps(metadata, sort_keys=True),
                        metadata["artifact_type"],
                        embedding_json,
                        terms_json,
                        tag_text,
                    ),
                )
                rowid = cursor.lastrowid
                conn.execute(
                    "INSERT INTO chunks_fts(rowid, path, text, tags) VALUES (?, ?, ?, ?)",
                    (rowid, rel_path, search_text, tag_text),
                )

            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    path, sha256, mtime, size, indexed_at, chunk_count
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (rel_path, digest, stat.st_mtime, stat.st_size, time.time(), len(chunks)),
            )
            indexed += 1
            chunk_total += len(chunks)
        except Exception as exc:  # pragma: no cover - command-line resilience
            failures.append((rel_path, str(exc)))

    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES ('last_indexed_at', ?)",
        (time.strftime("%Y-%m-%d %H:%M:%S %Z"),),
    )
    conn.commit()

    stats = index_stats(conn)
    print(
        f"Indexed {indexed} changed files, skipped {skipped}, removed {removed}; "
        f"database now has {stats['files']} files and {stats['chunks']} chunks."
    )
    print(f"Index: {db_file}")

    if failures:
        print("\nFailures:", file=sys.stderr)
        for rel_path, message in failures[:20]:
            print(f"- {rel_path}: {message}", file=sys.stderr)
        if len(failures) > 20:
            print(f"- ... {len(failures) - 20} more", file=sys.stderr)
        return 1

    if args.stats:
        largest = conn.execute(
            "SELECT path, chunk_count FROM files ORDER BY chunk_count DESC LIMIT 10"
        ).fetchall()
        print("\nLargest indexed files:")
        for row in largest:
            print(f"- {row['path']}: {row['chunk_count']} chunks")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
