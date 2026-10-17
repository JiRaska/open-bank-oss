#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""hibernate-sequence-guard (enforced).

Hibernate Reactive + Kotlin `PanacheEntity` allocate the `id` from a sequence named
`<table>_seq` (allocationSize 50). When the Flyway migration creates the table with BIGSERIAL
(which only yields `<table>_id_seq`) and the schema is `generation: none`, the sequence Hibernate
expects never exists and every INSERT fails at runtime with `relation "<table>_seq" does not exist`.

For every module under the repo root, each `@Table(name = "x")` in a Kotlin file that declares
`: PanacheEntity()` needs an unquoted, lower-case `CREATE SEQUENCE [IF NOT EXISTS] x_seq` in that
module's own `src/main/resources/db/migration/*.sql`. A quoted "X_SEQ" does NOT satisfy it:
Postgres treats it as a distinct case-sensitive identifier (the party V5 mistake V6 had to undo).

Replaces 26 byte-identical per-service HibernateSequenceGuardTest.kt copies; as a source gate it
also covers services that never carried the copy.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

PANACHE = re.compile(r""":\s*PanacheEntity\s*\(\s*\)""")
TABLE = re.compile(r"""@Table\(\s*name\s*=\s*"([^"]+)\"""")
CREATE_SEQ = re.compile(
    r"""create\s+sequence(?:\s+if\s+not\s+exists)?\s+("?)([A-Za-z0-9_]+)\1""", re.IGNORECASE
)
COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "worktrees"}


def modules(root: pathlib.Path):
    """Yield every directory that has src/main/kotlin."""
    for dirpath, dirnames, _ in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        p = pathlib.Path(dirpath)
        if (p / "src" / "main" / "kotlin").is_dir():
            dirnames[:] = []
            yield p


def created_sequences(module: pathlib.Path) -> set[str]:
    mig = module / "src" / "main" / "resources" / "db" / "migration"
    out: set[str] = set()
    if not mig.is_dir():
        return out
    for f in mig.rglob("*.sql"):
        for m in CREATE_SEQ.finditer(f.read_text(encoding="utf-8", errors="replace")):
            out.add(m.group(2) if m.group(1) == '"' else m.group(2).lower())
    return out


def check(root: pathlib.Path) -> tuple[list[str], int]:
    findings: list[str] = []
    subjects = 0
    for mod in modules(root):
        required: set[str] = set()
        for f in (mod / "src" / "main" / "kotlin").rglob("*.kt"):
            text = COMMENT.sub("", f.read_text(encoding="utf-8", errors="replace"))
            for m in TABLE.finditer(text):
                # the class header this @Table annotates: up to its opening brace
                header = text[m.end() : text.find("{", m.end())]
                if PANACHE.search(header):
                    required.add(f"{m.group(1)}_seq")
        if not required:
            continue
        subjects += len(required)
        present = created_sequences(mod)
        rel = mod.relative_to(root)
        for seq in sorted(required - present):
            findings.append(
                f"{rel}: PanacheEntity table needs sequence `{seq}` (unquoted, lower-case) in "
                f"src/main/resources/db/migration — add CREATE SEQUENCE IF NOT EXISTS {seq} "
                f"INCREMENT BY 50"
            )
    return findings, subjects


ENTITY = 'package a\n@Table(name = "orders")\nclass Order : PanacheEntity() {}\n'


def self_test() -> int:
    ok = True
    cases = [
        ("entity without its _seq is flagged (known-positive)", ENTITY,
         "CREATE TABLE orders (id BIGSERIAL PRIMARY KEY);\n", True),
        ("entity with unquoted lower-case _seq is clean (known-negative)", ENTITY,
         "CREATE SEQUENCE IF NOT EXISTS orders_seq INCREMENT BY 50;\n", False),
        ("upper-case unquoted name folds to lower-case and is clean", ENTITY,
         "CREATE SEQUENCE ORDERS_SEQ INCREMENT BY 50;\n", False),
        ("quoted upper-case name does not satisfy the requirement", ENTITY,
         'CREATE SEQUENCE "ORDERS_SEQ" INCREMENT BY 50;\n', True),
        ("only <table>_id_seq (BIGSERIAL) does not satisfy it", ENTITY,
         "CREATE SEQUENCE orders_id_seq;\n", True),
        ("a plain @Entity beside a Panache one in the same file needs no sequence",
         ENTITY + '@Table(name = "plain")\nclass Plain {}\n',
         "CREATE SEQUENCE IF NOT EXISTS orders_seq;\n", False),
        ("an @Table example inside a KDoc is not an entity",
         '/**\n * @Table(name = "ghost")\n */\nabstract class Base : PanacheEntity() {}\n', "", False),
        ("a non-Panache entity needs no sequence",
         'package a\n@Table(name = "orders")\nclass Order {}\n', "", False),
    ]
    for name, kt, sql, expect in cases:
        with tempfile.TemporaryDirectory() as d:
            mod = pathlib.Path(d) / "svc"
            (mod / "src/main/kotlin").mkdir(parents=True)
            (mod / "src/main/kotlin/A.kt").write_text(kt)
            (mod / "src/main/resources/db/migration").mkdir(parents=True)
            (mod / "src/main/resources/db/migration/V1__x.sql").write_text(sql)
            findings, _ = check(pathlib.Path(d))
            if bool(findings) != expect:
                print(f"SELF-TEST FAIL: {name}: findings={findings}")
                ok = False
    # sequences are per module: a sibling module's sequence must not satisfy this one
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        for svc, sql in (("a", ""), ("b", "CREATE SEQUENCE orders_seq;\n")):
            (root / svc / "src/main/kotlin").mkdir(parents=True)
            (root / svc / "src/main/kotlin/A.kt").write_text(ENTITY)
            (root / svc / "src/main/resources/db/migration").mkdir(parents=True)
            (root / svc / "src/main/resources/db/migration/V1__x.sql").write_text(sql)
        findings, _ = check(root)
        if len(findings) != 1 or not findings[0].startswith("a:"):
            print(f"SELF-TEST FAIL: per-module isolation: findings={findings}")
            ok = False
    print("self-test: " + ("pass" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, subjects = check(pathlib.Path(args.root))
    gatelib.subjects(subjects, "PanacheEntity table(s) checked for a <table>_seq sequence")
    for f in findings:
        print(f"  {f}")
    if findings:
        print(f"{len(findings)} PanacheEntity table(s) missing their hibernate sequence")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
