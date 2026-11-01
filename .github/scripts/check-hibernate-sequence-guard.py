#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""hibernate-sequence-guard (enforced).

Hibernate Reactive + Kotlin `PanacheEntity` allocate the `id` from a sequence named
`<table>_seq` (allocationSize 50). When the Flyway migration creates the table with BIGSERIAL
(which only yields `<table>_id_seq`) and the schema is `generation: none`, the sequence Hibernate
expects never exists and every INSERT fails at runtime with `relation "<table>_seq" does not exist`.

For every module under the repo root, each real `@Table(name = "x")` class with direct or
resolvable same-module PanacheEntity ancestry needs a matching `CREATE SEQUENCE [IF NOT EXISTS]
x_seq` in that module's own `src/main/resources/db/migration/*.sql`. A quoted "X_SEQ" does not satisfy it:
Postgres treats it as a distinct case-sensitive identifier (the party V5 mistake V6 had to undo).

Replaces 25 per-service HibernateSequenceGuardTest.kt copies (three byte variants); as a source gate it
also covers services that never carried the copy.
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib

TABLE = re.compile(r"""@Table\(\s*name\s*=\s*"([^"]+)\"""")
CREATE_SEQ = re.compile(
    r"""create\s+sequence(?:\s+if\s+not\s+exists)?\s+("?)([A-Za-z0-9_]+)\1""", re.IGNORECASE
)
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "worktrees"}


# Reuse the collector's offset-preserving, nested-comment-aware Kotlin lexer.
# It has no external dependencies and already supports masking string/character literals.
_spec = importlib.util.spec_from_file_location(
    "sequence_guard_evidence", pathlib.Path(__file__).with_name("collect-test-run-evidence.py"),
)
_collector = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_collector)
mask_kotlin_comments = _collector.mask_kotlin_comments

HEADER_TOKEN = re.compile(r"[{}()[\];]|\b(?:class|interface|object|fun|val|var|typealias)\b")
DECLARATIONS = {"class", "interface", "object", "fun", "val", "var", "typealias"}


def annotated_class_header(code: str, start: int) -> str:
    """Find only this annotation's class header in comment/string-masked Kotlin.

    Parenthesized constructor lambdas and annotation arguments are not class bodies.
    A bodyless declaration ends at EOF or the next declaration; it cannot borrow a
    later class's Panache superclass. This is a bounded lexical check, not an AST.
    """
    nesting = 0
    header_start = None
    for token in HEADER_TOKEN.finditer(code, start):
        value = token.group()
        if value in ("(", "["):
            nesting += 1
        elif value in (")", "]"):
            nesting -= 1
        elif nesting == 0:
            if header_start is None:
                if value == "class":
                    header_start = token.end()
                elif value in DECLARATIONS or value in ("{", "}", ";"):
                    return ""
            elif value in DECLARATIONS or value in ("{", "}", ";"):
                return code[header_start:token.start()]
    return code[header_start:] if header_start is not None else ""


def superclass(header: str) -> str | None:
    """Read the superclass after the header's top-level colon, not a parameter type."""
    nesting = 0
    generic_depth = 0
    for token in re.finditer(r"->|[()[\]<>:]", header):
        value = token.group()
        if value in ("(", "["):
            nesting += 1
        elif value in (")", "]"):
            nesting -= 1
        # Inside constructor arguments '<'/'>' can be comparison operators. Their
        # parentheses already shield parameter colons; they must not change generic depth.
        elif nesting == 0 and value == "<":
            generic_depth += 1
        elif nesting == 0 and value == ">":
            generic_depth -= 1
        elif value == ":" and nesting == 0 and generic_depth == 0:
            parent = re.match(r"\s*([A-Za-z_]\w*(?:\.\w+)*)", header[token.end():])
            return parent.group(1) if parent else None
    return None


def kotlin_context(code: str):
    package = re.search(r"(?m)^\s*package\s+([\w.]+)", code)
    prefix = package.group(1) + "." if package else ""
    imports = {m.group(2) or m.group(1).split(".")[-1]: m.group(1) for m in re.finditer(
        r"(?m)^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?", code,
    )}
    return prefix, imports


def resolved_parent(header: str, prefix: str, imports: dict[str, str]) -> str | None:
    # Source presence only: external/unresolved bases, star imports and nested class
    # qualification are not a full Kotlin type resolver and do not establish ancestry.
    parent = superclass(header)
    if parent is None:
        return None
    if parent == "PanacheEntity" or parent.endswith(".PanacheEntity"):
        return "PanacheEntity"
    return imports.get(parent, parent if "." in parent else prefix + parent)


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
    # This remains a source-presence check: SQL comments and later DROP statements
    # are not interpreted (the replaced tests had the same limitation).
    for f in mig.rglob("*.sql"):
        for m in CREATE_SEQ.finditer(f.read_text(encoding="utf-8", errors="replace")):
            out.add(m.group(2) if m.group(1) == '"' else m.group(2).lower())
    return out


def check(root: pathlib.Path) -> tuple[list[str], int]:
    findings: list[str] = []
    subjects = 0
    for mod in modules(root):
        required: set[str] = set()
        sources = []
        parents = {}
        for f in (mod / "src" / "main" / "kotlin").rglob("*.kt"):
            text = mask_kotlin_comments(f.read_text(encoding="utf-8", errors="replace"))
            declarations = mask_kotlin_comments(text, mask_strings=True)
            prefix, imports = kotlin_context(declarations)
            sources.append((text, declarations, prefix, imports))
            for declaration in re.finditer(r"\bclass\s+([A-Za-z_]\w*)", declarations):
                header = annotated_class_header(declarations, declaration.start())
                parents[prefix + declaration.group(1)] = resolved_parent(header, prefix, imports)
        panache = {"PanacheEntity"}
        while additions := {name for name, parent in parents.items() if parent in panache} - panache:
            panache.update(additions)
        for text, declarations, prefix, imports in sources:
            for m in TABLE.finditer(text):
                # Annotation text must be code, not an example inside a string literal.
                if declarations[m.start():m.start() + len("@Table")] != "@Table":
                    continue
                header = annotated_class_header(declarations, m.start())
                if resolved_parent(header, prefix, imports) in panache:
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
    # Strings may contain comment delimiters; masking must preserve their bytes.
    strings = '"https://example.invalid/* docs */" """// raw /* text */""" \'/\''
    if mask_kotlin_comments(strings) != strings:
        print("SELF-TEST FAIL: comment masking changed string/character literals")
        ok = False
    cases = [
        ("entity without its _seq is flagged (known-positive)", ENTITY,
         "CREATE TABLE orders (id BIGSERIAL PRIMARY KEY);\n", True),
        ("annotation string brace cannot hide a real Panache superclass",
         '@Table(name = "orders")\n@Deprecated("{")\nclass Order : PanacheEntity() {}', "", True),
        ("constructor lambda is not the class body",
         '@Table(name = "orders")\nclass Order(val label: String = run { "x" }) : PanacheEntity() {}',
         "", True),
        ("constructor comparison cannot hide the superclass",
         '@Table(name = "orders")\nclass Order(val enabled: Boolean = 1 < 2) : PanacheEntity() {}',
         "", True),
        ("generic bounds do not become the superclass",
         '@Table(name = "orders")\nclass Order<T : Comparable<T>>(val value: T) : PanacheEntity() {}',
         "", True),
        ("constructor function type and comparison do not hide the superclass",
         '@Table(name = "orders")\nclass Order(val test: () -> Boolean = { 1 < 2 }) : PanacheEntity() {}',
         "", True),
        ("bodyless plain class cannot borrow a later Panache superclass",
         '@Table(name = "plain")\nclass Plain\nclass Other : PanacheEntity() {}', "", False),
        ("bodyless Panache class before another class is checked",
         '@Table(name = "orders")\nclass Order : PanacheEntity()\nclass Other {}', "", True),
        ("raw string example does not declare a table",
         'val example = \"\"\"@Table(name = "ghost") class Ghost : PanacheEntity() {}\"\"\"', "", False),
        ("ordinary string example does not declare a table",
         r'val example = "@Table(name = \"ghost\") class Ghost : PanacheEntity() {}"', "", False),
        ("bodyless inherited Panache table is checked without borrowing a later class",
         ('package a\nabstract class Base : PanacheEntity() {}\n'
          '@Table(name = "orders")\nclass Order : Base()\nclass Other {}'), "", True),
        ("inherited Panache table with its sequence is clean",
         ('package a\nabstract class Base : PanacheEntity() {}\n'
          '@Table(name = "orders")\nclass Order : Base()'), "CREATE SEQUENCE orders_seq;", False),
        ("cyclic superclass graph terminates without inventing Panache ancestry",
         'class A : B()\nclass B : A()\n@Table(name = "plain")\nclass Plain : A()', "", False),
        ("unresolved superclass is not asserted to be Panache",
         '@Table(name = "plain")\nclass Plain : ExternalBase()', "", False),
        ("unrelated superclass does not borrow Panache from another class",
         '@Table(name = "plain")\nclass Plain : Base()\nclass Other : PanacheEntity() {}', "", False),
        ("bodyless entity at EOF without newline is checked",
         '@Table(name = "orders")\nclass Order : PanacheEntity()', "", True),
        ("nested Kotlin comments do not expose a phantom table",
         '/** outer /* nested */ @Table(name = "ghost") */\nclass Ghost : PanacheEntity() {}\n', "", False),
        ("comment markers inside annotation strings do not hide an entity",
         '@Deprecated("use https://example.invalid/* docs */")\n' + ENTITY,
         "", True),
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
    # Simple names are resolved within their package or explicit import, never module-wide.
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        source = root / "svc/src/main/kotlin"
        source.mkdir(parents=True)
        (source / "A.kt").write_text(
            'package a\nabstract class Base : PanacheEntity() {}\n'
            '@Table(name = "orders")\nclass Order : Base()',
        )
        (source / "B.kt").write_text(
            'package b\nopen class Base\n@Table(name = "plain")\nclass Plain : Base()',
        )
        (source / "C.kt").write_text(
            'package c\nimport a.Base\n@Table(name = "imported")\nclass Imported : Base()',
        )
        findings, subjects = check(root)
        if subjects != 2 or len(findings) != 2 or any("plain_seq" in item for item in findings):
            print(f"SELF-TEST FAIL: package/import isolation: findings={findings}")
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
