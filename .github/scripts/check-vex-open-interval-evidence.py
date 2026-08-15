#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A VEX verdict that rests on a SINGLE `< X` advisory bound must carry artifact evidence.

WHY THIS EXISTS (issue #4716, prompted by #4533 / PR #4707)
-----------------------------------------------------------
An advisory that expresses its affected range as one open interval — `< X`, no lower bound —
silently misclassifies every dependency that maintains PARALLEL RELEASE LINES. Maven/semver
ordering says `3.4.2 < 4.2.1`, so a 3.x release branched AFTER the fix sorts below the bound and
reads as affected. It is not.

Measured, twice:

  * hibernate-reactive. `GHSA-frpp-8pwq-hjrx` states `< 4.2.1`. `4.2.1.Final` released
    2025-12-21; `3.4.0.Final` released 2026-05-27 — five months LATER, off a branch that already
    contained the fix. The fleet resolves `3.4.2.Final`, which HAS the patch (proved in bytecode
    with `javap`, against a known-negative and a known-positive, sha256-matched to
    `gradle/verification-metadata.xml`). 47 VEX statements sat at `affected`, and the "obvious"
    remediation — ship `>= 4.2.1` — is not merely unmet but unsatisfiable: every candidate
    quarkus-bom resolves 3.4.2.Final, and forcing 4.2.1 would trade a non-existent DoS for a
    guaranteed boot failure against a different Hibernate ORM.
  * netty, 4.1.x vs 4.2.x — the same shape.

WHY A GATE OVER THE COMMITTED STATEMENTS, AND NOT INSIDE THE VEX TOOLING
------------------------------------------------------------------------
The issue proposes refusing to "auto-classify". There is nothing to intercept:
`build-release-evidence.sh` writes EVERY finding as `under_investigation` on purpose — a machine
never asserts `not_affected`, because that is a security claim requiring human judgement
(`openbank-libs/governance/vex/README.md`). So the misclassification is not produced by tooling;
it is written by a person, into `openbank-libs/governance/vex/<component>.openvex.json`, and the
only place it can be caught before it becomes a regulator-facing audit artifact is a gate over
those committed files. A checklist would be the third option and is the weakest: it needs a human
to remember, and the two occurrences above are what remembering already produced.

WHAT THIS CHECKS
----------------
For every statement that records a VERDICT (`not_affected`, `fixed`, `affected` — only
`under_investigation`, which is the untriaged default, is out of scope), the statement's prose is
scanned for the version bounds it cites.

`affected` is deliberately IN scope, though it is the conservative-looking answer. The #4533
harm was an `affected` verdict: 47 statements sat there on the strength of the `< 4.2.1` bound
alone, which produced an issue with an exit criterion no available platform can satisfy and a
"remediation" that would have replaced a non-existent DoS with a certain boot failure. A wrong
`affected` is not free, and it is the direction that actually cost this fleet an investigation
twice. The escape is the same either way — say which line you resolve, or prove it in bytecode.

A cited `<`/`<=` bound is PAIRED when a `>`/`>=` bound appears immediately before it (same range
expression, e.g. `>=4.2.0, <4.2.16`). A paired bound is a two-sided interval — the well-formed
shape, which names its release line and cannot sweep in a parallel one. It is out of scope.

An UNPAIRED `< X` is the defective shape, and the statement must then carry ONE of:

  (a) ARTIFACT EVIDENCE — a sha256 in the statement text that also appears in
      `gradle/verification-metadata.xml`. That is the only claim version arithmetic cannot make
      and bytecode can: it pins the verdict to the exact bytes the build ships. This is the
      escape the hibernate-reactive verdict takes.
  (b) `resolved_version:` — an explicit field on the statement naming what the fleet resolves,
      whose release line (major.minor) MATCHES the bound's. Then the comparison is ordinary,
      sound, same-line arithmetic and needs no bytecode.

Why (b) is a declared FIELD and not read out of the prose: extracting "the resolved version"
from a paragraph is guesswork, and it is guesswork on the escape hatch — the direction where
being wrong ships a wrong classification silently. The hibernate statement alone mentions eight
distinct version numbers, four of them on the bound's own line as probe controls. A gate that
tried to infer intent from that would have let the defective case through. So the default for an
unpaired bound is "prove it"; escaping is something the author states, not something the gate
guesses.

BOTH DIRECTIONS OF THE UNDERLYING DEFECT
-----------------------------------------
The predicate is symmetric, and deliberately so. The false POSITIVE (resolved sorts below the
bound but carries the fix) and the false NEGATIVE (an older, genuinely unpatched line whose
version string sorts ABOVE the bound, so it reads as safe) are the same unsound inference from
the same single open interval. A statement making either one cites an unpaired `< X` and is
caught here.

WHAT IT CANNOT SEE. A vulnerability for which no statement exists at all. If a scanner never
reports the finding — which is exactly what the false-negative direction can produce upstream —
there is no statement in this corpus and this gate has no subject. This gate hardens the verdicts
that get written; it does not discover findings.

    python3 .github/scripts/check-vex-open-interval-evidence.py --root .
    python3 .github/scripts/check-vex-open-interval-evidence.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # import after the path insert — checkers run as scripts from the repo root

VEX_GLOB = "openbank-libs/governance/vex/*.openvex.json"
VERIFICATION_METADATA = "gradle/verification-metadata.xml"

# Every recorded verdict. Only `under_investigation` — the untriaged default, which asserts
# nothing — is exempt. `affected` is included on purpose: see the module docstring.
VERDICTS = ("not_affected", "fixed", "affected")

TEXT_FIELDS = ("impact_statement", "action_statement", "justification", "status_notes")

# A version bound as advisories and these statements write them: `< 4.2.1`, `>=4.2.0.Final`,
# `<= 4.2.15.Final`. Requires at least two dotted components so a bare `4` or a CVE year cannot
# match, and rejects a preceding word char/dot so `CVE-2026-59921` and `3.38.0.CR1` fragments do
# not produce phantom bounds.
BOUND_RE = re.compile(r"(?<![\w.])(?P<op>[<>]=?)\s*(?P<v>\d+(?:\.\d+)+(?:\.[A-Za-z][\w.-]*)?)")

SHA256_RE = re.compile(r"(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")

# How far back a `>`/`>=` may sit and still be the SAME range expression as a following `<`.
# The corpus' widest real two-sided citation is
# `>=4.2.0.Final and <=4.2.15.Final` (25 chars); 45 gives headroom for
# `is >= 4.2.0, and strictly < 4.2.16` phrasings without reaching a previous sentence.
PAIR_WINDOW = 45


def release_line(version: str) -> str:
    """major.minor — the release LINE, which is what a parallel-line project branches on.

    Not the major alone: netty's parallel lines are 4.1.x and 4.2.x, which share a major. The
    issue proposed comparing majors; the netty half of its own evidence shows major is too
    coarse.
    """
    parts = version.split(".")
    return ".".join(parts[:2])


def unpaired_upper_bounds(text: str) -> list[str]:
    """The `<`/`<=` bounds cited with no lower bound beside them — the defective shape."""
    out: list[str] = []
    for m in BOUND_RE.finditer(text):
        if not m.group("op").startswith("<"):
            continue
        window = text[max(0, m.start() - PAIR_WINDOW):m.start()]
        if any(w.group("op").startswith(">") for w in BOUND_RE.finditer(window)):
            continue  # two-sided interval: names its line, cannot sweep a parallel one
        out.append(m.group("v"))
    return out


def check(root: Path) -> tuple[list[str], int]:
    errors: list[str] = []
    examined = 0

    vm = root / VERIFICATION_METADATA
    # Fail closed, loudly: without the pin file, escape (a) cannot be verified, and treating an
    # unverifiable sha256 as evidence is precisely the "probe reports clean because it could not
    # run" shape this repo keeps paying for.
    pinned_digests: set[str] = set()
    if vm.is_file():
        pinned_digests = {d.lower() for d in SHA256_RE.findall(gatelib.read_text(str(vm)))}
    else:
        errors.append(f"{VERIFICATION_METADATA} is missing — sha256 evidence cannot be verified")

    for path in sorted(root.glob(VEX_GLOB)):
        try:
            doc = json.loads(gatelib.read_text(str(path)))
        except json.JSONDecodeError as exc:
            errors.append(f"{path}: not valid JSON ({exc})")
            continue
        for idx, st in enumerate(doc.get("statements") or []):
            examined += 1
            if st.get("status") not in VERDICTS:
                continue
            text = " ".join(str(st.get(k, "")) for k in TEXT_FIELDS)
            bounds = unpaired_upper_bounds(text)
            if not bounds:
                continue
            cve = (st.get("vulnerability") or {}).get("name", f"statement[{idx}]")
            where = f"{path.name} {cve}"

            # (b) declared same-line resolution — sound ordinary arithmetic.
            resolved = str(st.get("resolved_version") or "").strip()
            if resolved and all(release_line(resolved) == release_line(b) for b in bounds):
                continue

            # (a) artifact evidence pinned to the bytes the build ships.
            digests = {d.lower() for d in SHA256_RE.findall(text)}
            proven = digests & pinned_digests
            if proven:
                continue

            cited = ", ".join(f"< {b}" for b in sorted(set(bounds)))
            if digests and not proven:
                errors.append(
                    f"{where}: status `{st['status']}` rests on a single open interval ({cited}) "
                    f"and cites sha256 {sorted(digests)[0][:16]}… which is NOT pinned in "
                    f"{VERIFICATION_METADATA} — the inspected bytes are not provably the shipped "
                    "bytes."
                )
            elif resolved:
                errors.append(
                    f"{where}: status `{st['status']}` rests on a single open interval ({cited}) "
                    f"while `resolved_version` {resolved} is on release line "
                    f"{release_line(resolved)}, not the bound's. A cross-line comparison is not "
                    "sound version arithmetic — attach artifact evidence."
                )
            else:
                errors.append(
                    f"{where}: status `{st['status']}` rests on a single open interval ({cited}) "
                    "with neither artifact evidence nor a declared same-line `resolved_version`. "
                    "A `< X` advisory range sweeps in every parallel release line (issue #4716)."
                )
    return errors, examined


REMEDY = """
An advisory range written as one open interval cannot classify a dependency that maintains
parallel release lines. Settle it one of two ways, in the statement itself:

  * ARTIFACT EVIDENCE — `javap -p -c` the jar for the advisory's fix commit, run the probe
    against a known-negative (last unfixed release) AND a known-positive (first patched one) so
    it discriminates, and quote the jar's sha256 — which must be the value already pinned in
    gradle/verification-metadata.xml, so the bytes inspected are the bytes shipped.
    (`strings` is not a probe: on BSD it returned 0 for every jar including the known-fixed
    control, #4533.)
  * `"resolved_version": "<x.y.z>"` on the statement, when the fleet genuinely resolves the
    bound's own release line and plain arithmetic settles it.
"""


def self_test() -> int:
    """Falsify the gate in both directions, on the SHAPES it must discriminate.

    A guard that only ever says "no" is unfalsified, and here "no" is the answer that ships a
    wrong classification — so the negative cases carry as much weight as the positive ones.
    """
    ok = True
    good_sha = "a" * 64
    unpinned_sha = "b" * 64

    def doc(statements):
        return json.dumps({
            "@context": "https://openvex.dev/ns/v0.2.0",
            "@id": "https://open-bank.tech/vex/selftest",
            "author": "self-test",
            "version": 1,
            "statements": statements,
        })

    def stmt(**kw):
        base = {"vulnerability": {"name": "CVE-0000-0001"}, "status": "not_affected"}
        base.update(kw)
        return base

    cases = [
        # (label, statement, must_flag)
        ("open interval, no evidence at all",
         stmt(impact_statement="The advisory range is < 4.2.1; the fleet resolves 3.4.2.Final."),
         True),
        ("open interval, evidence sha256 NOT pinned in verification-metadata",
         stmt(impact_statement=f"Range < 4.2.1. Disassembled jar sha256 {unpinned_sha}."),
         True),
        ("open interval, resolved_version on a DIFFERENT release line",
         stmt(impact_statement="The advisory range is < 4.2.1.", resolved_version="3.4.2"),
         True),
        # The false-NEGATIVE direction: the resolved version sorts ABOVE the bound, so naive
        # arithmetic reads it as safe, but it is an older parallel line that may be unpatched.
        ("open interval, resolved sorts ABOVE the bound but on another line (false negative)",
         stmt(status="fixed",
              action_statement="Advisory affects < 2.5.0; we resolve 10.1.4 on the legacy line.",
              resolved_version="10.1.4"),
         True),
        # --- must NOT flag ---
        ("two-sided range (netty shape): >=4.2.0, <4.2.16",
         stmt(status="fixed",
              action_statement="The advisory's range (>=4.2.0, <4.2.16) does not apply to the "
                               "resolved 4.1.136.Final line."),
         False),
        ("two-sided range with .Final qualifiers and prose between the bounds",
         stmt(action_statement="Affected range is io.netty:netty-codec-dns >=4.2.0.Final and "
                               "<=4.2.15.Final (4.2.x line only)."),
         False),
        ("open interval but same release line, declared",
         stmt(impact_statement="The advisory range is < 4.2.1.", resolved_version="4.2.3"),
         False),
        ("open interval settled by a sha256 pinned in verification-metadata",
         stmt(impact_statement=f"Range < 4.2.1; jar sha256 {good_sha} carries the fix commit."),
         False),
        ("no version bound cited at all",
         stmt(impact_statement="The affected codec is never invoked."),
         False),
        # The #4533 shape itself: `affected` asserted off the bound alone. Must be flagged —
        # this is the case whose "obvious" remediation would have broken the boot.
        ("status `affected` off an open interval alone (the #4533 shape)",
         stmt(status="affected", action_statement="Advisory range < 4.2.1; remediation tracked."),
         True),
        ("status `affected` on the bound's own release line, declared",
         stmt(status="affected",
              action_statement="Advisory range < 4.2.1; bump pending.", resolved_version="4.2.0"),
         False),
        # The untriaged default asserts nothing and must never be forced to carry evidence.
        ("status `under_investigation` with an open interval — exempt",
         stmt(status="under_investigation",
              impact_statement="Advisory range < 4.2.1; triage pending."),
         False),
    ]

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        vexdir = root / "openbank-libs" / "governance" / "vex"
        vexdir.mkdir(parents=True)
        (root / "gradle").mkdir()
        (root / VERIFICATION_METADATA).write_text(
            f'<verification-metadata><sha256 value="{good_sha}"/></verification-metadata>'
        )

        for i, (label, statement, must_flag) in enumerate(cases):
            for f in vexdir.glob("*.json"):
                f.unlink()
            (vexdir / f"case{i}.openvex.json").write_text(doc([statement]))
            gatelib.clear()
            errs, examined = check(root)
            flagged = bool(errs)
            if examined != 1:
                print(f"SELF-TEST FAIL: [{label}] examined {examined} statements, expected 1")
                ok = False
            if flagged != must_flag:
                verb = "was not flagged" if must_flag else "was flagged"
                print(f"SELF-TEST FAIL: [{label}] {verb}"
                      + (f" — {errs[0]}" if errs else ""))
                ok = False

        # known-positive: an ABSENT verification-metadata must fail, never silently accept every
        # sha256 as proven. (Case: the one statement that escapes only via evidence.)
        for f in vexdir.glob("*.json"):
            f.unlink()
        (vexdir / "evidence.openvex.json").write_text(doc([
            stmt(impact_statement=f"Range < 4.2.1; jar sha256 {good_sha}.")]))
        (root / VERIFICATION_METADATA).unlink()
        gatelib.clear()
        errs, _ = check(root)
        if not any("is missing" in e for e in errs):
            print("SELF-TEST FAIL: a missing verification-metadata.xml did not fail closed")
            ok = False

        # known-positive: an empty corpus must not read as clean-and-checked. `min_subjects:` in
        # gates.yaml is what acts on this, so assert the count the runner reads is honest.
        for f in vexdir.glob("*.json"):
            f.unlink()
        gatelib.clear()
        if check(root)[1] != 0:
            print("SELF-TEST FAIL: an empty corpus reported a non-zero subject count")
            ok = False

    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    errors, examined = check(Path(args.root))
    # Printed on BOTH paths: a gate that found its corpus and then failed on it must not also
    # read as having lost it.
    gatelib.subjects(examined, "OpenVEX statements across openbank-libs/governance/vex")
    for e in errors:
        print(f"::error::{e}")
    if errors:
        print(f"\n{len(errors)} VEX verdict(s) resting on a single `< X` advisory interval.")
        print(REMEDY)
        return 1
    print("OK: every VEX verdict citing a one-sided `< X` range carries either artifact evidence "
          "or a declared same-line resolved_version.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
