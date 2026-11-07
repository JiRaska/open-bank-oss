#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every Micrometer metric the fleet registers must be named `openbank.*` (or `openbank_*`).

WHY
---
Measured 2026-10-17: of ~200 literal metric names in `src/main`, seven carried no prefix
(`lending.origination.*` x2, `onboarding_funnel_events`, `credit_funnel_events`, and -- found by
this gate, not by the audit -- `feedback_submissions`, `feedback_emit_failures`). An unprefixed
name cannot be told apart from JVM/HTTP/third-party series by a label-free selector, escapes
every `{__name__=~"openbank_.*"}` dashboard or recording rule, and is invisible to
`check-alert-metric-emitted.py`, which only follows `openbank_*`. All were renamed together
with their readers; this gate stops a fourth.

WHAT IT CHECKS
--------------
In `*/src/main/**/*.kt`, a string literal that is the first argument of `.counter(` `.timer(`
`.gauge(` `.summary(` `.longTaskTimer(` or of `Counter|Timer|Gauge|DistributionSummary|
LongTaskTimer.builder(`, or the value of a `val ...(METRIC|COUNTER|TIMER|GAUGE|HISTOGRAM)... =`
constant, must start with `openbank.` or `openbank_`. Names built from constants are checked at
the constant. Not decidable by regex, deliberately out of scope: names assembled by string
templates.

BASELINE is the ratchet: an entry is `file -> {name}` with an issue link; it is empty today
because the offenders were renamed in the PR that added this gate. A baseline entry that
no longer matches anything is reported too, so the debt cannot go stale.

Run:  python3 .github/scripts/check-metric-name-prefix.py [--root .] [--self-test]
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

BASELINE: dict = {}  # {"openbank-x/src/main/.../F.kt": {"name"}}  # + issue link per entry

CALL_RE = re.compile(
    r'(?:\.(?:counter|timer|gauge|summary|longTaskTimer)|(?:Counter|Timer|Gauge|DistributionSummary|LongTaskTimer)\.builder)'
    r'\(\s*"([^"$]+)"'
)
CONST_RE = re.compile(
    r'\bval\s+(?:\w+_)?(?:METRIC|COUNTER|TIMER|GAUGE|HISTOGRAM)(?:_NAME)?\s*(?::\s*String\s*)?=\s*"([^"$]+)"'
)
NAME_RE = re.compile(r"^[a-z][a-z0-9_.]*$")


def names_in(text: str):
    for rx in (CALL_RE, CONST_RE):
        for m in rx.finditer(text):
            n = m.group(1)
            if NAME_RE.match(n):
                yield n


def ok(name: str) -> bool:
    return name.startswith("openbank.") or name.startswith("openbank_")


def scan(root: Path):
    files = subprocess.run(
        ["git", "-C", str(root), "ls-files", "*/src/main/*.kt"], capture_output=True, text=True, check=True
    ).stdout.split()
    found = []
    for f in files:
        for n in names_in((root / f).read_text(errors="ignore")):
            found.append((f, n))
    return found


def evaluate(found):
    bad = [(f, n) for f, n in found if not ok(n) and n not in BASELINE.get(f, set())]
    used = {(f, n) for f, n in found}
    stale = [(f, n) for f, ns in BASELINE.items() for n in ns if (f, n) not in used]
    return bad, stale


def self_test() -> int:
    good = 'registry.counter("openbank.x.y").increment()\nval METRIC = "openbank_a_b"\n'
    badt = 'registry.counter("onboarding_funnel_events", "a", b)\nTimer.builder("lending.x").register(r)\nval FOO_COUNTER = "plain"\n'
    assert all(ok(n) for n in names_in(good)) and list(names_in(good)), "positive control"
    got = [n for n in names_in(badt) if not ok(n)]
    assert got == ["onboarding_funnel_events", "lending.x", "plain"], f"negative control: {got}"
    b, s = evaluate([("f.kt", "plain")])
    assert b == [("f.kt", "plain")] and not s, "unbaselined name must fail"
    print("self-test OK: unprefixed names are caught, prefixed pass")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", default=".")
    p.add_argument("--self-test", action="store_true")
    a = p.parse_args()
    if a.self_test:
        return self_test()
    found = scan(Path(a.root))
    bad, stale = evaluate(found)
    print(f"SUBJECTS={len(found)}  # literal metric names in src/main")
    for f, n in bad:
        print(f"{f}: metric '{n}' must start with 'openbank.' (or 'openbank_')")
    for f, n in stale:
        print(f"{f}: baseline entry '{n}' matches nothing -- remove it")
    return 1 if bad or stale else 0


if __name__ == "__main__":
    sys.exit(main())
