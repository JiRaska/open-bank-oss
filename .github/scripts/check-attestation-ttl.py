#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-attestation-ttl.py — the anti-rot half of `attestations.yaml` (Refs #2365).

WHAT THE FILE PROMISES, AND WHO KEPT IT
---------------------------------------
`openbank-libs/governance/attestations.yaml` is the only place production-readiness maturity is
entered by hand, and its own header states the anti-rot contract:

    ANTI-ROT: kazda atestace MA date + ttl_days. Po expiraci collector NEZAPOCITA
    bank-grade bonus a dimenze spadne zpet na derived skore. Zelena neni navzdy.

The decay half is implemented — `collect-prod-readiness.mjs:attestFresh()` refuses an attestation
older than its `ttl_days`, so the dimension does drop back to the derived score. The WARNING half
was implemented nowhere. An attestation lapsing therefore looks exactly like an attestation that
was never made: a cell quietly goes from 3 to 2, the matrix keeps rendering, and nothing anywhere
says a control expired. That is this repo's most-repeated failure class — a control whose lapse
looks like calm — and it is a live risk today, not a hypothetical: two of the three entries in the
file carry `ttl_days: 21` deliberately, precisely so they expire when the CI lane behind them dies.
The short TTL is the design. The silence around it was the gap.

WHAT THIS GATE DOES
-------------------
Reads the file and classifies every attestation by remaining life:

  expired   days_left < 0                     -> ::error, exit 1 under --enforce
  expiring  0 <= days_left <= --lead-days      -> ::warning, exit 0 (lead time, not a failure)
  valid     days_left > --lead-days            -> silent
  malformed unusable date / ttl_days / shape   -> ::error, exit 1 under --enforce

A malformed entry is a NAMED finding, never a crash and never a silent skip. That distinction is
the whole point: `attestFresh()` already treats a missing `date` as "not fresh" and defaults a
missing `ttl_days` to 365, so a typo'd entry degrades to either an invisible zero or a year of
unearned green, and in both directions the file still parses and the matrix still renders.

IT DOES NOT WRITE. EVER.
------------------------
There is no `--fix`, no `--renew`, no generator. An attestation is a human (or a named CI lane)
testifying that an event happened; a script that writes one manufactures the same false evidence
as a DR drill that reports `success` having restored nothing (#4247). The only honest responses to
an expiry are to perform the event again and record it, or to delete the entry and let the score
fall back to derived — which is why enforcement is never a deadlock: deleting a stale line is
always available, and is the truthful action.

SCOPE IS DERIVED, NOT HAND-KEPT
-------------------------------
Services and keys come from the parsed document itself — every top-level mapping, every key under
it. There is no list of expected services or expected keys anywhere in this file. A gate whose
coverage set is maintained separately from the artefacts it covers reads as *passing* when the list
is short, never as *unchecked*. Corollary worth stating because it is the one thing this gate
cannot see: it measures the attestations that EXIST, so it is silent about the ones missing from
the 20 x 3 = 60 target. That is #2365's substance and is not closable by code.

THE COMMENTED-OUT EXAMPLE MUST NOT COUNT
----------------------------------------
The file ships a worked example under `# Priklad (zakomentovany ...)` naming `ledger` with three
keys and dates in the past. Parsing with `yaml.safe_load` drops comments structurally, so the
example cannot be miscounted — but "structurally impossible" is a claim, and this repo does not
accept claims about a gate's blind spots, so `--self-test` feeds it that exact fixture and asserts
zero attestations.

DATES ARE THE TRAP
------------------
`date: 2026-07-26` unquoted is parsed by YAML into a `datetime.date` OBJECT, not a string, so any
handling that assumes `str` (`.split('-')`, a regex, `strptime`) fails on the shape the file
actually uses. All three of today's entries are unquoted. Both shapes are accepted here and
`--self-test` asserts they produce an IDENTICAL verdict, rather than merely that neither crashes.

Usage: python3 .github/scripts/check-attestation-ttl.py [--enforce] [--lead-days N]
                                                        [--file PATH] [--today YYYY-MM-DD]
                                                        [--self-test]
Exit:  0 clean or advisory, 1 expired/malformed under --enforce (2 on a self-test failure)
"""

from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import sys
import tempfile

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_FILE = REPO / "openbank-libs" / "governance" / "attestations.yaml"

# 14 days: the two live short-TTL entries are refreshed by WEEKLY lanes (api-fuzz.yml runs Tue+Thu,
# dast-zap-baseline.yml weekly), so a window shorter than two lane cycles could warn for the first
# time after the last chance to act on it had already passed.
DEFAULT_LEAD_DAYS = 14


class Finding:
    """One classified attestation. `kind` is one of expired/expiring/valid/malformed."""

    def __init__(self, service: str, key: str, kind: str, message: str, days_left: int | None = None):
        self.service = service
        self.key = key
        self.kind = kind
        self.message = message
        self.days_left = days_left

    def __str__(self) -> str:
        return f"{self.service}.{self.key}: {self.message}"


def _coerce_date(raw: object) -> dt.date | None:
    """Accept both YAML shapes: a `datetime.date` object and a `YYYY-MM-DD` string.

    `datetime.datetime` is checked FIRST — it is a subclass of `date`, so the obvious
    `isinstance(raw, dt.date)` would swallow it and carry a time component into the arithmetic.
    """
    if isinstance(raw, dt.datetime):
        return raw.date()
    if isinstance(raw, dt.date):
        return raw
    if isinstance(raw, str):
        try:
            return dt.datetime.strptime(raw.strip(), "%Y-%m-%d").date()
        except ValueError:
            return None
    return None


def classify(doc: object, today: dt.date, lead_days: int) -> list[Finding]:
    """Classify every attestation in a parsed document. Scope is the document, not a list."""
    findings: list[Finding] = []

    if doc is None:  # empty file, or a file that is entirely comments
        return findings
    if not isinstance(doc, dict):
        return [Finding("<file>", "<root>", "malformed", f"top level is {type(doc).__name__}, expected a mapping of services")]

    for service in sorted(doc):
        entries = doc[service]
        if entries is None:
            findings.append(Finding(str(service), "<none>", "malformed", "service has no attestations (empty mapping)"))
            continue
        if not isinstance(entries, dict):
            findings.append(Finding(str(service), "<root>", "malformed", f"expected a mapping of keys, got {type(entries).__name__}"))
            continue

        for key in sorted(entries):
            rec = entries[key]
            svc, k = str(service), str(key)

            if not isinstance(rec, dict):
                findings.append(Finding(svc, k, "malformed", f"expected a mapping with date/ttl_days, got {type(rec).__name__}"))
                continue

            if "date" not in rec:
                findings.append(Finding(svc, k, "malformed", "missing `date` — attestFresh() reads this as never-attested, silently"))
                continue
            date = _coerce_date(rec["date"])
            if date is None:
                findings.append(Finding(svc, k, "malformed", f"unparseable `date` {rec['date']!r} — expected YYYY-MM-DD"))
                continue

            if "ttl_days" not in rec:
                findings.append(Finding(svc, k, "malformed", "missing `ttl_days` — attestFresh() would default it to 365, a year of unearned green"))
                continue
            ttl_raw = rec["ttl_days"]
            if isinstance(ttl_raw, bool) or not isinstance(ttl_raw, int):
                findings.append(Finding(svc, k, "malformed", f"`ttl_days` {ttl_raw!r} is not an integer"))
                continue
            if ttl_raw <= 0:
                findings.append(Finding(svc, k, "malformed", f"`ttl_days` {ttl_raw} must be positive"))
                continue

            if date > today:
                findings.append(Finding(svc, k, "malformed", f"`date` {date.isoformat()} is in the future (today {today.isoformat()}) — an attestation testifies to a past event"))
                continue

            expiry = date + dt.timedelta(days=ttl_raw)
            days_left = (expiry - today).days
            detail = f"attested {date.isoformat()}, ttl {ttl_raw}d, expires {expiry.isoformat()}"

            if days_left < 0:
                findings.append(Finding(svc, k, "expired", f"EXPIRED {abs(days_left)}d ago ({detail}) — the dimension has already fallen back to its derived score", days_left))
            elif days_left <= lead_days:
                findings.append(Finding(svc, k, "expiring", f"expires in {days_left}d ({detail}) — re-attest from a real event, or delete the entry", days_left))
            else:
                findings.append(Finding(svc, k, "valid", f"{days_left}d left ({detail})", days_left))

    return findings


def run_check(path: pathlib.Path, today: dt.date, lead_days: int, enforce: bool, quiet: bool = False) -> tuple[int, list[Finding]]:
    """Parse + classify + report. Returns (exit_code, findings)."""
    if not path.exists():
        if not quiet:
            print(f"::error::check-attestation-ttl: {path} does not exist")
        return 1, [Finding("<file>", "<missing>", "malformed", "file does not exist")]

    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        if not quiet:
            print(f"::error::check-attestation-ttl: {path} is not valid YAML: {exc}")
        return 1, [Finding("<file>", "<parse>", "malformed", "not valid YAML")]

    findings = classify(doc, today, lead_days)
    by = {kind: [f for f in findings if f.kind == kind] for kind in ("expired", "expiring", "valid", "malformed")}
    level = "error" if enforce else "warning"

    if not quiet:
        for f in by["malformed"]:
            print(f"::{level}::check-attestation-ttl: {f}")
        for f in by["expired"]:
            print(f"::{level}::check-attestation-ttl: {f}")
        for f in by["expiring"]:
            print(f"::warning::check-attestation-ttl: {f}")
        for f in by["valid"]:
            print(f"  ok  {f}")

        # The count is MEASURED and printed every run. #2365's title says "1/60"; the file has
        # carried three entries since 2026-07-28 and the title was never corrected. A number
        # restated from a title is not a number anyone measured.
        print(
            f"\n{len(findings)} attestation(s) in {path.relative_to(REPO) if path.is_relative_to(REPO) else path}: "
            f"{len(by['valid'])} valid, {len(by['expiring'])} expiring within {lead_days}d, "
            f"{len(by['expired'])} EXPIRED, {len(by['malformed'])} malformed (today {today.isoformat()})."
        )
        if by["expired"] or by["malformed"]:
            print(
                "An expired attestation is a control that has already lapsed — the collector stopped "
                "counting it and nothing said so. Re-attest from a real event, or delete the entry and "
                "let the dimension show its derived score. Do not extend ttl_days to clear this."
            )

    failing = by["expired"] + by["malformed"]
    return (1 if (failing and enforce) else 0), findings


# ---------------------------------------------------------------------------------------
# --self-test: proof the RED is reachable, and that each case is distinguished by NAME.
#
# Every case asserts (a) the exit code and (b) which finding kinds came back for which keys.
# Asserting only the exit code would let one broken classifier hide behind another — a malformed
# entry and an expired one both exit 1, so a gate that called everything malformed would pass an
# exit-code-only harness while reporting nonsense.
# ---------------------------------------------------------------------------------------

TODAY = dt.date(2026, 8, 9)

CASES: list[tuple[str, str, int, dict[str, str]]] = [
    (
        "expired",
        "svc-a:\n  pentest: { date: 2026-07-01, ttl_days: 21, by: ci, ref: r }\n",
        1,
        {"svc-a.pentest": "expired"},
    ),
    (
        "inside the lead window",
        "svc-b:\n  pentest: { date: 2026-07-26, ttl_days: 21, by: ci, ref: r }\n",
        0,
        {"svc-b.pentest": "expiring"},
    ),
    (
        "comfortably valid",
        "svc-c:\n  restore_drill: { date: 2026-07-26, ttl_days: 180, by: jiri, ref: r }\n",
        0,
        {"svc-c.restore_drill": "valid"},
    ),
    (
        "missing ttl_days",
        "svc-d:\n  pentest: { date: 2026-07-26, by: ci, ref: r }\n",
        1,
        {"svc-d.pentest": "malformed"},
    ),
    (
        "missing date",
        "svc-e:\n  pentest: { ttl_days: 21, by: ci, ref: r }\n",
        1,
        {"svc-e.pentest": "malformed"},
    ),
    (
        "unparseable date",
        "svc-f:\n  pentest: { date: 'not-a-date', ttl_days: 21 }\n",
        1,
        {"svc-f.pentest": "malformed"},
    ),
    (
        "ttl_days not an integer",
        "svc-g:\n  pentest: { date: 2026-07-26, ttl_days: soon }\n",
        1,
        {"svc-g.pentest": "malformed"},
    ),
    (
        "date in the future",
        "svc-h:\n  pentest: { date: 2027-01-01, ttl_days: 21 }\n",
        1,
        {"svc-h.pentest": "malformed"},
    ),
    (
        "entry is not a mapping",
        "svc-i:\n  pentest: done\n",
        1,
        {"svc-i.pentest": "malformed"},
    ),
    (
        "empty file",
        "",
        0,
        {},
    ),
    (
        "file that is entirely comments",
        "# nothing here yet\n# ledger:\n#   pentest: { date: 2020-01-01, ttl_days: 1 }\n",
        0,
        {},
    ),
    (
        "the shipped commented-out example must not count",
        "# Priklad (zakomentovany):\n"
        "#\n"
        "# ledger:\n"
        "#   restore_drill: { date: 2026-06-20, ttl_days: 180, by: jiri, ref: runbook-0006 }\n"
        "#   dr_drill:      { date: 2026-06-20, ttl_days: 180, by: jiri, ref: runbook-0006 }\n"
        "#   pentest:       { date: 2026-05-01, ttl_days: 365, by: ext,  ref: schemathesis-fleet }\n",
        0,
        {},
    ),
]


def _self_test() -> int:
    failures: list[str] = []

    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)

        for label, content, want_exit, want_kinds in CASES:
            path = tmp / "att.yaml"
            path.write_text(content, encoding="utf-8")
            code, findings = run_check(path, TODAY, DEFAULT_LEAD_DAYS, enforce=True, quiet=True)
            got_kinds = {f"{f.service}.{f.key}": f.kind for f in findings}
            if code != want_exit:
                failures.append(f"[{label}] exit {code}, expected {want_exit}")
            if got_kinds != want_kinds:
                failures.append(f"[{label}] classified {got_kinds}, expected {want_kinds}")

        # A `date` YAML parsed into a datetime.date OBJECT must produce a verdict IDENTICAL to the
        # quoted-string form. Asserting merely that neither crashes would pass a gate that silently
        # treated the object form as malformed — which is the shape all three real entries use.
        for ttl, expect in ((21, "expiring"), (180, "valid"), (1, "expired")):
            unquoted = tmp / "unquoted.yaml"
            quoted = tmp / "quoted.yaml"
            unquoted.write_text(f"svc:\n  pentest: {{ date: 2026-07-26, ttl_days: {ttl} }}\n", encoding="utf-8")
            quoted.write_text(f"svc:\n  pentest: {{ date: '2026-07-26', ttl_days: {ttl} }}\n", encoding="utf-8")
            u_code, u_find = run_check(unquoted, TODAY, DEFAULT_LEAD_DAYS, enforce=True, quiet=True)
            q_code, q_find = run_check(quoted, TODAY, DEFAULT_LEAD_DAYS, enforce=True, quiet=True)
            u = [(f.kind, f.days_left) for f in u_find]
            q = [(f.kind, f.days_left) for f in q_find]
            if u != q or u_code != q_code:
                failures.append(f"[date-object ttl={ttl}] unquoted {u}/{u_code} != quoted {q}/{q_code}")
            if not u or u[0][0] != expect:
                failures.append(f"[date-object ttl={ttl}] got {u}, expected kind {expect}")

        # Advisory-vs-enforce must differ on the SAME input, or `--enforce` is decorative.
        expired = tmp / "expired.yaml"
        expired.write_text("svc:\n  pentest: { date: 2026-07-01, ttl_days: 21 }\n", encoding="utf-8")
        if run_check(expired, TODAY, DEFAULT_LEAD_DAYS, enforce=False, quiet=True)[0] != 0:
            failures.append("[advisory] an expired entry exited non-zero without --enforce")
        if run_check(expired, TODAY, DEFAULT_LEAD_DAYS, enforce=True, quiet=True)[0] != 1:
            failures.append("[enforce] an expired entry exited 0 with --enforce")

        # The lead window must be the thing that decides `expiring` vs `valid` — with a 0-day
        # window nothing is expiring, so a hardcoded threshold hiding behind the flag is caught.
        window = tmp / "window.yaml"
        window.write_text("svc:\n  pentest: { date: 2026-07-26, ttl_days: 21 }\n", encoding="utf-8")
        if [f.kind for f in run_check(window, TODAY, 0, enforce=True, quiet=True)[1]] != ["valid"]:
            failures.append("[lead-days] --lead-days 0 still reported an entry as expiring")
        if [f.kind for f in run_check(window, TODAY, 30, enforce=True, quiet=True)[1]] != ["expiring"]:
            failures.append("[lead-days] --lead-days 30 did not report a 7-day entry as expiring")

        # A missing file must be a named failure, not a clean run over nothing.
        if run_check(tmp / "nope.yaml", TODAY, DEFAULT_LEAD_DAYS, enforce=True, quiet=True)[0] != 1:
            failures.append("[missing file] exited 0")

    if failures:
        for f in failures:
            print(f"::error::check-attestation-ttl --self-test: {f}")
        print(f"\nSELF-TEST FAILED: {len(failures)} assertion(s).")
        return 2
    print(f"OK: --self-test passed ({len(CASES)} fixtures + date-object equivalence, enforce/advisory split, lead-window, missing file).")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true", help="exit 1 on expired or malformed attestations")
    parser.add_argument("--lead-days", type=int, default=DEFAULT_LEAD_DAYS, help=f"warn this many days before expiry (default {DEFAULT_LEAD_DAYS})")
    parser.add_argument("--file", type=pathlib.Path, default=DEFAULT_FILE, help="attestations file to read")
    parser.add_argument("--today", help="override today's date (YYYY-MM-DD) — testing only")
    parser.add_argument("--self-test", action="store_true", help="prove the gate's RED is reachable")
    args = parser.parse_args()

    if args.self_test:
        return _self_test()

    today = dt.date.today()
    if args.today:
        parsed = _coerce_date(args.today)
        if parsed is None:
            print(f"::error::check-attestation-ttl: --today {args.today!r} is not YYYY-MM-DD")
            return 1
        today = parsed

    if args.lead_days < 0:
        print("::error::check-attestation-ttl: --lead-days must not be negative")
        return 1

    code, _ = run_check(args.file, today, args.lead_days, args.enforce)
    return code


if __name__ == "__main__":
    sys.exit(main())
