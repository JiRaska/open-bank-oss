#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""outbox-sent-retention (ADR-0329, enforced).

Every module whose `src/main` extends `AbstractOutboxDispatcher` owns an outbox table, and every
SENT row in it keeps its payload — often personal data — until something deletes it. The
libs-runtime `OutboxSentRetentionJob` deletes them, but only for CDI beans of type
`SentOutboxRetention`; a module whose repository is not one is silently retained forever, and
nothing at runtime can tell "no rows old enough" from "never looked".

So: a dispatcher owner must declare, on a non-comment line in `src/main`, a class whose
supertypes include `SentOutboxRetention` or the kernel base `AbstractPanacheOutboxRepository<`
(which implements it). Detection is by SUPERTYPE, never by file or method name.

Exemptions are by module with a REASON, because each one is an outbox whose SENT rows something
still reads. The set may only shrink and fails in both directions: an uncovered module it does
not name, and an exempt module that has since opted in or no longer owns a dispatcher.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

DISPATCHER_SUPERTYPE = re.compile(r":\s*AbstractOutboxDispatcher\s*\(")
# `class X(...) : A, B, C {` — the supertype list runs from the class header's colon to its body,
# or, for a body-less class (`class CaseOutboxRepositoryImpl(...) : Base(...), P`), to the next
# UNINDENTED line, i.e. the next top-level declaration. Not "the next blank line": a comment inside
# the supertype list is a blank line once comments are stripped (incentive's header, #11902).
CLASS_HEADER = re.compile(r"\bclass\s+\w+(?:(?!\n(?=[^\s}]))[^{])*", re.S)
RETENTION_SUPERTYPE = re.compile(r"\bSentOutboxRetention\b|\bAbstractPanacheOutboxRepository\s*<")
RETENTION_EXEMPT_OVERRIDE = re.compile(
    r"\boverride\s+val\s+sentRetentionExempt\s*:\s*Boolean\s*=\s*true\b"
)

# Each entry: why that outbox's SENT rows must not be purged yet. Measured 2026-10-03 (#11896).
EXEMPT: dict[str, str] = {
    "openbank-billing-service": (
        "Annual fee-summary reruns still use SENT billing_outbox rows as the issuance guard; "
        "keep retention off until the durable account/year key in #12311 is merged and deployed (#12187)"
    ),
    "openbank-case-coordinator-agent": (
        "GET /cases/{caseId} projects proposal evidence directly from SENT case_outbox rows; "
        "purge only after that evidence has an independent durable source (#11896)"
    ),
}


def strip_comments(src: str) -> str:
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    in_str = False
    while i < n:
        two = src[i:i + 2]
        c = src[i]
        if depth == 0 and not in_str and two == "//":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if not in_str and two == "/*":
            depth += 1
            i += 2
            continue
        if depth > 0 and two == "*/":
            depth -= 1
            i += 2
            continue
        if depth == 0:
            if c == '"':
                in_str = not in_str
            out.append(c)
        i += 1
    return "".join(out)


def sources(tree: pathlib.Path) -> list[str]:
    if not tree.is_dir():
        return []
    return [strip_comments(kt.read_text(encoding="utf-8", errors="replace")) for kt in tree.rglob("*.kt")]


def opts_in(src: str) -> bool:
    for m in CLASS_HEADER.finditer(src):
        header = m.group(0)
        colon = header.find(":")
        if colon >= 0 and RETENTION_SUPERTYPE.search(header[colon:]):
            return True
    return False


def scan(root: pathlib.Path) -> tuple[set[str], set[str], set[str]]:
    """(dispatcher owners, type-covered owners, owners with an explicit runtime exemption)."""
    owners: set[str] = set()
    covered: set[str] = set()
    runtime_exempt: set[str] = set()
    for module in sorted(root.glob("openbank-*")):
        main = sources(module / "src" / "main")
        if any(DISPATCHER_SUPERTYPE.search(s) for s in main):
            owners.add(module.name)
            if any(opts_in(s) for s in main):
                covered.add(module.name)
            if any(RETENTION_EXEMPT_OVERRIDE.search(s) for s in main):
                runtime_exempt.add(module.name)
    return owners, covered, runtime_exempt


def check(root: pathlib.Path, exempt: dict[str, str]) -> tuple[list[str], int]:
    owners, covered, runtime_exempt = scan(root)
    me = pathlib.Path(__file__).name
    findings: list[str] = []
    for m in sorted(owners - covered - exempt.keys()):
        findings.append(
            f"{m}: extends AbstractOutboxDispatcher but no src/main class implements SentOutboxRetention — "
            "its SENT outbox rows (and their payloads) are kept forever. Implement it on the outbox repository "
            "(`SentOutboxRetention by PanacheOutboxRetention(OutboxTableShape(\"<table>\"))`), or add a reasoned exemption to " + me
        )
    for m in sorted((exempt.keys() & covered) - runtime_exempt):
        findings.append(f"{m}: exempt but now implements SentOutboxRetention — remove the entry from {me}")
    for m in sorted(runtime_exempt - exempt.keys()):
        findings.append(f"{m}: disables SENT retention at runtime but has no reasoned exemption in {me}")
    for m in sorted(runtime_exempt - covered):
        findings.append(f"{m}: declares a runtime exemption without a SentOutboxRetention target")
    for m in sorted(exempt.keys() - owners):
        findings.append(f"{m}: exempt but no longer extends AbstractOutboxDispatcher — remove the entry from {me}")
    for m, why in exempt.items():
        if len(why.strip()) < 20:
            findings.append(f"{m}: exemption needs a reason, got {why!r}")
    return findings, len(owners)


def self_test() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)

        def write(rel: str, text: str) -> None:
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)

        disp = "class XDispatcher(m: DomainMetrics) : AbstractOutboxDispatcher(m)\n"
        # v1 opt-in, multi-line supertypes.
        write("openbank-a/src/main/kotlin/D.kt", disp)
        write("openbank-a/src/main/kotlin/R.kt", "class ARepo(c: Clock) :\n    APort,\n    SentOutboxRetention,\n    PanacheRepository<E> {\n}\n")
        # v2 opt-in via the kernel base, body-less class (case-coordinator's shape).
        write("openbank-e/src/main/kotlin/D.kt", disp)
        write("openbank-e/src/main/kotlin/R.kt", "@ApplicationScoped\nclass ERepo(c: Clock) :\n    AbstractPanacheOutboxRepository<E>(\n        S,\n        c,\n    ),\n    PanacheRepository<E>\n")
        # v1 opt-in by delegation, with a comment block inside the supertype list (incentive's shape).
        write("openbank-f/src/main/kotlin/D.kt", disp)
        write(
            "openbank-f/src/main/kotlin/R.kt",
            "class FRepo :\n    PanacheRepository<E>,\n    OutboxRepository,\n    // why this table is odd\n"
            "    // second line\n    SentOutboxRetention by PanacheOutboxRetention(\n        S,\n    ) {\n}\n",
        )
        # v2 opt-in via the kernel base.
        write("openbank-b/src/main/kotlin/D.kt", disp)
        write("openbank-b/src/main/kotlin/R.kt", "class BRepo(c: Clock) : AbstractPanacheOutboxRepository<E>(S, E::class.java, c), BPort {\n}\n")
        # Mentions only: a comment, a string, an import, and a method named purgeSent. None opts in.
        write("openbank-c/src/main/kotlin/D.kt", disp)
        write(
            "openbank-c/src/main/kotlin/R.kt",
            "import com.openbank.libs.persistence.outbox.SentOutboxRetention\n"
            "// class Fake : SentOutboxRetention {\n"
            "class CRepo : CPort {\n    val s = \"SentOutboxRetention\"\n    suspend fun purgeSent() = 0\n}\n",
        )
        write("openbank-d/src/main/kotlin/R.kt", "class DRepo : SentOutboxRetention {}\n")  # not an owner
        write("openbank-f/src/main/kotlin/D.kt", disp)
        write(
            "openbank-f/src/main/kotlin/R.kt",
            "class FRepo : AbstractPanacheOutboxRepository<E>(S, E::class.java, c) {\n"
            "    override val sentRetentionExempt: Boolean = true\n}\n",
        )
        reason = "a reason that is long enough to count"
        cases = [
            ("a, b covered and c, f exempt is clean", {"openbank-c": reason, "openbank-f": reason}, False),
            ("c without exemption is flagged (import/comment/string/method name do not count)", {}, True),
            ("an exempt module that opted in is stale", {"openbank-a": reason, "openbank-c": reason, "openbank-f": reason}, True),
            ("an exempt module that owns no dispatcher is stale", {"openbank-c": reason, "openbank-d": reason, "openbank-f": reason}, True),
            ("an exemption without a reason is flagged", {"openbank-c": "todo", "openbank-f": reason}, True),
            ("runtime exemption without a reason is flagged", {"openbank-c": reason}, True),
        ]
        for name, ex, expect in cases:
            findings, subjects = check(root, ex)
            if bool(findings) != expect or subjects != 5:
                print(f"SELF-TEST FAIL: {name}: findings={findings} subjects={subjects}")
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
    findings, subjects = check(pathlib.Path(args.root), EXEMPT)
    gatelib.subjects(subjects, "module(s) extending AbstractOutboxDispatcher")
    for f in findings:
        print(f"  {f}")
    print(f"{len(findings)} finding(s); {len(EXEMPT)} reasoned exemption(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
