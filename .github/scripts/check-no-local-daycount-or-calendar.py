#!/usr/bin/env python3
"""Guard: no service carries its own day-count or business-day calendar logic (ADR-0344 D1).

WHY
  ADR-0344 D1 puts day counts, calendars and roll conventions in ONE place — openbank-libs-domain
  (calendars) and openbank-libs-finance (day counts, schedules). A private `DayCount` object or a
  local `nextBusinessDay` in a service is a second implementation that drifts from the first: a
  hard-coded 365 in one service and 360 in another for the same product is a reconciliation
  break nobody sees until the month-end numbers disagree.

WHAT IT CHECKS
  Every `*/src/main/**/*.kt` outside `openbank-libs-domain` and `openbank-libs-finance`, comments
  stripped, for:
    daycount-type   `object DayCount` / `enum class DayCount`
    weekend-fn      `fun isWeekend(`
    next-bd-fn      `fun nextBusinessDay(`
    prev-bd-fn      `fun previousBusinessDay(`
    year-basis      a constant named like a year basis (`DAYS_IN_YEAR`, `DAYS_PER_YEAR`,
                    `DAY_COUNT_BASIS`, `DAYS_ACT360`, ...) assigned 360 or 365
  RATCHET: today's offenders are listed in .github/gates/baselines/local-daycount.txt (#11670).
  A finding not in the baseline fails; a baseline entry that no longer matches anything is STALE
  and fails too — the debt was paid, delete the line — so the baseline can only shrink.

Exit codes: 0 clean, 1 new finding or stale baseline entry, 2 cannot run.

    python3 .github/scripts/check-no-local-daycount-or-calendar.py [--root .] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

BASELINE = ".github/gates/baselines/local-daycount.txt"
EXEMPT_MODULES = ("openbank-libs-domain", "openbank-libs-finance")

PATTERNS = (
    ("daycount-type", re.compile(r"\b(?:object|enum\s+class)\s+(DayCount)\b")),
    ("weekend-fn", re.compile(r"\bfun\s+(isWeekend)\s*\(")),
    ("next-bd-fn", re.compile(r"\bfun\s+(nextBusinessDay)\s*\(")),
    ("prev-bd-fn", re.compile(r"\bfun\s+(previousBusinessDay)\s*\(")),
    ("year-basis", re.compile(
        r"\bval\s+([A-Z0-9_]*(?:DAYS_(?:IN|PER)_YEAR|DAY_COUNT_BASIS|YEAR_BASIS|DAYS_ACT_?36[05])[A-Z0-9_]*)"
        r"\s*(?::\s*[\w.]+)?\s*=\s*(?:BigDecimal(?:\.valueOf)?\(\s*\"?)?36[05](?:\.0)?[LDd]?\b")),
)


def strip_comments(text: str) -> str:
    """Drop /* */ and // comments, keeping line numbers (string literals are left alone)."""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def scan(root: pathlib.Path):
    """Returns ({key: 'path:line'}, files_scanned). key = 'path|kind|symbol'."""
    findings, n = {}, 0
    for path in sorted(root.glob("*/src/main/**/*.kt")):
        rel = path.relative_to(root).as_posix()
        if rel.split("/", 1)[0] in EXEMPT_MODULES:
            continue
        n += 1
        text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for lineno, line in enumerate(text.splitlines(), 1):
            for kind, rx in PATTERNS:
                m = rx.search(line)
                if m:
                    findings.setdefault(f"{rel}|{kind}|{m.group(1)}", f"{rel}:{lineno}")
    return findings, n


def load_baseline(root: pathlib.Path):
    path = root / BASELINE
    if not path.exists():
        return None
    entries = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip() if not raw.lstrip().startswith("#") else ""
        if line:
            entries[line] = raw
    return entries


def evaluate(root: pathlib.Path):
    baseline = load_baseline(root)
    if baseline is None:
        return 2, [f"cannot run: baseline {BASELINE} missing"], 0
    findings, n = scan(root)
    errors = []
    for key, where in sorted(findings.items()):
        if key not in baseline:
            _, kind, sym = key.split("|")
            errors.append(f"{where}: local {kind} `{sym}` — use openbank-libs-domain calendars / "
                          f"openbank-libs-finance day counts (ADR-0344 D1). New offenders are not baselined.")
    for key in sorted(baseline):
        if key not in findings:
            errors.append(f"STALE baseline entry `{key}` in {BASELINE} matches nothing — delete the line")
    return (1 if errors else 0), errors, n


def self_test() -> int:
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        src = t / "openbank-x-service/src/main/kotlin/X.kt"
        src.parent.mkdir(parents=True)
        (t / BASELINE).parent.mkdir(parents=True)
        lib = t / "openbank-libs-domain/src/main/kotlin/Cal.kt"
        lib.parent.mkdir(parents=True)
        lib.write_text("object DayCount {\n fun isWeekend(d: Int) = true\n}\n")

        def setup(code, baseline=""):
            src.write_text(code)
            (t / BASELINE).write_text(baseline)
            return evaluate(t)

        rc, e, _ = setup("class X\n")
        cases.append(("known-negative: clean service, empty baseline -> 0 (exempt lib ignored)", rc == 0 and not e))
        for code, label in [
            ("object DayCount { }\n", "object DayCount"),
            ("enum class DayCount { ACT_360 }\n", "enum class DayCount"),
            ("fun isWeekend(d: LocalDate) = false\n", "fun isWeekend("),
            ("    fun nextBusinessDay(d: LocalDate): LocalDate = d\n", "fun nextBusinessDay("),
            ("fun previousBusinessDay(d: LocalDate) = d\n", "fun previousBusinessDay("),
            ("private const val DAYS_IN_YEAR = 365L\n", "DAYS_IN_YEAR = 365L"),
            ("const val DAY_COUNT_BASIS = 365\n", "DAY_COUNT_BASIS = 365"),
            ("private const val DAYS_IN_YEAR_ACT360 = 360\n", "DAYS_IN_YEAR_ACT360 = 360"),
            ("private val DAYS_PER_YEAR: BigDecimal = BigDecimal(\"360\")\n", "BigDecimal(\"360\")"),
        ]:
            rc, e, _ = setup(code)
            cases.append((f"known-positive: {label} fails", rc == 1 and len(e) == 1))
        rc, e, _ = setup("// object DayCount\n/* fun isWeekend( */\nconst val MAX_TENOR_DAYS = 365\n")
        cases.append(("known-negative: comments and unrelated 365 constants pass", rc == 0))
        key = "openbank-x-service/src/main/kotlin/X.kt|weekend-fn|isWeekend"
        rc, e, _ = setup("fun isWeekend(d: Int) = true\n", f"{key}  # #11670\n")
        cases.append(("known-negative: a baselined offender passes", rc == 0))
        rc, e, _ = setup("class X\n", f"{key}  # #11670\n")
        cases.append(("known-positive: a stale baseline entry fails", rc == 1 and "STALE" in e[0]))
        (t / BASELINE).unlink()
        cases.append(("cannot-run: missing baseline is exit 2", evaluate(t)[0] == 2))
    failed = 0
    for label, ok in cases:
        print(f"{'PASS' if ok else 'FAIL'}  {label}")
        failed += 0 if ok else 1
    print(f"self-test: {len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    rc, errors, n = evaluate(pathlib.Path(args.root).resolve())
    print(f"SUBJECTS={n}  # src/main Kotlin files outside the two calendar/day-count libraries")
    for e in errors:
        print(f"::error::{e}")
    print(f"{len(errors)} finding(s)")
    return rc


if __name__ == "__main__":
    sys.exit(main())
