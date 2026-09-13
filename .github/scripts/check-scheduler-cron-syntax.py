#!/usr/bin/env python3
"""Every scheduler cron in a service's application.yaml must be structurally valid.

WHY THIS EXISTS. Nothing in this fleet's test layers ever parses the PRODUCTION cron. A cron IT
overrides it with a fast expression, and the `%test` profile overrides it with a never-fire one, so
both the assertion and the wiring are exercised against a *different string* than the one that
ships. A typo in the committed expression therefore survives every gate and fails at pod boot --
`quarkus.scheduler` refuses the malformed expression and the application does not start. The
service is then down, not merely unscheduled, and the first evidence is a CrashLoopBackOff.

The risk became concrete when the weekly agents introduced `? * SUN`, a Quartz shape none of the
daily agents use: `0 30 5 ? * SUN`. Nothing else in the repo would have caught a slip in it.

WHAT THIS DOES AND DOES NOT COVER. This validates STRUCTURE, not full Quartz semantics: field
count, per-field numeric ranges, day-of-week names, and Quartz's rule that exactly one of
day-of-month / day-of-week is `?`. It is deliberately not a reimplementation of the scheduler's
parser -- a second parser that disagrees with the real one would be worse than none. It catches
the typo classes that actually occur (wrong field count, an hour of 25, `*` in both day fields,
a misspelled day name) and says so rather than claiming more.

Env-var placeholders are unwrapped first: the committed default inside `${VAR:default}` is the
value that ships when the deployment does not override it, so it is the thing to check.

Run standalone:  .github/scripts/check-scheduler-cron-syntax.py [--enforce]
Self-test:       .github/scripts/check-scheduler-cron-syntax.py --self-test
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"}
MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"}
# (name, min, max, extra literal tokens allowed)
FIELDS = [
    ("second", 0, 59, set()),
    ("minute", 0, 59, set()),
    ("hour", 0, 23, set()),
    ("day-of-month", 1, 31, {"?", "L", "LW"}),
    ("month", 1, 12, MONTHS),
    ("day-of-week", 1, 7, DAYS | {"?", "L"}),
]
PLACEHOLDER = re.compile(r"^\$\{[A-Z0-9_]+:(.*)\}$")


def unwrap(value: str) -> str:
    """`${VAR:0 15 3 * * ?}` -> `0 15 3 * * ?`. A bare value passes through."""
    m = PLACEHOLDER.match(value.strip())
    return (m.group(1) if m else value).strip()


def validate(expr: str) -> list[str]:
    """Structural problems with one Quartz cron expression, empty if it looks sane."""
    parts = expr.split()
    if len(parts) not in (6, 7):
        return [f"expected 6 or 7 fields (second minute hour day-of-month month day-of-week [year]), got {len(parts)}"]
    problems = []
    # NOT strict: a wrong field count is a finding this function reports elsewhere, so
    # pairing what there is and letting that check speak is deliberate.
    for (name, lo, hi, extra), field in zip(FIELDS, parts, strict=False):
        for token in re.split(r"[,/]", field):
            token = token.strip()
            if token in ("", "*") or token in extra:
                continue
            for piece in token.split("-"):
                piece = piece.strip()
                if piece in extra or piece == "*":
                    continue
                if piece.isdigit():
                    if not lo <= int(piece) <= hi:
                        problems.append(f"{name}: {piece} is outside {lo}-{hi}")
                elif piece.upper() in extra:
                    continue
                elif re.fullmatch(r"\d+#\d+|\d+L", piece):
                    continue
                else:
                    problems.append(f"{name}: {piece!r} is not a number or a recognised token")
    # Quartz: exactly one of day-of-month / day-of-week must be '?'. Both '*' is the classic slip,
    # and it is rejected at boot rather than silently reinterpreted.
    dom, dow = parts[3], parts[5]
    if ("?" in dom) == ("?" in dow):
        problems.append(
            f"exactly one of day-of-month ({dom!r}) and day-of-week ({dow!r}) must be '?' — "
            f"Quartz rejects both-or-neither"
        )
    return problems


def crons_in(path: Path) -> list[tuple[str, str]]:
    """(dotted key path, raw value) for every *cron* key ANYWHERE in a service config.

    The walk used to start at the `openbank` root only (#6253), which left two populations
    unvalidated while the gate printed OK: a cron under another root (agent-service's production
    `agent.oversight.cron`, read by OversightService) and every cron inside a profile block
    (`"%test": openbank: ...`), which the scheduler parses at that profile's boot just the same.
    The key is reported as its full path so a finding says where it sits, not only its leaf.
    """
    try:
        doc = list(yaml.safe_load_all(path.read_text()))[0]
    except (yaml.YAMLError, IndexError):
        return []
    out: list[tuple[str, str]] = []

    def walk(node, prefix: str):
        if isinstance(node, dict):
            for key, value in node.items():
                here = f"{prefix}.{key}" if prefix else str(key)
                if isinstance(value, str) and "cron" in str(key):
                    out.append((here, value))
                else:
                    walk(value, here)
        elif isinstance(node, list):
            for i, item in enumerate(node):
                walk(item, f"{prefix}[{i}]")

    walk(doc or {}, "")
    return out


def audit() -> list[str]:
    findings: list[str] = []
    checked = 0
    for cfg in sorted(REPO.glob("openbank-*/src/main/resources/application.yaml")):
        service = cfg.parents[3].name
        for key, raw in crons_in(cfg):
            expr = unwrap(raw)
            checked += 1
            for problem in validate(expr):
                findings.append(f"{service}: {key} = {expr!r} — {problem}")
    if checked == 0:
        findings.append(
            "no cron expressions found at all — either the config layout moved or this gate is "
            "checking nothing, which reads as a pass and must not."
        )
    return findings


def self_test() -> int:
    cases = [
        ("a daily agent cron is valid", "0 15 3 * * ?", 0),
        ("a weekly Quartz cron with a day name is valid", "0 30 5 ? * SUN", 0),
        ("the never-fire test idiom is structurally valid", "0 0 5 31 2 ?", 0),
        ("an every-second cron is valid", "*/1 * * * * ?", 0),
        ("five fields (unix crontab, not Quarkus) is rejected", "30 5 * * SUN", 1),
        ("hour 25 is rejected", "0 0 25 * * ?", 1),
        ("minute 60 is rejected", "0 60 3 * * ?", 1),
        ("both day fields as '*' is rejected", "0 0 3 * * *", 1),
        ("neither day field as '?' with a real dom is rejected", "0 0 3 15 * 3", 1),
        ("a misspelled day name is rejected", "0 30 5 ? * SUNN", 1),
        ("a range and list still validate", "0 0 3 ? * MON-FRI", 0),
    ]
    failed = 0
    for name, expr, expect_problems in cases:
        got = len(validate(expr))
        ok = (got > 0) == (expect_problems > 0)
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name}: {expr!r} -> {got} problem(s)")
    # The unwrapper is part of the contract: a placeholder must be reduced to its default.
    if unwrap("${LIVENESS_CHECK_CRON:0 15 3 * * ?}") != "0 15 3 * * ?":
        print("  FAIL  ${VAR:default} is not unwrapped to its default")
        failed += 1
    else:
        print("  PASS  ${VAR:default} is unwrapped to its default")

    # SCOPE (#6253): everything above exercises validate() alone, so it could not see that
    # crons_in() only ever looked under `openbank`. Drive the real walker over a fixture whose
    # crons sit under the `openbank` root, under a profile block, and under a foreign root.
    import tempfile  # self-test only

    fixture = (
        "openbank:\n  a:\n    check-cron: 0 15 3 * * ?\n"
        '"%test":\n  openbank:\n    a:\n      check-cron: 0 0 5 31 2 ?\n'
        "agent:\n  oversight:\n    cron: ${AGENT_OVERSIGHT_CRON:0 0 25 * * ?}\n"
    )
    with tempfile.TemporaryDirectory() as d:
        cfg = Path(d) / "application.yaml"
        cfg.write_text(fixture)
        found = dict(crons_in(cfg))
    scope_checks = [
        ("a cron under the openbank root is found", "openbank.a.check-cron" in found),
        ("a cron inside a %test profile block is found", "%test.openbank.a.check-cron" in found),
        ("a cron under a foreign root (agent.oversight.cron) is found", "agent.oversight.cron" in found),
        # Present AND rejected for the planted reason. `found.get(key, "")` alone would pass when
        # the key is missing, because validate("") reports a field-count problem -- vacuous.
        ("the foreign-root cron's committed default is validated and rejected (hour 25)",
         "agent.oversight.cron" in found
         and any("hour: 25" in p for p in validate(unwrap(found["agent.oversight.cron"])))),
    ]
    for name, ok in scope_checks:
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    total = len(cases) + 1 + len(scope_checks)
    print(f"self-test: {total - failed}/{total} passed")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    findings = audit()
    if not findings:
        print("check-scheduler-cron-syntax: OK — every committed scheduler cron is structurally valid.")
        return 0
    for f in findings:
        print(f"{'::error::' if enforce else '::warning::'}{f}")
    print(f"\n{len(findings)} problem(s). A malformed cron is refused by the scheduler at BOOT, so "
          f"the service does not start — this is a crashloop, not a missed run.")
    return 1 if enforce else 0


if __name__ == "__main__":
    sys.exit(main())
