#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A Flyway migration cannot CREATE an untrusted Postgres extension — it works locally and crashloops deployed.

WHY THIS EXISTS
---------------
PostgreSQL splits extensions into TRUSTED and untrusted. A trusted one (`uuid-ossp`, `pgcrypto`,
`pg_trgm`, `unaccent`, `hstore`, …) can be created by a plain database OWNER; an untrusted one
(`vector`, `postgis`, `pg_stat_statements`, `plpython3u`, …) requires SUPERUSER.

Flyway connects as the application owner. Every local development path — Dev Services, a
`PostgreSQLContainer`, docker-compose — connects as `postgres`, i.e. superuser. So a migration that
creates an untrusted extension is GREEN on every laptop, GREEN in CI, and fails only in the
deployed cluster, where CloudNativePG hands the application a non-superuser role:

    as owner:      ERROR:  permission denied to create extension "vector"
                   HINT:   Must be superuser to create this extension.
    as superuser:  CREATE EXTENSION

The failure mode is the expensive shape: Flyway aborts, the pod crashloops on what reads as a
migration bug, and nothing about the diff hints that the difference is the connecting ROLE.
Measured against the exact image this fleet runs (ghcr.io/cloudnative-pg/postgresql:18.1) while
adding pgvector retrieval to copilot-service (ADR-0265 / ADR-0183).

THE FIX THIS CHECK REQUIRES
---------------------------
A CloudNativePG `Database` resource declaring the extension. The operator applies it over its own
superuser connection:

    apiVersion: postgresql.cnpg.io/v1
    kind: Database
    spec:
      cluster: {name: <cluster>}
      name: <database>
      extensions:
        - name: vector

The migration line stays — `CREATE EXTENSION IF NOT EXISTS` short-circuits before the permission
check, so it remains what makes local dev and the test containers work. BOTH halves are load-bearing
and this checker asserts the one that is easy to forget, because the other one fails loudly the
moment a developer runs the app.

WHAT IT CHECKS
--------------
For every `openbank-*/src/main/resources/db/migration/*.sql` containing `CREATE EXTENSION <name>`
where `<name>` is in the untrusted set: some `openbank-infra/gitops/**/*.yaml` must declare a
`kind: Database` with that extension. The pairing is by extension name, not by service: a repo-wide
`Database` inventory is what a reviewer can actually verify, and tying it to a service would need a
service→cluster map that would rot.

Usage:  check-untrusted-pg-extension.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]

# Untrusted in PostgreSQL 13+ (i.e. `trusted = true` absent from the extension's control file).
# Deliberately a small, curated list rather than a probe of a live server: this must run with no
# database, and a wrong entry here fails a build rather than shipping a broken migration. Extend it
# when a new extension is adopted — and confirm the classification the way ADR-0265 did, by running
# CREATE EXTENSION as a non-superuser against the real image, not by reading a blog post.
UNTRUSTED = {
    "vector",
    "postgis",
    "postgis_topology",
    "pg_stat_statements",
    "pg_cron",
    "plpython3u",
    "plperlu",
    "file_fdw",
    "postgres_fdw",
    "dblink",
    "amcheck",
    "pg_prewarm",
    "pgstattuple",
}

CREATE_EXTENSION_RE = re.compile(
    r"""CREATE\s+EXTENSION\s+(?:IF\s+NOT\s+EXISTS\s+)?["']?([a-zA-Z0-9_]+)["']?""",
    re.IGNORECASE,
)


def strip_sql_comments(sql: str) -> str:
    """Drop `--` line comments and /* */ blocks.

    Not cosmetic: this repo's migrations carry long prose explaining exactly the trap this checker
    encodes, and that prose names `CREATE EXTENSION vector` in full. A checker that reads its own
    documentation as code is the "grep found the word, not the artifact" failure this repo has
    already been bitten by.
    """
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.DOTALL)
    return re.sub(r"--[^\n]*", " ", sql)


def migrations_creating_untrusted(root: pathlib.Path) -> list[tuple[str, str]]:
    """[(relative sql path, extension name)] for every untrusted CREATE EXTENSION."""
    out: list[tuple[str, str]] = []
    for path in sorted(root.glob("openbank-*/src/main/resources/db/migration/*.sql")):
        try:
            sql = strip_sql_comments(path.read_text(errors="ignore"))
        except OSError:
            continue
        for name in CREATE_EXTENSION_RE.findall(sql):
            if name.lower() in UNTRUSTED:
                out.append((str(path.relative_to(root)), name.lower()))
    return out


def declared_extensions(root: pathlib.Path) -> set[str]:
    """Extension names declared on any CloudNativePG `Database` resource under gitops."""
    declared: set[str] = set()
    for path in sorted((root / "openbank-infra" / "gitops").rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(path.read_text(errors="ignore")))
        except (OSError, yaml.YAMLError):
            # An unparseable manifest is another gate's problem; skipping it here must not be
            # silent, or this checker could report "nothing declared" about a file it could not read.
            print(f"::warning::could not parse {path} — skipped by check-untrusted-pg-extension")
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "Database":
                continue
            for ext in (doc.get("spec") or {}).get("extensions") or []:
                if isinstance(ext, dict) and ext.get("name"):
                    if str(ext.get("ensure", "present")).lower() != "absent":
                        declared.add(str(ext["name"]).lower())
    return declared


def findings(root: pathlib.Path = REPO) -> tuple[list[str], int]:
    uses = migrations_creating_untrusted(root)
    declared = declared_extensions(root)
    messages = []
    for sql_path, ext in uses:
        if ext in declared:
            continue
        messages.append(
            f"::error file={sql_path}::migration creates the UNTRUSTED extension '{ext}', which the "
            f"database owner Flyway connects as cannot create — it works on every laptop (superuser) "
            f"and fails in the cluster with 'permission denied to create extension \"{ext}\"'. Declare "
            f"it on a CloudNativePG Database resource (kind: Database, spec.extensions[].name: {ext}) "
            f"so the operator creates it over its superuser connection. Keep the migration line: "
            f"IF NOT EXISTS short-circuits before the permission check and is what keeps local dev "
            f"and the test containers working."
        )
    return messages, len(uses)


def self_test() -> int:
    """Drive both directions against a temporary tree — a checker that has only seen a clean repo is unfalsified."""
    import tempfile

    ok = True

    def case(label: str, sql: str, database_yaml: str | None, expected: int) -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            mig = root / "openbank-x-service" / "src" / "main" / "resources" / "db" / "migration"
            mig.mkdir(parents=True)
            (mig / "V1__x.sql").write_text(sql)
            gitops = root / "openbank-infra" / "gitops" / "components" / "x"
            gitops.mkdir(parents=True)
            if database_yaml is not None:
                (gitops / "db.yaml").write_text(database_yaml)
            got = len(findings(root)[0])
            status = "ok " if got == expected else "FAIL"
            if got != expected:
                ok = False
            print(f"  [{status}] {label}: found={got} expected={expected}")

    db_vector = (
        "apiVersion: postgresql.cnpg.io/v1\nkind: Database\nspec:\n"
        "  cluster: {name: c}\n  name: d\n  owner: o\n  extensions:\n    - name: vector\n"
    )
    db_absent = db_vector.replace("- name: vector\n", "- name: vector\n      ensure: absent\n")

    case("untrusted extension with no Database resource — MUST flag",
         "CREATE EXTENSION IF NOT EXISTS vector;", None, 1)
    case("untrusted extension WITH a Database resource — must not flag",
         "CREATE EXTENSION IF NOT EXISTS vector;", db_vector, 0)
    case("a trusted extension is never flagged",
         'CREATE EXTENSION IF NOT EXISTS "pgcrypto";', None, 0)
    case("quoted untrusted name is still matched",
         'CREATE EXTENSION IF NOT EXISTS "vector";', None, 1)
    case("prose in a line comment is not code",
         "-- CREATE EXTENSION vector must never be done here\nCREATE TABLE t(id int);", None, 0)
    case("prose in a block comment is not code",
         "/* the trap: CREATE EXTENSION vector */\nCREATE TABLE t(id int);", None, 0)
    case("ensure: absent does not count as declared",
         "CREATE EXTENSION IF NOT EXISTS vector;", db_absent, 1)
    case("no IF NOT EXISTS is matched too",
         "CREATE EXTENSION vector;", None, 1)

    print("self-test: PASS" if ok else "self-test: FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    messages, checked = findings()
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    print(f"SUBJECTS={checked}")
    verdict = "clean." if not messages else f"{len(messages)} finding(s) above."
    print(f"check-untrusted-pg-extension: {checked} untrusted CREATE EXTENSION site(s) — {verdict}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
