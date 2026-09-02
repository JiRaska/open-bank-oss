#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# No-op-is-not-success guard: a disabled, skipped or dry-run outcome must not be reported through
# the same boolean as a real success (ADR-0252 phase 0, issue #4348).
#
# THE MECHANISM
#   `PushResult.skipped()` — returned whenever `openbank.notification.push.apns.enabled=false` —
#   carried `success = true`, and the fan-out asked `count { success }`. So in an environment with
#   no APNs credentials every push was counted as delivered, the row committed SENT with `sentAt`
#   set, and the outcome event announced a delivery that never left the process. It shipped that
#   way and a customer reported it.
#
#   Three properties made it unrecoverable from telemetry, and they generalise to every adapter of
#   this shape — which is why this is a gate and not a code-review note:
#     * the disabled path is the QUIET one, so there is no error anywhere to find;
#     * the channel emitted no metric of its own, so no series disagreed with the row;
#     * "accepted" is not "delivered" for this class of provider at all (APNs issues no receipt),
#       so the honest name for the counter was never `delivered` in the first place.
#
# WHAT THIS CHECKS
#   Rule A — a no-op FACTORY must not construct a success flag.
#     A companion function named skipped/noop/disabled/dryRun/notConfigured whose body sets a
#     success-like Boolean to `true` (success/ok/succeeded/delivered/sent/applied) is flagged. The
#     sanctioned shape is a dedicated enum — `PushSendOutcome { ACCEPTED, SKIPPED, FAILED }` — so
#     the two states cannot be collapsed by reading one field.
#
#   Rule B — an AGGREGATION must not fold a no-op into a success count.
#     `count/filter/any/all/none { ....success }` over a receiver whose element type also carries a
#     skipped-like flag is flagged. This is the half that actually shipped the defect: the factory
#     was arguably defensible in isolation, the `count { success }` at the fan-out was not.
#
#   Both rules read Kotlin `src/main` only. Tests legitimately assert on the raw flag, and an
#   outbound REST-client interface has no bodies to inspect.
#
# WHY THE BASELINE IS EMPTY, AND WHY THAT IS THE POINT
#   Measured against origin/main on 2026-08-22: zero occurrences fleet-wide. notification-service
#   was fixed by #4348 (the boolean survives for wire compatibility, but `outcome` is derived and
#   every call site reads it), and billing's `BillingAssessment.skipped` never had a success
#   boolean to be confused with. So this gate ships with NO remediation debt: it cannot be
#   dismissed as noise today, and its whole job is that the next adapter of this shape is red at PR
#   time rather than discovered by a customer. An empty baseline is a stronger claim than a
#   populated one — but only if the gate's red is reachable, which is what --self-test proves.
#
# Run:  python3 .github/scripts/check-noop-not-success.py [--root .] [--self-test]

import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gatelib  # noqa: E402

# Names that mean "nothing actually happened". Deliberately narrow: these are the words this repo
# uses for an off-by-default adapter, not every word that could mean it.
NOOP_NAMES = ("skipped", "skip", "noop", "noOp", "disabled", "dryRun", "notConfigured", "inert")

# Names that mean "it worked". `sent`/`delivered` are included precisely because they are the ones
# that lie loudest when a no-op sets them.
SUCCESS_NAMES = ("success", "successful", "ok", "succeeded", "delivered", "sent", "applied", "accepted")

_NOOP_ALT = "|".join(NOOP_NAMES)
_SUCCESS_ALT = "|".join(SUCCESS_NAMES)

# Rule A: `fun skipped(...) ... success = true` — the assignment may be on a later line, so the
# factory body is scanned up to its first blank line or the next `fun `.
RULE_A_FUN = re.compile(r"^\s*(?:private\s+|internal\s+)?fun\s+(" + _NOOP_ALT + r")\s*\(", re.IGNORECASE)
RULE_A_ASSIGN = re.compile(r"\b(" + _SUCCESS_ALT + r")\s*=\s*true\b", re.IGNORECASE)

# Rule B: `count { it.success }` and friends. The lambda body is matched loosely because the
# receiver may be destructured (`it.second.success`) — what matters is that a success-like property
# is the ONLY thing the predicate reads.
RULE_B = re.compile(
    r"\.(count|filter|filterNot|any|all|none|partition)\s*\{\s*(?:it|[a-z]\w*)"
    r"(?:\.\w+)*\.(" + _SUCCESS_ALT + r")\s*\}",
    re.IGNORECASE,
)

# A type that already models the three states is not the defect this gate is about.
OUTCOME_MARKER = re.compile(r"\bval\s+outcome\s*:|enum\s+class\s+\w*Outcome\b")

# (file, line, rule) -> issue reference. Empty by design; see the docblock.
BASELINE: dict = {}


def _kotlin_main_sources(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "build", "node_modules", ".gradle")]
        if f"{os.sep}src{os.sep}main{os.sep}" not in dirpath + os.sep:
            continue
        for name in filenames:
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def _factory_body(lines, start):
    """Lines of the factory beginning at `start`, to its first blank line or the next `fun `."""
    body = [lines[start]]
    for line in lines[start + 1:]:
        if not line.strip() or re.match(r"\s*(?:private\s+|internal\s+)?fun\s+", line):
            break
        body.append(line)
    return body


def scan_file(path, text):
    """Return findings as (rule, line_number, evidence). Pure — the self-test drives it directly."""
    findings = []
    lines = text.splitlines()
    # A type carrying an explicit three-state accessor has made the distinction; Rule A is about
    # types that have NOT. Rule B still applies: an outcome nobody reads is not a distinction.
    models_outcome = bool(OUTCOME_MARKER.search(text))

    for index, line in enumerate(lines):
        if line.lstrip().startswith("*") or line.lstrip().startswith("//"):
            continue
        if not models_outcome and RULE_A_FUN.match(line):
            for offset, body_line in enumerate(_factory_body(lines, index)):
                if RULE_A_ASSIGN.search(body_line):
                    findings.append(("A", index + 1 + offset, body_line.strip()))
                    break
        match = RULE_B.search(line)
        if match:
            findings.append(("B", index + 1, line.strip()))
    return findings


def collect(root, count_subjects=False):
    out = []
    examined = 0
    for path in sorted(_kotlin_main_sources(root)):
        examined += 1
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        # Rule B only fires where the element type can also be a no-op. Approximated per-file and
        # per-module: the aggregation and the type it folds are almost always co-located here, and
        # a cross-module version would need a Kotlin parser for a defect this gate already catches
        # at the factory. Under-reach is stated rather than hidden.
        has_noop_flag = re.search(r"\bval\s+(" + _NOOP_ALT + r")\s*:\s*Boolean", text, re.IGNORECASE)
        for rule, line_number, evidence in scan_file(path, text):
            if rule == "B" and not has_noop_flag:
                continue
            out.append((os.path.relpath(path, root), line_number, rule, evidence))
    if count_subjects:
        # Printed unconditionally, before any verdict: a gate that found its corpus and then
        # failed on it must not also read as having lost its corpus (gatelib.subjects docstring).
        gatelib.subjects(examined, "kotlin src/main files scanned")
    return out


def validate_baseline(findings):
    """Report baseline entries that no longer correspond to a finding — a stale claim is a finding."""
    live = {(f, ln, rule) for f, ln, rule, _ in findings}
    return [key for key in BASELINE if key not in live]


def run(root):
    all_findings = collect(root, count_subjects=True)
    findings = [f for f in all_findings if (f[0], f[1], f[2]) not in BASELINE]
    stale = validate_baseline(all_findings)

    for path, line_number, rule, evidence in findings:
        detail = (
            "a no-op factory must not set a success flag — give the outcome its own enum value"
            if rule == "A"
            else "aggregating on a success flag merges a no-op with a real success — read the outcome"
        )
        print(f"::error file={path},line={line_number}::[rule {rule}] {detail}\n    {evidence}")
    for key in stale:
        print(f"::error::stale BASELINE entry, no longer present: {key}")

    if findings or stale:
        print(f"\nFAIL: {len(findings)} finding(s), {len(stale)} stale baseline entr(ies).")
        return 1
    print("OK: no no-op-reported-as-success occurrences.")
    return 0


# --- self-test -------------------------------------------------------------------------------
# The harness exits 0 when the gate is falsifiable (`selftest_expect: pass`, the repo default).

_FIXTURE_A = """
package com.openbank.probe
data class ProbeResult(val success: Boolean, val skipped: Boolean = false) {
    companion object {
        fun skipped(reason: String): ProbeResult = ProbeResult(success = true, skipped = true)
    }
}
"""

_FIXTURE_B = """
package com.openbank.probe
data class Other(val success: Boolean, val skipped: Boolean = false)
class FanOut {
    fun run(results: List<Other>): Int = results.count { it.success }
}
"""

_FIXTURE_CLEAN = """
package com.openbank.probe
enum class ProbeOutcome { ACCEPTED, SKIPPED, FAILED }
data class ProbeResult(val success: Boolean, val skipped: Boolean = false) {
    val outcome: ProbeOutcome get() = if (!success) ProbeOutcome.FAILED else if (skipped) ProbeOutcome.SKIPPED else ProbeOutcome.ACCEPTED
    companion object {
        fun skipped(reason: String): ProbeResult = ProbeResult(success = true, skipped = true)
    }
}
class FanOut {
    fun run(results: List<ProbeResult>): Int = results.count { it.outcome == ProbeOutcome.ACCEPTED }
}
"""


def _materialise(root, name, body):
    directory = os.path.join(root, "openbank-probe-service", "src", "main", "kotlin")
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, name), "w", encoding="utf-8") as handle:
        handle.write(body)


def self_test():
    global BASELINE
    checks = []

    with tempfile.TemporaryDirectory() as root:
        _materialise(root, "A.kt", _FIXTURE_A)
        checks.append(("rule A flags a no-op factory setting success = true", run(root) == 1))

    with tempfile.TemporaryDirectory() as root:
        _materialise(root, "B.kt", _FIXTURE_B)
        checks.append(("rule B flags count { it.success } over a type with a skipped flag", run(root) == 1))

    with tempfile.TemporaryDirectory() as root:
        _materialise(root, "Clean.kt", _FIXTURE_CLEAN)
        checks.append(("the sanctioned three-state shape is NOT flagged", run(root) == 0))

    # The baseline must be able to suppress, and a stale entry must be reported — both directions,
    # because a baseline that silently accepts anything is the failure mode this repo has hit.
    with tempfile.TemporaryDirectory() as root:
        _materialise(root, "A.kt", _FIXTURE_A)
        saved = BASELINE
        try:
            path = os.path.join("openbank-probe-service", "src", "main", "kotlin", "A.kt")
            live = collect(root)
            BASELINE = {(live[0][0], live[0][1], live[0][2]): "self-test"}
            checks.append(("a baselined finding is suppressed", run(root) == 0))
            BASELINE = {(path, 9999, "A"): "self-test"}
            checks.append(("a stale baseline entry is reported", run(root) == 1))
        finally:
            BASELINE = saved

    for description, passed in checks:
        print(f"  [{'ok' if passed else 'FAIL'}] {description}")
    if not all(passed for _, passed in checks):
        print("SELF-TEST FAILED: the gate is not falsifiable as documented.")
        return 1
    print("SELF-TEST OK: red is reachable, green is suppressible, stale baselines are reported.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    return self_test() if args.self_test else run(args.root)


if __name__ == "__main__":
    sys.exit(main())
