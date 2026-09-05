#!/usr/bin/env python3
"""Guard: no `Instant = Instant.EPOCH` default in production Kotlin sources.

WHY THIS EXISTS: an `Instant.EPOCH` default on a field meaning "when did this happen" is a lie
every test agrees with. `AuditEvent.timestamp` and `FlagExposure.timestamp` both defaulted to it;
23 of the 25 fleet `AuditEvent(` sites took the default, and `isNotNull()` passes against
1970-01-01 — so nothing caught it until #3882. The companion defect, one step later, is a
sentinel that is right for a REPORT and wrong the day something ALERTS on it:
`DomainMetrics.registerWorkflowLiveness` seeded its age gauge from `Instant.EPOCH`, and
ADR-0237's `WorkflowLivenessStale` then fired 15 minutes after every deploy, for every daily
workflow, until that workflow's next success (#2239 Gap 2, fixed by #4208).

WHAT IT CHECKS: every `openbank-*/src/main/kotlin/**.kt`, comments stripped (a fix comment
explaining why EPOCH is wrong must not trip the guard, and a violation must not be hideable in a
comment). Any `… : Instant = Instant.EPOCH` default — field, constructor parameter, or function
parameter — is flagged. A default of EPOCH is never the right answer in production code: the
correct shapes are a required parameter (caller supplies the time), a `Clock` injection, or
`Instant.now()` at the call site that owns the meaning.

BASELINE RATCHET: the fleet carried 58 occurrences when this guard was written (2026-09-03,
audit follow-up #8344 family). They are baselined in
`.github/gates/epoch-instant-default-baseline.txt` as `path::trimmed-declaration#<n>`
(`#<n>` disambiguates identical lines in one file), so:
  - a NEW occurrence fails the gate (the ratchet only tightens), and
  - a baseline entry whose occurrence DISAPPEARS also fails — the list cannot rot, and the fix
    PR removes the line as part of the fix (same idiom as check-kafka-dotted-keys.py, #2945).
Line numbers are deliberately NOT in the baseline: they drift under every unrelated edit.

ENFORCED: findings are ::error:: annotations and exit 1.

stdlib only. Usage: check-no-epoch-instant-default.py [--root .] [--enforce] [--self-test]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import gatelib

EPOCH_DEFAULT_RE = re.compile(r":\s*Instant\s*=\s*Instant\.EPOCH\b")
BASELINE = ".github/gates/epoch-instant-default-baseline.txt"


def strip_comments(lines: list[str]) -> list[str]:
    """Blank `//` comments and (possibly nested) `/* … */` blocks, keeping line numbering.

    Kotlin block comments NEST, so depth is counted, not boolean — with a boolean a KDoc
    explaining the rule becomes a violation of the rule it documents (#2450 precedent in
    check-no-runblocking-in-scheduled.py).
    """
    out: list[str] = []
    depth = 0
    for line in lines:
        buf: list[str] = []
        i = 0
        while i < len(line):
            two = line[i:i + 2]
            if depth > 0:
                if two == "*/":
                    depth -= 1
                    i += 2
                    continue
                if two == "/*":
                    depth += 1
                    i += 2
                    continue
                i += 1
                continue
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "//":
                break
            buf.append(line[i])
            i += 1
        out.append("".join(buf))
    return out


def find_occurrences(root: pathlib.Path) -> dict[str, tuple[int, str]]:
    """All occurrences as {"<path>::<trimmed line>#<n>": (line_no, trimmed)} over src/main only.

    `#<n>` is the 1-based index of this occurrence among IDENTICAL lines in the same file.
    Without it, a file carrying the same default twelve times (Card.kt's `now:` parameters)
    collapses to one baseline key — and the ratchet then cannot tell "one of the twelve was
    fixed" from "a thirteenth was added".
    """
    found: dict[str, tuple[int, str]] = {}
    for kt in gatelib.rglob(root, "openbank-*/src/main/**/*.kt"):
        code = strip_comments(gatelib.read_text(kt).splitlines())
        seen: dict[str, int] = {}
        for idx, line in enumerate(code, start=1):
            if EPOCH_DEFAULT_RE.search(line):
                rel = kt.relative_to(root).as_posix()
                text = line.strip()
                seen[text] = seen.get(text, 0) + 1
                found[f"{rel}::{text}#{seen[text]}"] = (idx, text)
    return found


def load_baseline(root: pathlib.Path) -> set[str]:
    path = root / BASELINE
    if not path.exists():
        return set()
    return {
        ln.strip()
        for ln in gatelib.read_text(path).splitlines()
        if ln.strip() and not ln.startswith("#")
    }


def self_test() -> int:
    """Falsify the detector against fixtures whose answer is known."""
    fails: list[str] = []

    def flagged(src: str) -> bool:
        code = strip_comments(src.splitlines())
        return any(EPOCH_DEFAULT_RE.search(line) for line in code)

    def case(label: str, src: str, want: bool) -> None:
        got = flagged(src)
        if got != want:
            fails.append(f"{label}: expected flagged={want}, got {got}")

    case("a data-class timestamp default must be FLAGGED", """
        data class AuditEvent(val timestamp: Instant = Instant.EPOCH)
    """, True)

    case("a var entity default must be FLAGGED", """
        var createdAt: Instant = Instant.EPOCH
    """, True)

    case("a function parameter default must be FLAGGED", """
        fun activate(now: Instant = Instant.EPOCH) = also { }
    """, True)

    case("the fix — a required parameter — is CLEAN", """
        data class AuditEvent(val timestamp: Instant)
    """, False)

    case("Instant.now() is CLEAN (the call site owns the meaning)", """
        var createdAt: Instant = Instant.now()
    """, False)

    case("a comment mentioning the pattern is CLEAN", """
        // never write: val timestamp: Instant = Instant.EPOCH — see #3882
        val timestamp: Instant
    """, False)

    case("a NESTED block comment mentioning the pattern is CLEAN", """
        /* outer /* val x: Instant = Instant.EPOCH */ still comment */
        val timestamp: Instant
    """, False)

    case("a different default instant constant is out of scope (CLEAN)", """
        val cutoff: Instant = Instant.MAX
    """, False)

    if fails:
        for f in fails:
            print(f"SELF-TEST FAILURE: {f}")
        return 1
    print(f"self-test OK ({8} cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true",
                    help="exit 1 on findings (default in this script; kept for flag parity)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    found = find_occurrences(root)
    baseline = load_baseline(root)

    gatelib.subjects(len(found) + len(baseline), "epoch-default occurrences + baseline entries")

    rc = 0
    for key, (line_no, text) in sorted(found.items()):
        if key not in baseline:
            path = key.split("::", 1)[0]
            print(f"::error file={path},line={line_no}::new `Instant = Instant.EPOCH` default "
                  f"({text}) — make the caller supply the time, inject a Clock, or use "
                  f"Instant.now() at the owning call site (#3882, #4208). If this is genuinely "
                  f"not event time, baseline it in {BASELINE} with the reason in the PR.")
            rc = 1
    for entry in sorted(baseline - set(found)):
        print(f"::error::baseline entry no longer present in the tree: {entry} — remove it from "
              f"{BASELINE} in the same PR that removes the occurrence (the list cannot rot).")
        rc = 1

    if rc == 0:
        print(f"OK: {len(found)} occurrence(s), all baselined; no new `Instant.EPOCH` defaults.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
