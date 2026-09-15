#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A workflow that pushes an openbank-* image signs it through the shared library, never by hand.

WHY THIS EXISTS

The shared library `openbank-infra/scripts/lib/cosign-attest.sh` produces every predicate the
Kyverno policies require, in one call. A workflow that spells out the cosign commands itself
produces whatever its author knew about on the day it was written -- and then a new policy lands
and the producer silently falls behind the consumer.

That is not hypothetical. `runner-image.yml` hand-rolled its attestation and therefore emitted a
CycloneDX SBOM and no SLSA provenance; when #8847 made SLSA an admission input, every ARC Pod was
denied -- and the only workflow that can rebuild the runner image runs on the pool the policy
blocks. Twice in two months, the same shape (#9805). The root CLAUDE.md has carried this rule in
prose the whole time, which is exactly the point: prose is not a control.

WHAT IT ASSERTS

For every file under `.github/workflows/`: a workflow that invokes cosign's sign or attest
subcommands must also reference the shared library and call `cosign_sign_and_attest`.
Verification (`verify`, `verify-attestation`) is deliberately NOT restricted -- reading back what
a producer made is a legitimate thing for a workflow to do on its own.

After #9793 the violating set is empty, so this enforces from day one, with no baseline that
could outlive its subject.
"""
from __future__ import annotations

import pathlib
import re
import sys

WORKFLOW_DIR = pathlib.Path(".github/workflows")
LIB_CALL = "cosign_sign_and_attest"
LIB_FILE = "cosign-attest" + ".sh"

# The sign and attest subcommands only. `sign-blob`, `verify` and `verify-attestation` are
# different operations and are not restricted -- hence the negative lookahead for a hyphen.
HANDROLLED = re.compile(r"\bcosign\s+(?:sign|attest)\b(?!-)")


def violations(root: pathlib.Path) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for path in sorted((root / WORKFLOW_DIR).glob("*.y*ml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        m = HANDROLLED.search(text)
        if not m:
            continue
        # Both halves are required. Naming the library in a comment while still running the
        # commands by hand is the case that must NOT pass, and the self-test pins it.
        if LIB_CALL in text and LIB_FILE in text:
            continue
        line = text[: m.start()].count("\n") + 1
        out.append((f"{WORKFLOW_DIR}/{path.name}", str(line)))
    return out


def self_test() -> int:
    """Falsify in both directions: a hand-rolled workflow must FIRE, a library caller must not."""
    import tempfile

    lib = f"openbank-infra/scripts/lib/{LIB_FILE}"
    cases = [
        ("hand-rolled sign -- MUST fire", "run: cosign sign --key x $IMAGE\n", 1),
        ("hand-rolled attest -- MUST fire", "run: cosign attest --predicate sbom.json $IMAGE\n", 1),
        ("the shared library -- clean",
         f"run: |\n  source {lib}\n  {LIB_CALL} \"$IMAGE\" linux/arm64\n", 0),
        ("a library caller that also names the old commands in a comment stays clean",
         f"run: |\n  # replaces the hand-rolled cosign sign + cosign attest pair\n"
         f"  source {lib}\n  {LIB_CALL} \"$IMAGE\"\n", 0),
        ("verify alone is not signing", "run: cosign verify --key x $IMAGE\n", 0),
        ("verify-attestation alone is not signing",
         "run: cosign verify-attestation --type cyclonedx $IMAGE\n", 0),
        ("sign-blob is a different operation", "run: cosign sign-blob --key x file\n", 0),
        ("naming the library without calling it does NOT excuse a hand-rolled sign -- MUST fire",
         f"run: |\n  # {lib} exists but we do it ourselves\n  cosign sign --key x $IMAGE\n", 1),
        ("a workflow with no cosign at all", "run: echo hello\n", 0),
    ]
    failures = 0
    for label, doc, want in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / WORKFLOW_DIR).mkdir(parents=True)
            (root / WORKFLOW_DIR / "w.yml").write_text(doc)
            got = len(violations(root))
        status = "ok" if got == want else "FAIL"
        failures += got != want
        print(f"  {status:4}  want={want} got={got}  {label}")
    print("self-test: PASS" if not failures else f"self-test: FAIL ({failures})")
    return 1 if failures else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    root = pathlib.Path(".")
    found = violations(root)
    total = len(list((root / WORKFLOW_DIR).glob("*.y*ml")))
    for rel, line in found:
        print(
            f"::error file={rel},line={line}::this workflow signs and attests by hand. Use the "
            f"shared library openbank-infra/scripts/lib/{LIB_FILE} and call {LIB_CALL} instead -- "
            f"it emits every predicate the Kyverno policies require, so a caller cannot fall "
            f"behind a new policy the way runner-image.yml did (#9805)."
        )
    verb = "workflow" if total == 1 else "workflows"
    if found:
        print(f"FAIL: {len(found)} of {total} {verb} hand-roll cosign signing.")
        return 1
    print(f"OK: none of {total} {verb} hand-roll signing; every signer uses the shared library.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
