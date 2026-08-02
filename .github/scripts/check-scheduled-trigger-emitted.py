#!/usr/bin/env python3
"""A service that declares a SCHEDULED trigger must actually emit it.

WHY THIS IS A GATE. A trigger enum with a `SCHEDULED` value is a claim: "this thing runs on a
schedule." When nothing constructs that value, the claim is false and **nothing anywhere goes
red** -- the service boots, its health endpoint is green, its REST trigger works, and its results
table is simply empty. An empty results table renders as "nothing to act on", which is the
healthiest-looking possible answer to a question nobody has ever asked. A control that has never
run is indistinguishable from one that runs and finds nothing.

Measured 2026-08-02: eight services declared a SCHEDULED trigger; three emitted it. The five that
did not (governance-auditor, release-steward, docs-truth-agent, authz-policy-auditor,
flaky-test-hunter) had 0 Temporal schedules, 0 workflow executions and 0 stored findings between
them, and had been deployed for weeks. The three that were fixed (#3339, #3370) were found only
because someone went looking.

WHY NOT JUST GREP FOR `@Scheduled`. Two reasons, both load-bearing:
  * A raw `grep -c "@Scheduled"` matches COMMENTS. On main it reported 1 for
    control-liveness-sentinel, whose true count was 0 -- the hit was a comment explaining
    ADR-0160 mechanism 3. This checker strips comments and anchors on the annotation.
  * Having a `@Scheduled` method somewhere is not the claim being checked. The claim is that the
    SCHEDULED path is *reachable*, which means something must construct `<Enum>.SCHEDULED`.
    statement-service is the worked example of doing it right: `CloseTrigger { SCHEDULED, MANUAL }`
    plus `PeriodCloseScheduler` calling `runClose(CloseTrigger.SCHEDULED)`.

Run standalone:  .github/scripts/check-scheduled-trigger-emitted.py [--enforce]
Self-test:       .github/scripts/check-scheduled-trigger-emitted.py --self-test
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# `enum class XTrigger { SCHEDULED, ... }` — the declaration that makes the claim.
ENUM_RE = re.compile(r"enum\s+class\s+(\w*Trigger)\s*\{([^}]*)\}", re.S)

# Services whose SCHEDULED value is declared but not yet emitted. This is a RATCHET, not an
# amnesty: a new occurrence fails, and an entry that becomes covered ALSO fails, so a debt cannot
# quietly become permanent and a fix cannot quietly leave a stale declaration behind.
# EMPTY, and that is the point: the five services listed here when this gate landed (#3484) are
# the five #3500 fixed, so every SCHEDULED value in the tree is now actually emitted. The other
# direction of the ratchet is what forced this edit — leaving a fixed service listed is itself a
# failure, so the list cannot rot into an excuse nobody rereads (same shape as KNOWN_UNCOVERED in
# check-pact-provider-replay.py, also empty). A new entry needs a reason and a way out.
KNOWN_UNEMITTED: dict[str, str] = {}


def strip_comments(text: str) -> str:
    """Remove // and /* */ comments. Kotlin block comments NEST, so a naive non-greedy strip
    closes early on `/* ... /* ... */ ... */` and leaves real code hidden inside a 'comment'."""
    out, i, depth = [], 0, 0
    while i < len(text):
        if depth == 0 and text.startswith("//", i):
            j = text.find("\n", i)
            i = len(text) if j == -1 else j
        elif text.startswith("/*", i):
            depth += 1
            i += 2
        elif depth > 0 and text.startswith("*/", i):
            depth -= 1
            i += 2
        else:
            if depth == 0:
                out.append(text[i])
            i += 1
    return "".join(out)


def analyse(service_dir: Path) -> tuple[set[str], set[str], bool]:
    """Return (trigger enums declaring SCHEDULED, enums whose SCHEDULED is constructed,
    whether any real @Scheduled annotation exists)."""
    declared: set[str] = set()
    emitted: set[str] = set()
    has_scheduled = False
    for kt in service_dir.rglob("*.kt"):
        src = strip_comments(kt.read_text(errors="ignore"))
        for name, body in ENUM_RE.findall(src):
            if re.search(r"\bSCHEDULED\b", body):
                declared.add(name)
        if re.search(r"^\s*@Scheduled\b", src, re.M):
            has_scheduled = True
        for name in re.findall(r"\b(\w*Trigger)\.SCHEDULED\b", src):
            emitted.add(name)
    return declared, emitted, has_scheduled


def audit() -> list[str]:
    findings: list[str] = []
    offenders: set[str] = set()
    for service_dir in sorted(REPO.glob("openbank-*/src/main")):
        service = service_dir.parents[1].name
        declared, emitted, has_scheduled = analyse(service_dir)
        unemitted = declared - emitted
        if not unemitted:
            continue
        offenders.add(service)
        if service in KNOWN_UNEMITTED:
            continue
        findings.append(
            f"{service}: declares {sorted(unemitted)} with a SCHEDULED value, but nothing "
            f"constructs it"
            + ("" if has_scheduled else " and the service has no @Scheduled method at all")
            + ". The SCHEDULED path is unreachable, so the service runs only when a human asks "
            "and its empty results table reads as 'nothing to act on'."
        )
    # The ratchet's other direction: a baselined service that is now covered must be removed from
    # KNOWN_UNEMITTED, or the list rots into a permanent excuse nobody rereads.
    for service, reason in sorted(KNOWN_UNEMITTED.items()):
        if not (REPO / service / "src" / "main").is_dir():
            findings.append(f"{service}: listed in KNOWN_UNEMITTED but has no src/main — stale entry, remove it.")
        elif service not in offenders:
            findings.append(
                f"{service}: now emits its SCHEDULED trigger — remove it from KNOWN_UNEMITTED "
                f"(recorded reason: {reason})."
            )
    return findings


def self_test() -> int:
    import tempfile

    def build(files: dict[str, str]) -> Path:
        root = Path(tempfile.mkdtemp()) / "openbank-x" / "src" / "main"
        root.mkdir(parents=True)
        for name, body in files.items():
            (root / name).write_text(body)
        return root

    cases = [
        ("declared and emitted from a scheduler passes",
         {"a.kt": "enum class RunTrigger { SCHEDULED, MANUAL }",
          "b.kt": "@Scheduled(cron=\"x\")\nfun f() { run(RunTrigger.SCHEDULED) }"}, set()),
        ("declared, never constructed is flagged",
         {"a.kt": "enum class RunTrigger { SCHEDULED, MANUAL }",
          "b.kt": "fun f() { run(RunTrigger.MANUAL) }"}, {"RunTrigger"}),
        ("a COMMENT mentioning the value does not count as emitting it",
         {"a.kt": "enum class RunTrigger { SCHEDULED }",
          "b.kt": "// we should call RunTrigger.SCHEDULED here one day\nfun f() {}"}, {"RunTrigger"}),
        ("a commented-out @Scheduled does not count as a scheduler",
         {"a.kt": "enum class RunTrigger { SCHEDULED }",
          "b.kt": "// @Scheduled(cron=\"x\")\nfun f() {}"}, {"RunTrigger"}),
        ("NESTED block comments do not hide real code",
         {"a.kt": "enum class RunTrigger { SCHEDULED }",
          "b.kt": "/* outer /* inner */ still comment */\nfun f() { run(RunTrigger.SCHEDULED) }"}, set()),
        ("an enum without a SCHEDULED value is not our business",
         {"a.kt": "enum class RunTrigger { MANUAL, WEBHOOK }", "b.kt": "fun f() {}"}, set()),
        ("a differently-named trigger enum is still checked",
         {"a.kt": "enum class CloseTrigger { SCHEDULED, MANUAL }", "b.kt": "fun f() {}"}, {"CloseTrigger"}),
        ("two enums, one emitted, flags only the other",
         {"a.kt": "enum class RunTrigger { SCHEDULED }\nenum class CloseTrigger { SCHEDULED }",
          "b.kt": "fun f() { run(CloseTrigger.SCHEDULED) }"}, {"RunTrigger"}),
        ("SCHEDULED as a substring of another value is not a declaration",
         {"a.kt": "enum class RunTrigger { RESCHEDULED_LATER }", "b.kt": "fun f() {}"}, set()),
    ]
    failed = 0
    for name, files, expected in cases:
        declared, emitted, _ = analyse(build(files))
        got = declared - emitted
        ok = got == expected
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {sorted(expected)}, got {sorted(got)})")
    print(f"self-test: {len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    findings = audit()
    if not findings:
        print(f"check-scheduled-trigger-emitted: OK — every declared SCHEDULED trigger is emitted "
              f"({len(KNOWN_UNEMITTED)} baselined).")
        return 0
    for f in findings:
        print(f"{'::error::' if enforce else '::warning::'}{f}")
    print(f"\n{len(findings)} problem(s). A SCHEDULED value nothing constructs is a claim the "
          f"service cannot keep, and it fails silently: no error, no alert, just an empty table.")
    return 1 if enforce else 0


if __name__ == "__main__":
    sys.exit(main())
