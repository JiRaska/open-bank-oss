#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A VEX verdict reasoned from a bare `< X` advisory bound must carry bytecode evidence.

WHY THIS EXISTS (#4716, Refs #4533)
-----------------------------------
A security advisory that expresses its affected range as a single open interval — `< X`, with
no lower bound — silently misclassifies every dependency that maintains PARALLEL RELEASE LINES.
Maven/semver ordering says `3.4.2 < 4.2.1`, so a 3.x release branched *after* the fix sorts
below the bound and reads as affected, although it carries the patch.

The fleet hit this twice:

  * hibernate-reactive (#4533, PR #4707). `GHSA-frpp-8pwq-hjrx` states `< 4.2.1`. `4.2.1.Final`
    was released 2025-12-21; `3.4.0.Final` on 2026-05-27 — five months LATER, off a branch that
    already contained the fix. The fleet resolves `3.4.2.Final`, which HAS the patch. 47 VEX
    statements sat at `affected`, an issue was filed with an exit criterion (`>= 4.2.1`) that no
    Quarkus platform can satisfy, and the "obvious" remediation would have moved onto a line
    built against ORM 7.2.0 while Quarkus 3.38.0 ships ORM 7.4.5 — a guaranteed boot failure
    traded for a DoS that was never present.
  * netty — the 4.1.x-vs-4.2.x VEX statement was the same shape, caught by hand.

Both directions cost. The FALSE POSITIVE is what happened. The FALSE NEGATIVE is equally
available: a dependency whose older line is genuinely unpatched but whose version string sorts
ABOVE the stated bound (resolved 5.0.0 against `< 4.2.1`) reads as safe under the same
arithmetic. Neither is decidable from version metadata; see `classify()`.

WHAT SETTLES IT — AND WHAT THIS GATE CAN THEREFORE DEMAND
---------------------------------------------------------
Version arithmetic cannot settle a cross-line case. Bytecode can: read the advisory's referenced
fix commit, `javap -p -c` the resolved jar for its observable effects, run the probe against a
known-NEGATIVE (the last unfixed release) and a known-POSITIVE (the first patched one) so a
probe that always answers "present" is caught, and confirm the jar's sha256 against
`gradle/verification-metadata.xml` so the bytes examined are the bytes shipped.

Only that last step leaves a machine-checkable trace, and it is the one that anchors the whole
argument: a disassembly of some jar off the internet proves nothing about this build. So:

WHAT THIS CHECKS
----------------
For every statement in `openbank-libs/governance/vex/*.openvex.json` whose status is a VERDICT
(not `under_investigation`), the reasoning text is scanned for cited version bounds.

  * A bounded citation — `>=4.2.0, <4.2.16` — DECLARES the line it applies to. Version
    arithmetic is sound inside a declared interval, so nothing is required. This is the
    netty shape, and it is ~545 of today's ~600 citing statements: a gate that flagged them
    too would be noise, not a control.
  * The ambiguous shape has THREE spellings, and the gate had to learn all three by being run
    against the real pre-#4707 corpus rather than a fixture (see the regex comments):
      - a bare upper bound, `< 4.2.1`;
      - a zero-lower interval, `[0, 4.2.1)` — it looks bounded and declares nothing;
      - the same claim in prose, "Resolved ... is 3.4.1.Final ... fixed in 4.2.1.Final", which
        is what the 47 statements actually said and contains no `<` at all.
    A statement making any of them must also cite a sha256 that is PINNED in
    `gradle/verification-metadata.xml`. A sha absent from the metadata fails LOUDER than no sha
    at all: it means the artifact someone inspected is not the artifact this build resolves.

MEASURED, BOTH DIRECTIONS
-------------------------
Against `origin/main` today: 824 verdict statements, 47 cross-line, 0 findings (PR #4707's
statements each cite the pinned sha256 7e7443b2…075c for hibernate-reactive-core 3.4.2.Final).
Against the same overlays at 3356426^ — the real pre-#4707 tree — 47 of 47 flagged, exit 1.
That second run is the falsification: the gate goes red on the statements that shipped the
defect, and green on the ones that fixed it.

WHAT THIS GATE CANNOT ESTABLISH
-------------------------------
That the disassembly was done, that it found what the statement says it found, or that the
verdict is right. It enforces the one structural property that is machine-checkable — a
cross-line verdict is anchored to a specific, pinned artifact rather than to sorting two
version strings — and makes the un-anchored shape un-mergeable. The judgement stays human.
`classify()` is the other half: it refuses to auto-classify a cross-line case at all, so no
tooling downstream can turn arithmetic back into a verdict.

Usage:  check-vex-range-reasoning.py [--root .] [--enforce]
        check-vex-range-reasoning.py --classify <resolved> <range> [<range>...]
        check-vex-range-reasoning.py --self-test
"""

from __future__ import annotations

import argparse
import glob
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402 — import after the path insert; checkers run from the repo root

VEX_GLOB = "openbank-libs/governance/vex/*.openvex.json"
METADATA = "gradle/verification-metadata.xml"

# Statuses that assert something. `under_investigation` asserts nothing and is never inspected —
# the whole point of the triage queue is that a finding may sit there without an argument.
VERDICTS = {"affected", "not_affected", "fixed"}

# A version bound as written in prose: `< 4.2.1`, `<4.2.16`, `>= 4.2.0`.
#
# THREE numeric components minimum, deliberately. Ordinary prose in these statements contains
# comparisons that are not versions — "a ratio < 0.5", "fewer than < 4 services" — and a gate
# that demands a jar disassembly for a ratio is one people route around. Advisory bounds in the
# wild are three-part (`4.2.1`, `4.2.16`, `1.62.0`); a two-part bound (`< 4.2`) is therefore
# missed, which under-detects and never over-detects. Same direction as release_line(): the
# gate stays quiet where it is unsure, loud on the shape that has cost twice.
BOUND_RE = re.compile(
    r"(?<![\w.])(?P<op><=|<|>=|>)\s*(?P<ver>\d+(?:\.\d+){2,}(?:[.-][A-Za-z][\w.-]*)?)")

# THE SHAPE THAT ACTUALLY SHIPPED, and why a `<`-only scanner would have been theatre.
#
# The pre-#4707 hibernate statement — the real false positive, replicated across 47 files —
# never wrote `< 4.2.1` at all. It wrote: "Resolved org.hibernate.reactive:hibernate-reactive-core
# is 3.4.1.Final ... The connection-leak DoS is fixed in 4.2.1.Final". That is the identical
# claim in prose, and it is the form a triager naturally writes. Measured: a bare-`<` scanner
# run over the pre-#4707 corpus finds NOTHING — the gate would have been green about the exact
# statement it exists to stop. So the resolved/fixed-in pair is extracted too, and run through
# classify() rather than compared by hand.
_V = r"(\d+(?:\.\d+){1,}[\w.-]*)"
# A THIRD spelling of the same claim: the half-open interval `[0, 4.2.1)`. Two of the 47
# pre-#4707 statements used it (mcp-service, security-scanner). It LOOKS bounded — it has a
# lower bound — but a lower bound of zero declares no release line at all, so it carries the
# identical ambiguity as `< 4.2.1`. Found by running this gate against the real pre-fix corpus
# and asking why it flagged 45 and not 47; a synthetic fixture would never have raised it.
INTERVAL_RE = re.compile(r"\[\s*(\d+(?:\.\d+)*)\s*,\s*" + _V + r"\s*\)")
RESOLVED_RE = re.compile(
    r"[Rr]esolved\s+(?:[\w.:@/+-]+\s+){0,3}?(?:is|are)\s+(?:pinned to\s+|at\s+)?" + _V)
FIXED_IN_RE = re.compile(r"(?:fixed|patched|remediated|corrected|addressed)\s+in\s+" + _V)
SHA256_RE = re.compile(r"(?<![0-9a-f])([0-9a-f]{64})(?![0-9a-f])")
XML_SHA_RE = re.compile(r'sha256\s+value="([0-9a-f]{64})"')

# How far back from an upper bound a lower bound may sit and still be read as the same cited
# interval. `>=4.2.0, <4.2.16` is 9 characters; a lower bound a paragraph away is a different
# claim about a different range and must not silently launder this one.
PAIR_WINDOW = 48

UNDECIDABLE = "undecidable_cross_line"
AFFECTED = "affected"
UNAFFECTED = "unaffected"


# --------------------------------------------------------------------------------------
# version ordering
# --------------------------------------------------------------------------------------
def version_key(v: str) -> tuple[int, ...]:
    """Numeric components of a version, for ordering WITHIN one release line.

    Deliberately ignores qualifiers (`.Final`, `-SNAPSHOT`): this is used to answer "is 4.2.0
    below 4.2.1", never to rank `.Final` against `.CR1`. Comparing across lines is exactly the
    operation this file exists to refuse, so a comparator good enough for that is not needed.
    """
    parts = []
    for chunk in re.split(r"[.-]", v):
        if chunk.isdigit():
            parts.append(int(chunk))
        else:
            break
    return tuple(parts) or (0,)


def release_line(v: str) -> int:
    """The major component, used as the release-line proxy.

    A PROXY, not a truth: hibernate-reactive 3.x and 4.x are genuinely separate maintenance
    branches, but a project could equally branch 4.1.x and 4.2.x (netty does). Under-detecting
    the line only makes this gate demand evidence LESS often, never more — and the netty case
    arrives as a bounded citation anyway, which needs no evidence. Erring toward the major keeps
    the gate quiet on the common case and loud on the one that has actually cost twice.
    """
    return version_key(v)[0]


def naive_affected(resolved: str, upper: str) -> bool:
    """What version arithmetic alone says: below the bound means affected.

    Present so the self-test can assert the DEFECT, not only the fix. A test that shows the new
    classifier answering correctly, without showing what the old reasoning answered on the same
    input, proves nothing about this issue.
    """
    return version_key(resolved) < version_key(upper)


def classify(resolved: str, ranges: list[tuple[str | None, str]]) -> str:
    """Classify a resolved version against advisory ranges as (lower|None, upper) pairs.

    Returns `affected`, `unaffected`, or `undecidable_cross_line` — the third being a REFUSAL,
    not a verdict. Callers must escalate it to a human with an artifact probe; they must never
    fold it into either of the other two.
    """
    undecidable = False
    for lower, upper in ranges:
        if lower is not None:
            # A declared interval carries its own line. Arithmetic inside it is sound.
            if version_key(lower) <= version_key(resolved) < version_key(upper):
                return AFFECTED
            continue
        if release_line(resolved) == release_line(upper):
            # Same line: `< X` is an ordinary "everything before the patch" statement.
            if version_key(resolved) < version_key(upper):
                return AFFECTED
            continue
        # Different line, bare bound. Sorting below says nothing (the line may have branched
        # after the fix — hibernate-reactive 3.4.2); sorting above says nothing either (the
        # line may have branched before it and never received the backport).
        undecidable = True
    return UNDECIDABLE if undecidable else UNAFFECTED


# --------------------------------------------------------------------------------------
# the gate
# --------------------------------------------------------------------------------------
def bare_upper_bounds(text: str) -> list[str]:
    """Upper bounds cited with no lower bound in front of them, within PAIR_WINDOW chars."""
    out = []
    for m in BOUND_RE.finditer(text):
        if m.group("op") not in ("<", "<="):
            continue
        window = text[max(0, m.start() - PAIR_WINDOW):m.start()]
        if any(w.group("op") in (">=", ">") for w in BOUND_RE.finditer(window)):
            continue
        out.append(m.group("ver"))
    for lower, upper in INTERVAL_RE.findall(text):
        if not any(version_key(lower)):  # [0, X) / [0.0.0, X) — no line declared
            out.append(upper)
    return out


def cross_line_claims(text: str) -> list[str]:
    """Every cross-line-ambiguous version argument this statement makes, as human-readable text.

    Two shapes, both of which have shipped here:
      1. a bare `< X` bound cited from the advisory;
      2. "Resolved ... is A" + "fixed in B" — the same claim in prose, and the one the real
         #4533 statements actually used. Run through classify() so the decision is the
         classifier's, not a second hand-written comparison that can drift from it.
    """
    claims = [f"a bare upper bound (< {v})" for v in dict.fromkeys(bare_upper_bounds(text))]
    resolved = RESOLVED_RE.search(text)
    if resolved:
        for fix in dict.fromkeys(FIXED_IN_RE.findall(text)):
            if classify(resolved.group(1), [(None, fix)]) == UNDECIDABLE:
                claims.append(f"'resolved {resolved.group(1)}' vs 'fixed in {fix}' "
                              f"(different release lines)")
    return claims


def statement_text(st: dict) -> str:
    """The prose a human wrote. `justification` is a closed OpenVEX enum, never prose."""
    return " ".join(str(st.get(k, "")) for k in ("impact_statement", "action_statement"))


def pinned_shas(root: str) -> set[str]:
    p = pathlib.Path(root) / METADATA
    if not p.exists():
        raise FileNotFoundError(f"{METADATA} not found — refusing to report a pass")
    shas = set(XML_SHA_RE.findall(p.read_text()))
    if not shas:
        raise ValueError(f"{METADATA}: no sha256 pins parsed — refusing to report a pass")
    return shas


def analyse(docs: dict[str, dict], shas: set[str]) -> tuple[list[str], int, int]:
    """Return (findings, inspected, cross_line).

    `inspected` is every VERDICT statement read — that is the corpus, and the number whose
    collapse means the glob broke. `cross_line` is the subset making an ambiguous version
    argument; it is legitimately allowed to reach zero (the fleet could hold no such statement),
    so it must NOT be the floor, or paying the debt off would fail the gate.
    """
    findings: list[str] = []
    inspected = 0
    cross_line = 0
    for name, doc in sorted(docs.items()):
        for st in doc.get("statements", []) or []:
            if st.get("status") not in VERDICTS:
                continue
            inspected += 1
            text = statement_text(st)
            claims = cross_line_claims(text)
            if not claims:
                continue
            cross_line += 1
            vuln = st.get("vulnerability")
            cve = vuln.get("name") if isinstance(vuln, dict) else vuln
            where = f"{name}: {cve} ({st.get('status')})"
            cited = set(SHA256_RE.findall(text))
            if not cited:
                findings.append(
                    f"{where} reasons from {'; '.join(claims)} "
                    f"with no artifact evidence. A single `< X` interval sweeps in every parallel "
                    f"release line below X, in both directions (#4716). Disassemble the resolved "
                    f"jar for the advisory's fix commit — with a known-unfixed and a known-fixed "
                    f"control — and cite its sha256 from gradle/verification-metadata.xml, or "
                    f"state the range as a bounded interval if the advisory really is line-scoped.")
            elif not (cited & shas):
                findings.append(
                    f"{where} cites sha256 {sorted(cited)[0]}, which is not pinned in "
                    f"{METADATA}. The bytes inspected are not the bytes this build resolves, so "
                    f"the evidence does not attach to the artifact the verdict is about.")
    return findings, inspected, cross_line


def load_docs(root: str) -> dict[str, dict]:
    files = sorted(glob.glob(str(pathlib.Path(root) / VEX_GLOB)))
    if not files:
        raise FileNotFoundError(f"{VEX_GLOB}: no VEX overlays found — refusing to report a pass")
    return {pathlib.Path(f).name: json.loads(pathlib.Path(f).read_text()) for f in files}


def report(findings: list[str], inspected: int, cross_line: int, enforce: bool) -> int:
    for f in findings:
        print(f"::error::vex-range-reasoning: {f}", file=sys.stderr)
    # Printed on BOTH paths: a gate that found its corpus and then failed on it must not also
    # read as having lost it.
    gatelib.subjects(inspected, "VEX verdict statements across the fleet overlays")
    print(f"vex-range-reasoning: {cross_line} of them reason from a version range that "
          f"declares no release line; {len(findings)} of those without pinned-artifact evidence")
    if findings and not enforce:
        print("::warning::vex-range-reasoning found violations (advisory run)")
        return 0
    return 1 if findings else 0


# --------------------------------------------------------------------------------------
# self-test — the gate's RED must be reachable, and the DEFECT must be exhibited
# --------------------------------------------------------------------------------------
def self_test() -> int:  # noqa: C901
    fails: list[str] = []

    def eq(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want!r}, got {got!r}")

    # --- the real case, from #4533. hibernate-reactive: GHSA-frpp-8pwq-hjrx says `< 4.2.1`;
    # the fleet resolves 3.4.2.Final, branched and released FIVE MONTHS AFTER 4.2.1.Final and
    # carrying the fix. Assert the defect first: plain arithmetic calls it affected.
    eq("DEFECT: arithmetic calls the patched 3.4.2.Final affected",
       naive_affected("3.4.2.Final", "4.2.1"), True)
    eq("FIX: a bare cross-line bound is refused, not classified",
       classify("3.4.2.Final", [(None, "4.2.1")]), UNDECIDABLE)

    # --- the other direction, which is just as available: an older line that sorts ABOVE the
    # bound reads as safe under arithmetic, though it may never have received the backport.
    eq("DEFECT: arithmetic clears 5.0.0 against a bare < 4.2.1",
       naive_affected("5.0.0", "4.2.1"), False)
    eq("FIX: the false-negative direction is refused too",
       classify("5.0.0", [(None, "4.2.1")]), UNDECIDABLE)

    # --- the guard must NOT fire on same-line reasoning, or it is noise that decides nothing.
    eq("same line, below the bound, is plainly affected",
       classify("4.2.0.Final", [(None, "4.2.1")]), AFFECTED)
    eq("same line, at the bound, is plainly unaffected",
       classify("4.2.1.Final", [(None, "4.2.1")]), UNAFFECTED)

    # --- netty: a BOUNDED interval declares its own line, so 4.1.136.Final is cleanly outside
    # it — while the same bound read bare would have swept the whole 4.1.x line in.
    eq("bounded interval: the resolved 4.1.x line is outside it",
       classify("4.1.136.Final", [("4.2.0", "4.2.16")]), UNAFFECTED)
    eq("DEFECT: the same bound read bare would have called 4.1.136.Final affected",
       naive_affected("4.1.136.Final", "4.2.16"), True)
    eq("bounded interval: a version inside it is affected",
       classify("4.2.5", [("4.2.0", "4.2.16")]), AFFECTED)
    eq("a bounded hit wins over a bare cross-line bound in the same advisory",
       classify("4.2.5", [(None, "9.9.9"), ("4.2.0", "4.2.16")]), AFFECTED)

    # --- bound extraction from prose.
    eq("a bare bound in prose is extracted",
       bare_upper_bounds("affected range as a single interval (< 4.2.1), which under Maven"),
       ["4.2.1"])
    eq("a bounded citation is not reported as bare",
       bare_upper_bounds("the advisory's 4.2.x range (>=4.2.0, <4.2.16) does not apply"), [])
    eq("a lower bound a paragraph away does not launder a bare bound",
       bare_upper_bounds(">=1.0.0 " + "x" * (PAIR_WINDOW + 10) + " fixed in < 4.2.1"), ["4.2.1"])
    eq("an ordinary number in prose is not a bound",
       bare_upper_bounds("fewer than < 4 services and a ratio < 0.5 apply"), [])
    eq("a two-part bound is knowingly under-detected, not silently over-detected",
       bare_upper_bounds("fixed in < 4.2"), [])
    eq("a zero-lower interval declares no line and counts as bare (mcp-service's spelling)",
       bare_upper_bounds("The advisory's affected range is [0, 4.2.1), so this version is in "
                         "range."), ["4.2.1"])
    eq("a real half-open interval declares its line and does not",
       bare_upper_bounds("the range [4.2.0, 4.2.16) does not apply"), [])
    eq("an arrow bump is not a bound",
       bare_upper_bounds("4.1.135.Final -> 4.1.136.Final fleet-wide"), [])

    # --- the prose shape, taken VERBATIM from the pre-#4707 statement that shipped in 47 files.
    # It contains no `<` anywhere, so a bare-bound scanner alone reports it clean — which is
    # why this half exists.
    real_prefix = ("Resolved org.hibernate.reactive:hibernate-reactive-core is 3.4.1.Final "
                   "(bundled by the Quarkus platform BOM io.quarkus.platform:quarkus-bom:3.37.2). "
                   "The connection-leak DoS is fixed in 4.2.1.Final, a major-version jump that "
                   "cannot be forced independently of the Quarkus platform")
    eq("DEFECT EXHIBIT: the real pre-fix statement cites no `<` bound at all",
       bare_upper_bounds(real_prefix), [])
    eq("the real pre-fix statement IS caught as a cross-line claim",
       len(cross_line_claims(real_prefix)), 1)
    eq("a same-line resolved/fixed pair is decidable and stays quiet (the live "
       "opentelemetry statement: 1.60.1 vs 1.62.0)",
       cross_line_claims("Resolved io.opentelemetry:opentelemetry-api is 1.60.1. The unbounded "
                         "allocation is fixed in 1.62.0."), [])
    eq("a 'fixed in' with no resolved version stated is not guessed at",
       cross_line_claims("The DoS is fixed in 4.2.1.Final."), [])

    # --- end-to-end over a synthetic corpus. Every row states what must happen and why.
    good_sha = "a" * 64
    corpus = {
        "clean-bounded.json": {"statements": [{
            "vulnerability": {"name": "CVE-1"}, "status": "fixed",
            "action_statement": "the advisory's 4.2.x range (>=4.2.0, <4.2.16) does not apply "
                                "to the resolved 4.1.x line."}]},
        "clean-evidenced.json": {"statements": [{
            "vulnerability": {"name": "CVE-2"}, "status": "not_affected",
            "impact_statement": f"advisory range is a single interval (< 4.2.1); measured by "
                                f"disassembling the jar sha256 {good_sha}, the fix is present."}]},
        "bad-no-evidence.json": {"statements": [{
            "vulnerability": {"name": "CVE-3"}, "status": "affected",
            "action_statement": "resolved 3.4.2.Final is below the advisory's < 4.2.1, so this "
                                "is affected; ship >= 4.2.1."}]},
        "bad-unpinned-sha.json": {"statements": [{
            "vulnerability": {"name": "CVE-4"}, "status": "not_affected",
            "impact_statement": f"range is < 4.2.1; disassembled sha256 {'b' * 64} and the fix "
                                f"is present."}]},
        "untriaged.json": {"statements": [{
            "vulnerability": {"name": "CVE-5"}, "status": "under_investigation",
            "impact_statement": "range is < 4.2.1, pending."}]},
    }
    findings, inspected, cross_line = analyse(corpus, {good_sha})
    got = sorted(f.split(":")[0] for f in findings)
    eq("exactly the two un-evidenced rows are flagged", got,
       ["bad-no-evidence.json", "bad-unpinned-sha.json"])
    eq("every verdict statement is counted as corpus, untriaged ones are not", inspected, 4)
    eq("only the ambiguous ones are counted as cross-line", cross_line, 3)
    eq("the unpinned-sha row names the metadata file",
       any(METADATA in f for f in findings), True)

    # --- exit-code contract.
    import contextlib
    import io
    sink = io.StringIO()
    with contextlib.redirect_stderr(sink), contextlib.redirect_stdout(sink):
        rc_adv = report(["x"], 1, 1, enforce=False)
        rc_enf = report(["x"], 1, 1, enforce=True)
        rc_ok = report([], 1, 1, enforce=True)
    eq("advisory mode downgrades a violation to 0", rc_adv, 0)
    eq("--enforce fails on a violation", rc_enf, 1)
    eq("a clean run exits 0", rc_ok, 0)

    # --- a missing corpus must RAISE, never report a clean pass (the repo's oldest probe trap).
    for fn, label in ((load_docs, "missing VEX overlays"), (pinned_shas, "missing metadata")):
        try:
            fn("/nonexistent-root-for-self-test")
            fails.append(f"{label} did not raise (would report a false clean)")
        except (FileNotFoundError, ValueError):
            pass

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    sys.stderr.write("self-test passed: the gate flags un-evidenced cross-line reasoning, "
                     "leaves bounded and evidenced statements alone, and exhibits the "
                     "arithmetic defect it exists to stop\n")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", dest="self_test", action="store_true")
    ap.add_argument("--classify", nargs="+", metavar="ARG",
                    help="RESOLVED then one or more ranges as 'LOWER:UPPER' or ':UPPER'")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.classify:
        resolved, *raw = args.classify
        if not raw:
            sys.exit("--classify needs a resolved version and at least one range")
        ranges: list[tuple[str | None, str]] = []
        for r in raw:
            lower, _, upper = r.partition(":")
            if not upper:
                sys.exit(f"range {r!r} must be 'LOWER:UPPER' or ':UPPER'")
            ranges.append((lower or None, upper))
        verdict = classify(resolved, ranges)
        print(verdict)
        if verdict == UNDECIDABLE:
            print("REFUSED: a bare `< X` bound cannot classify a version on another release "
                  "line (#4716). Disassemble the resolved jar for the advisory's fix commit, "
                  "with a known-unfixed and a known-fixed control, and cite its pinned sha256.",
                  file=sys.stderr)
            return 2
        return 0

    findings, inspected, cross_line = analyse(load_docs(args.root), pinned_shas(args.root))
    return report(findings, inspected, cross_line, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
