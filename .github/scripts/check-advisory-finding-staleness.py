#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""An advisory gate's "why this isn't enforced yet" claim must be dated, and re-dated.

WHY THIS EXISTS (ADR-0254)
--------------------------
`incluster-hostname-resolution` shipped advisory with a triage note claiming all six
findings were dead defaults safely overridden elsewhere. Three were live: settlement-service
and onboarding-service dialled hostnames resolving in no namespace, on wrong ports (CLAUDE.md,
"An advisory gate's 'these findings are all benign' note is an unverified claim, and advisory
mode is what removes the pressure to check it"). The note was written once and never
re-examined. Nothing in `gates.yaml` distinguishes a claim verified yesterday from one verified
the day the gate was written and never looked at again — a red advisory check and a
verified-benign one are, from the manifest alone, indistinguishable.

This does not re-verify the underlying claim — that is each gate's own job, or a human's. It
enforces the one structural property CLAUDE.md's own conclusion names: "if you write 'known,
benign', write what you checked and when; if you can't, leave the finding untriaged."

WHAT THIS CHECKS
----------------
Every gate with `mode: advisory` in `gates.yaml` must carry a `verified:` string starting with
an ISO date (`YYYY-MM-DD — <what was reconfirmed>`). The gate fails when:

  * a `mode: advisory` gate has no `verified:` field and is not in the DEBT baseline below
    (ratchet, same shape as #4339's self-test/subject-floor debt lists);
  * a `verified:` date is more than `MAX_AGE_DAYS` old — 180 days, roughly two release
    cycles for the advisory-graduation targets already declared against ADR-0144.

WHAT "TODAY" MEANS WITHOUT A CLOCK
-----------------------------------
This script has no access to wall-clock "now" that is safe to hard-code (a self-test must
produce the same verdict every time it runs, in six months as much as today). `--today` takes
an explicit ISO date; the caller (CI) supplies `$(date -u +%F)`. Never a bare `date.today()` —
that would make the self-test's "is this date stale" cases silently drift true or false as
real time passes, which is the same class of clock-dependent flakiness `ADR-0100`/the
clock-injection gate exists to forbid in application code.

Usage:  check-advisory-finding-staleness.py --today YYYY-MM-DD [--enforce]
        check-advisory-finding-staleness.py --self-test
"""

from __future__ import annotations

import argparse
import datetime
import pathlib
import re
import sys

import yaml

MANIFEST = ".github/gates/gates.yaml"
MAX_AGE_DAYS = 180
DATE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})\s*(?:—|--|-)\s*\S")

# Advisory gates with no `verified:` as of 2026-08-09, when this check was added. Growing this
# needs a reason a reviewer accepts (repo rule: exemptions are what a human has to justify).
DEBT_MARKER = "debt — no verified: field yet (baselined 2026-08-09, #4339/ADR-0254)"
DEBT: dict[str, str] = {}


def load(root="."):
    f = pathlib.Path(root) / MANIFEST
    if not f.exists():
        raise FileNotFoundError(f"{MANIFEST} not found")
    gates = (yaml.safe_load(f.read_text()) or {}).get("gates")
    if not gates:
        raise ValueError(f"{MANIFEST}: no gates found — refusing to report a pass")
    return gates


def parse_date(value) -> tuple[datetime.date | None, str | None]:
    """Return (date, error). `error` is set for a `verified:` field present but malformed —
    that must be a DIFFERENT, louder failure than "absent", or a typo'd date silently behaves
    like there was never a claim to check."""
    m = DATE_RE.match(str(value or ""))
    if not m:
        return None, f"verified: {value!r} does not start with 'YYYY-MM-DD — <what>'"
    try:
        return datetime.date.fromisoformat(m.group(1)), None
    except ValueError:
        return None, f"verified: {value!r} has an invalid date"


def analyse(gates, today: datetime.date, debt: dict, max_age: int = MAX_AGE_DAYS):
    """Return (undeclared, malformed, stale, stale_debt)."""
    undeclared, malformed, stale = [], [], []
    ids = {g.get("id") for g in gates}
    for g in gates:
        if g.get("mode") != "advisory":
            continue
        gid = g.get("id")
        verified = g.get("verified")
        if verified is None:
            if gid not in debt:
                undeclared.append(gid)
            continue
        date, err = parse_date(verified)
        if err:
            malformed.append(f"{gid}: {err}")
            continue
        age = (today - date).days
        if age > max_age:
            stale.append(f"{gid}: verified {date.isoformat()}, {age} days ago (max {max_age})")

    stale_debt = [gid for gid in debt if gid not in ids]
    return undeclared, malformed, stale, stale_debt


def report(undeclared, malformed, stale, stale_debt, enforce):
    bad = False
    for gid in undeclared:
        print(f"::error::{gid}: advisory gate with no `verified:` field and no DEBT baseline "
              f"entry. An advisory 'not enforced yet' claim with no date is one nobody can tell "
              f"apart from a stale one — add `verified: \"YYYY-MM-DD — <what you checked>\"`.",
              file=sys.stderr)
        bad = True
    for msg in malformed:
        print(f"::error::advisory-finding-staleness: {msg}", file=sys.stderr)
        bad = True
    for msg in stale:
        print(f"::error::advisory-finding-staleness: {msg} — re-confirm the advisory reasoning "
              f"still holds and bump the date, or graduate/remove the gate.", file=sys.stderr)
        bad = True
    for gid in stale_debt:
        print(f"::error::advisory-finding-staleness DEBT entry '{gid}' names a gate that no "
              f"longer exists — remove it.", file=sys.stderr)
        bad = True
    if bad and not enforce:
        print("::warning::advisory-finding-staleness found violations (advisory run)")
        return 0
    return 1 if bad else 0


def self_test() -> int:
    fails = []
    today = datetime.date(2026, 8, 9)

    def case(label, gates, debt, want_u, want_m, want_s, want_sd):
        u, m, s, sd = analyse(gates, today, debt)
        got = (sorted(u), sorted(x.split(":")[0] for x in m), sorted(x.split(":")[0] for x in s),
               sorted(sd))
        exp = (sorted(want_u), sorted(want_m), sorted(want_s), sorted(want_sd))
        if got != exp:
            fails.append(f"{label}: expected {exp}, got {got}")

    enf = {"id": "e", "mode": "enforced"}  # never inspected — the non-advisory control
    case("an enforced gate is never inspected", [enf], {}, [], [], [], [])
    case("a fresh verified: date is clean",
         [{"id": "a", "mode": "advisory", "verified": "2026-08-01 — checked X"}],
         {}, [], [], [], [])
    case("no verified: and no debt entry is flagged",
         [{"id": "a", "mode": "advisory"}], {}, ["a"], [], [], [])
    case("no verified: but baselined as debt is accepted",
         [{"id": "a", "mode": "advisory"}], {"a": "d"}, [], [], [], [])
    case("a verified: older than 180 days is stale",
         [{"id": "a", "mode": "advisory", "verified": "2025-12-01 — checked X"}],
         {}, [], [], ["a"], [])
    case("exactly at the boundary (180 days) is still clean",
         [{"id": "a", "mode": "advisory",
           "verified": (today - datetime.timedelta(days=180)).isoformat() + " — checked X"}],
         {}, [], [], [], [])
    case("one day past the boundary is stale",
         [{"id": "a", "mode": "advisory",
           "verified": (today - datetime.timedelta(days=181)).isoformat() + " — checked X"}],
         {}, [], [], ["a"], [])
    case("a malformed date is its own error, not silently 'absent'",
         [{"id": "a", "mode": "advisory", "verified": "not-a-date"}],
         {}, [], ["a"], [], [])
    case("a debt entry for a gate that no longer exists is stale",
         [enf], {"gone": "d"}, [], [], [], ["gone"])

    # Exit-code contract, silenced (report() prints ::error to stderr).
    import contextlib
    import io
    sink = io.StringIO()
    with contextlib.redirect_stderr(sink), contextlib.redirect_stdout(sink):
        rc_adv = report(["x"], [], [], [], enforce=False)
        rc_enf = report(["x"], [], [], [], enforce=True)
        rc_ok = report([], [], [], [], enforce=True)
    if rc_adv != 0:
        fails.append("advisory mode did not downgrade a violation to 0")
    if rc_enf != 1:
        fails.append("--enforce did not fail on a violation")
    if rc_ok != 0:
        fails.append("a clean run did not exit 0")

    try:
        load(root="/nonexistent-root-for-self-test")
        fails.append("a missing manifest did not raise (would report a false clean)")
    except (FileNotFoundError, ValueError):
        pass

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: advisory-finding-staleness is falsifiable (12 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--today", help="YYYY-MM-DD — required outside --self-test")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not args.today:
        sys.stderr.write("::error::--today YYYY-MM-DD is required (pass $(date -u +%F))\n")
        return 2
    try:
        today = datetime.date.fromisoformat(args.today)
        gates = load(args.root)
    except (FileNotFoundError, ValueError) as exc:
        sys.stderr.write(f"::error::{exc}\n")
        return 1

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import gatelib

    advisory_count = len([g for g in gates if g.get("mode") == "advisory"])
    gatelib.subjects(advisory_count, "advisory-mode gates")

    u, m, s, sd = analyse(gates, today, DEBT)
    print(f"advisory-finding-staleness: {advisory_count} advisory gate(s) as of {today.isoformat()} "
          f"— {len(u)} undeclared, {len(m)} malformed, {len(s)} stale (>{MAX_AGE_DAYS}d), "
          f"{len(DEBT)} baselined as debt.")
    return report(u, m, s, sd, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
