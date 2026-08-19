#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""
Assert every JPA entity property resolves to a column its migrations actually created.

Hibernate's IMPLICIT column name for a property is the property name verbatim, and Postgres
folds an unquoted identifier to lower case. So in a service that configures no
`physical-naming-strategy`, a property called `createdAt` resolves to the column `createdat`,
while every migration in this repo writes `created_at`. The mapping is wrong for every
multi-word property and right for every single-word one, which is exactly why it survives
review: the class looks consistent.

Found live in consent-service's `SuppressionEntity` (2026-08-19). Six of its ten columns were
wrong — party_id, reason_code, created_by, created_at, revoked_at, revoked_by — and
`GET /api/v1/suppressions/party/{partyId}` answered `500 INTERNAL_ERROR` on EVERY call since the
endpoint shipped:

    SQLGrammarException: column se1_0.createdat does not exist (42703)

Nothing caught it. A unit test that mocks the repository never issues the SQL; the service had no
integration test driving that route against a real database; the pod is Ready because health
probes do not touch the table; and the sibling entities in the same package (ConsentEntity,
ConsentOutboxEntity) spell every column out, so the file next door looked like the convention was
being followed. It was found only when schemathesis fuzzed the running service.

THE SPLIT THIS ENFORCES
-----------------------
Six services set `hibernate-orm.physical-naming-strategy: CamelCaseToUnderscoresNamingStrategy`
(campaign, engagement, fx and friends). There, `createdAt` -> `created_at` is derived and a bare
property is CORRECT. Everywhere else the column name must be explicit. Both spellings are fine;
mixing them per-service is what breaks, so the rule is per-service and derived from that service's
own config — never a list kept here.

Usage:
    check-entity-column-names.py             # gate (exit 1 on a bare camelCase column)
    check-entity-column-names.py --self-test # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
STRATEGY = "CamelCaseToUnderscoresNamingStrategy"

CAMEL = re.compile(r"[a-z][A-Z]")

# One `@Entity ... class X { ... }` body. Kotlin binds an annotation to the NEXT declaration, so an
# annotation block sits immediately above the thing it annotates — the same rule that lets a stray
# top-level function between `@Path` and its class steal the annotation (CLAUDE.md). Scanning a
# whole FILE instead of a class body is how a first cut of this check reported five findings in
# openbank-audit-service against a plain result-holder that merely shares a file with an entity.
ENTITY_CLASS = re.compile(
    r"@Entity\b(?P<anns>(?:[^\n]*\n)*?)\s*class\s+(?P<name>\w+)[^{]*\{(?P<body>.*?)\n\}",
    re.S,
)
TABLE_NAME = re.compile(r'@Table\(\s*name\s*=\s*"([^"]+)"')
PROPERTY = re.compile(
    r"((?:^[ \t]*@(?:\w+:)?[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?[ \t]*\n)*)"
    r"^[ \t]*(?:lateinit\s+)?(?:var|val)\s+(\w+)\s*:",
    re.M,
)
# `@field:Column` / `@get:Column` are the same annotation with a Kotlin use-site target, and
# roughly half the fleet writes them that way. A first cut of this regex required a bare `@Column`
# and therefore reported all of openbank-sanctions-service — every column of which IS named
# explicitly, as `@field:Column(name = "checked_at")`. Twenty-three findings, none real.
EXPLICIT_NAME = re.compile(r'@(?:\w+:)?Column\((?:[^()]|\([^()]*\))*?name\s*=\s*"([^"]+)"')
# Associations and computed/ignored members carry their own naming rules and are not columns.
NOT_A_COLUMN = ("Transient", "OneToMany", "ManyToMany", "OneToOne", "ManyToOne",
                "JoinColumn", "Embedded", "ElementCollection", "Formula")

def service_uses_strategy(module: Path) -> bool:
    for cfg in (module / "src" / "main" / "resources").glob("application*.y*ml"):
        if STRATEGY in gatelib.read_text(cfg):
            return True
    return False


def audit(repo: Path) -> tuple[list[str], int]:
    """-> ([findings], entity classes examined)

    The rule is the CONVENTION, not a diff against the DDL, and that is a deliberate narrowing.
    Deriving "does this column exist" from the migrations needs real DDL parsing — this repo has
    partitioned tables (`) PARTITION BY RANGE (booking_date);`), custom enum types
    (`settlement_type settlement_type NOT NULL`), ALTER/RENAME chains and quoted identifiers — and
    every shortcut produced false findings against code that was fine: twelve from a type-restricted
    column pattern, then the whole of transaction-service from a closing-paren pattern. A gate that
    cries wolf about correct code gets ignored, and then it is worth less than nothing.

    So this asks the decidable question instead: in a service that converts nothing, does a
    multi-word property say which column it means? Both spellings are fine — explicit names, or the
    naming strategy — and mixing them per service is what breaks.
    """
    findings: list[str] = []
    examined = 0
    for module in sorted(repo.glob("openbank-*")):
        if not (module / "src" / "main").is_dir():
            continue
        if service_uses_strategy(module):
            continue  # the strategy derives the snake_case name; a bare property is correct
        for f in sorted((module / "src" / "main").rglob("*.kt")):
            text = gatelib.read_text(f)
            if "@Entity" not in text:
                continue
            for cls in ENTITY_CLASS.finditer(text):
                examined += 1
                for annotations, prop in PROPERTY.findall(cls.group("body")):
                    if any(marker in annotations for marker in NOT_A_COLUMN):
                        continue
                    if not CAMEL.search(prop):
                        continue  # single word: the implicit name and the folded identifier agree
                    if EXPLICIT_NAME.search(annotations):
                        continue
                    findings.append(
                        f"{f.relative_to(repo)}: {cls.group('name')}.{prop} has no explicit "
                        f"@Column(name = ...), and {module.name} sets no physical-naming-strategy, "
                        f"so Hibernate asks for the column `{prop.lower()}` — every migration in "
                        f"this repo writes `{_snake(prop)}`"
                    )
    return findings, examined


def _snake(prop: str) -> str:
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", prop).lower()


def self_test() -> int:
    """Prove the gate fires on the real defect shape and stays silent on the two correct ones."""
    # (label, strategy, expect a finding, expected subject count). The subject count matters:
    # the strategy case is silent because the MODULE IS SKIPPED, not because the entity passed, and
    # a self-test that only checked "no finding" could not tell those apart — which is the exact
    # failure mode this repo names most often.
    cases = [
        ("bare camelCase, no strategy -> MUST fire", None, True, 1),
        ("explicit @Column(name) -> silent", None, False, 1),
        ("bare camelCase WITH strategy -> skipped, 0 subjects", STRATEGY, False, 0),
        ("single-word property, no strategy -> silent", None, False, 1),
    ]
    bodies = [
        "    @Column(nullable = false)\n    lateinit var createdAt: String\n",
        '    @Column(name = "created_at", nullable = false)\n    lateinit var createdAt: String\n',
        "    @Column(nullable = false)\n    lateinit var createdAt: String\n",
        "    @Column(nullable = false)\n    lateinit var source: String\n",
    ]
    failures = 0
    for (label, strategy, want_finding, want_subjects), body in zip(cases, bodies):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            module = repo / "openbank-probe-service"
            src = module / "src" / "main" / "kotlin"
            src.mkdir(parents=True)
            (module / "src" / "main" / "resources").mkdir(parents=True)
            (module / "src" / "main" / "resources" / "application.yaml").write_text(
                f"quarkus:\n  hibernate-orm:\n    physical-naming-strategy: {strategy}\n"
                if strategy else "quarkus:\n  hibernate-orm: {}\n"
            )
            (src / "E.kt").write_text(f"@Entity\n@Table(name = \"t\")\nclass E {{\n{body}}}\n")
            got, examined = audit(repo)
            ok = bool(got) == want_finding and examined == want_subjects
            print(f"  {'ok  ' if ok else 'FAIL'}  {label}")
            if not ok:
                failures += 1
                for g in got:
                    print(f"        got: {g}")
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s)", file=sys.stderr)
        return 1
    print("self-test: PASS — the gate fires on the defect and is silent on both correct spellings")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    findings, examined = audit(REPO)
    gatelib.subjects(examined, "entity source files in services with no naming strategy")
    if findings:
        print("JPA properties that map to a column no migration creates:\n", file=sys.stderr)
        for f in findings:
            print(f"  {f}", file=sys.stderr)
        print(
            f"\n{len(findings)} finding(s). Add `@Column(name = \"<snake_case>\")`, matching the "
            f"migration. Do NOT 'fix' this by adding a physical-naming-strategy to a service whose "
            f"other entities already spell their columns out — that changes their mapping too.",
            file=sys.stderr,
        )
        return 1
    print("OK: every entity property in a strategy-less service names its column explicitly.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
