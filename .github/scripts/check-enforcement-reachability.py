#!/usr/bin/env python3
"""A declared enforcement function must have a PRODUCTION caller (rules.yaml: enforcement_reachability).

Why this exists
---------------
`AuthorizationService.isAuthorizedForAmount` is the only amount-aware account guard in the fleet
and the only enforcer of ADR-0232's `perTransactionLimit` in account-service. Measured against
`origin/main` on 2026-08-03, every one of its call sites is in `AuthorizationServiceDelegationTest`
(issue #3615). The HTTP endpoint that looks like its front door,
`GET /api/v1/accounts/{id}/authorizations/check`, calls the amount-free `isAuthorized` instead.

Nothing in this repo could see that. The function compiles, it is a CDI bean method, its port
declares it, its unit tests are green and thorough — and they are green about a function no
request reaches. Coverage does not help either: a well-tested unreachable function has HIGHER
coverage than a lightly-tested reachable one. The same shape produced #3613 one layer down, where
`dailyLimit`/`monthlyLimit` were accepted by the API and counted by nothing.

What this checks
----------------
For each entry in `rules.yaml: enforcement_reachability.declared`, count call sites of the symbol
across every module's `src/main` Kotlin, excluding the files that DECLARE it (the implementation
and its port interface), and excluding declaration lines themselves. Then compare the measured
verdict against the declared `status`:

  status: reachable    -> at least one production call site, else FAIL
  status: unreachable  -> zero production call sites; a caller appearing FAILS too, asking for the
                          registry (and the ADR delivery-status row that quotes it) to be updated

The second direction is the point. A one-way check would let the entry rot into a permanent
"known broken" nobody revisits, and would stay silent on the day the gap is actually closed — the
`KNOWN_UNCOVERED`/baseline idiom this repo already uses in check-pact-provider-replay.py and
check-single-replica-rollout-strategy.py, where a stale declaration is itself a finding.

Deliberate limits
-----------------
Kotlin functions only. An unreachable HTTP endpoint or an unconsumed domain event is the same
defect class, but detecting a route's callers means reasoning across the fleet's HTTP clients and
the admin-ui, and detecting an unconsumed event is already
check-event-consumer-liveness.py's job. A narrow gate that is falsifiable beats a broad one that
guesses.

Comment stripping matters here: this repo's KDoc discusses the very symbols being counted (see
the `check-roles-allowed-realm.py` collision, ADR note in CLAUDE.md), and Kotlin block comments
NEST, so a naive non-greedy strip closes early on a KDoc containing `/*`.

Falsifiability
--------------
`--self-test` runs the counter over synthetic sources in both directions — a caller present, a
caller only inside a comment, a caller only in a string, and a declaration line alone — and
asserts the verdict each time, so a "clean" run has been shown capable of reporting dirty.

Usage: check-enforcement-reachability.py [--root .] [--rules ...] [--self-test] [--enforce]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - the runner image always has it
    print("::error::pyyaml unavailable — add it to the runner base image", file=sys.stderr)
    raise SystemExit(1)

REPO = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_RULES = "openbank-libs/governance/rules.yaml"


def strip_comments(src: str) -> str:
    """Remove Kotlin line comments, NESTING block comments, and string literals.

    Strings go too because a path or a message naming the symbol is prose about the function, not
    a call to it — same reason the comments go.
    """
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    while i < n:
        two = src[i : i + 2]
        if depth:
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "*/":
                depth -= 1
                i += 2
                continue
            out.append("\n" if src[i] == "\n" else " ")
            i += 1
            continue
        if two == "/*":
            depth = 1
            i += 2
            continue
        if two == "//":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if src[i] in ('"', "'"):
            quote = src[i]
            triple = src[i : i + 3] == quote * 3
            i += 3 if triple else 1
            while i < n:
                if not triple and src[i] == "\\":
                    i += 2
                    continue
                if triple and src[i : i + 3] == quote * 3:
                    i += 3
                    break
                if not triple and (src[i] == quote or src[i] == "\n"):
                    i += 1
                    break
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


def call_sites(src: str, symbol: str) -> int:
    """Count invocations of `symbol` in already-stripped Kotlin, ignoring its own declaration."""
    call = re.compile(rf"(?<![A-Za-z0-9_]){re.escape(symbol)}\s*\(")
    declaration = re.compile(rf"\bfun\s+{re.escape(symbol)}\s*\(")
    return sum(1 for line in src.splitlines() if call.search(line) and not declaration.search(line))


def production_sources(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(
        p
        for p in root.glob("openbank-*/src/main/kotlin/**/*.kt")
        if p.is_file()
    )


def measure(root: pathlib.Path, entry: dict) -> list[str]:
    """Return the production files (repo-relative) that call this entry's symbol."""
    symbol = entry["symbol"]
    declaring = {(root / f).resolve() for f in entry.get("declared_in", [])}
    hits: list[str] = []
    for path in production_sources(root):
        if path.resolve() in declaring:
            continue
        try:
            src = strip_comments(path.read_text(encoding="utf-8"))
        except UnicodeDecodeError:  # pragma: no cover
            continue
        if call_sites(src, symbol):
            hits.append(str(path.relative_to(root)))
    return hits


def findings(root: pathlib.Path, rules_path: pathlib.Path) -> list[str]:
    rules = yaml.safe_load(rules_path.read_text(encoding="utf-8")) or {}
    declared = ((rules.get("enforcement_reachability") or {}).get("declared")) or []
    out: list[str] = []
    for entry in declared:
        symbol = entry.get("symbol", "?")
        status = entry.get("status")
        issue = entry.get("issue")
        for f in entry.get("declared_in", []):
            if not (root / f).exists():
                out.append(f"{symbol}: declared_in path does not exist: {f} — registry is stale")
        callers = measure(root, entry)
        if status == "reachable" and not callers:
            out.append(
                f"{symbol}: declared `reachable` but no production call site exists. "
                f"Its tests are green about a function no request reaches "
                f"({entry.get('why', 'see rules.yaml')})."
            )
        elif status == "unreachable" and callers:
            out.append(
                f"{symbol}: declared `unreachable` (issue #{issue}) but is now called from "
                f"{', '.join(callers)}. If the enforcement path was wired, flip status to "
                f"`reachable` and update the ADR delivery-status row that quotes this entry."
            )
        elif status not in ("reachable", "unreachable"):
            out.append(f"{symbol}: status must be `reachable` or `unreachable`, got {status!r}")
    return out


SELF_TEST_CASES = [
    ("val ok = service.isAuthorizedForAmount(a, b, c, d)", 1, "a real call"),
    ("// service.isAuthorizedForAmount(a, b) is not wired yet", 0, "a call in a line comment"),
    ("/* outer /* nested */ isAuthorizedForAmount(x) */\nval n = 1", 0, "a call inside NESTED block comments"),
    ('val msg = "call isAuthorizedForAmount(x) here"', 0, "a call inside a string literal"),
    ("override suspend fun isAuthorizedForAmount(\n    accountId: UUID,\n): Boolean = false", 0, "the declaration"),
    ("val bad = notIsAuthorizedForAmount(x)", 0, "a longer identifier that merely contains the symbol"),
    ("fun caller() {\n    return isAuthorizedForAmount(x)\n}", 1, "a call in a body below a different declaration"),
]


def self_test() -> int:
    failures = 0
    for src, expected, label in SELF_TEST_CASES:
        got = call_sites(strip_comments(src), "isAuthorizedForAmount")
        ok = got == expected
        failures += 0 if ok else 1
        print(f"  [{'ok' if ok else 'FAIL'}] {label}: expected {expected}, got {got}")
    if failures:
        print(f"::error::enforcement-reachability self-test: {failures} case(s) failed")
        return 1
    print(f"enforcement-reachability self-test: {len(SELF_TEST_CASES)} cases pass (both directions)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(REPO))
    parser.add_argument("--rules", default=DEFAULT_RULES)
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    problems = findings(root, root / args.rules)
    for p in problems:
        print(f"::{'error' if args.enforce else 'warning'}::enforcement-reachability: {p}")
    if not problems:
        print("enforcement-reachability: every declared enforcement function matches its declared status")
    return 1 if (problems and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
