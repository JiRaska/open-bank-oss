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
subcommands must source the shared library and invoke `cosign_sign_and_attest` or both
attestation primitives in the same job.
Comments and calls in another job do not establish this convention. This is a static
convention check, not proof of shell execution or image-to-attestation binding.
Verification (`verify`, `verify-attestation`) is deliberately NOT restricted -- reading back what
a producer made is a legitimate thing for a workflow to do on its own.

After #9793 the violating set is empty, so this enforces from day one, with no baseline that
could outlive its subject.
"""
from __future__ import annotations

import pathlib
import re
import sys

import yaml

WORKFLOW_DIR = pathlib.Path(".github/workflows")
LIB_CALL = "cosign_sign_and_attest"
LIB_FILE = "cosign-attest" + ".sh"

# The sign and attest subcommands only. The deploy workflow resolves cosign into "$bin"
# before signing; matching only the literal command name would make its diagnostic echo
# the sole reason the gate sees that job. This is a syntactic convention check, not a
# shell interpreter: recognize direct calls through a variable as well as `cosign`.
HANDROLLED = re.compile(
    r"(?:\bcosign\b|[\"']?\$(?:\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z_][A-Za-z0-9_]*)[\"']?)"
    r"\s+(?:sign|attest)\b(?!-)"
)
OUTPUT_ONLY = re.compile(r"^\s*(?:echo|printf)\b")


def signing_command(script: str) -> re.Match[str] | None:
    for line in script.splitlines():
        if line.lstrip().startswith("#"):
            continue
        # Split shell command chains outside quoted strings: an `echo` segment is
        # output, but `echo ready; "$bin" sign ...` still executes a signer.
        segments = []
        start = 0
        quote = None
        escaped = False
        for pos, char in enumerate(line):
            if escaped:
                escaped = False
            elif char == "\\" and quote != "'":
                escaped = True
            elif char == quote:
                quote = None
            elif char in ("'", '"') and quote is None:
                quote = char
            elif char in ";|&" and quote is None:
                segments.append(line[start:pos])
                start = pos + 1
        segments.append(line[start:])
        for segment in segments:
            # Log output is not an invocation. In particular, `echo "cosign sign"`
            # must not keep this gate green about a separate "$bin" sign.
            if OUTPUT_ONLY.match(segment):
                continue
            match = HANDROLLED.search(segment)
            if match:
                return match
    return None


def violations(root: pathlib.Path) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for path in sorted((root / WORKFLOW_DIR).glob("*.y*ml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        document = yaml.safe_load(text)
        if not isinstance(document, dict):
            raise ValueError(f"workflow is not a mapping: {path}")
        jobs = document.get("jobs", {"fixture": {"steps": [document]}})
        if not isinstance(jobs, dict):
            raise ValueError(f"workflow jobs are not a mapping: {path}")
        for job in jobs.values():
            if not isinstance(job, dict):
                raise ValueError(f"workflow job is not a mapping: {path}")
            scripts = [step["run"] for step in job.get("steps", [])
                       if isinstance(step, dict) and isinstance(step.get("run"), str)]
            executable = "\n".join(
                line for script in scripts for line in script.splitlines()
                if not line.lstrip().startswith("#")
            )
            m = signing_command(executable)
            if not m:
                continue
            # Enforce this convention per job: a different image-producing job
            # cannot supply evidence for this one. Only command lines count,
            # not comments or echo strings naming the library.
            sourced = re.search(r"(?m)^\s*(?:source|\.)\s+[^\n]*cosign-attest\.sh(?:[\s\"']|$)", executable)
            called = re.search(r"(?m)^\s*cosign_sign_and_attest(?:\s|$)", executable)
            # The deploy job signs before its vulnerability scan, then invokes
            # both attestation primitives for the deployable set. Preserve that
            # split pipeline without granting an exemption to unrelated jobs.
            sbom = re.search(r"(?m)^\s*(?:if\s+!\s+)?cosign_attest_sbom(?:\s|$)", executable)
            provenance = re.search(r"(?m)^\s*(?:if\s+!\s+)?cosign_attest_slsa_provenance(?:\s|$)", executable)
            if sourced and (called or (sbom and provenance)):
                continue
            matching_line = next((i for i, line in enumerate(text.splitlines(), 1)
                                  if m.group(0) in line and not line.lstrip().startswith("#")), 1)
            out.append((f"{WORKFLOW_DIR}/{path.name}", str(matching_line)))
    return out


def self_test() -> int:
    """Falsify in both directions: a hand-rolled workflow must FIRE, a library caller must not."""
    import tempfile

    lib = f"openbank-infra/scripts/lib/{LIB_FILE}"
    cases = [
        ("hand-rolled sign -- MUST fire", "run: cosign sign --key x $IMAGE\n", 1),
        ("hand-rolled attest -- MUST fire", "run: cosign attest --predicate sbom.json $IMAGE\n", 1),
        ("resolved cosign binary signs without shared attestation -- MUST fire",
         'run: |\n  bin=/tmp/cosign\n  "$bin" sign --key key "$IMAGE"\n', 1),
        ("a diagnostic echo is not signing", 'run: echo "cosign sign"\n', 0),
        ("a diagnostic printf is not signing", 'run: printf "cosign attest\\n"\n', 0),
        ("signing after a diagnostic echo is still detected",
         'run: echo "cosign sign"; "$bin" sign --key key "$IMAGE"\n', 1),
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
        ("both library names in a comment do not authorize signing",
         f"run: |\n  # TODO {lib} and {LIB_CALL}\n  cosign sign --key x $IMAGE\n", 1),
        ("one library caller does not exempt another hand-rolled step",
         f"jobs:\n  good:\n    steps:\n      - run: |\n          source {lib}\n"
         f"          {LIB_CALL} $FIRST_IMAGE\n  bad:\n    steps:\n"
         "      - run: cosign attest --predicate sbom.json $SECOND_IMAGE\n", 1),
        ("a standalone explanatory comment is not a command",
         "run: |\n  # cosign sign was replaced\n  echo ready\n", 0),
        ("split signing and both shared attestations preserve deploy ordering",
         f"run: |\n  source {lib}\n  cosign sign --key x $IMAGE\n"
         "  if ! cosign_attest_sbom $IMAGE linux/arm64; then exit 1; fi\n"
         "  if ! cosign_attest_slsa_provenance $IMAGE; then exit 1; fi\n", 0),
        ("resolved binary retains the valid split-signing exemption",
         f'run: |\n  source {lib}\n  bin=/tmp/cosign\n  "$bin" sign --key key "$IMAGE"\n'
         "  if ! cosign_attest_sbom $IMAGE linux/arm64; then exit 1; fi\n"
         "  if ! cosign_attest_slsa_provenance $IMAGE; then exit 1; fi\n", 0),
        ("a split pipeline missing provenance still fails",
         f"run: |\n  source {lib}\n  cosign sign --key x $IMAGE\n"
         "  cosign_attest_sbom $IMAGE linux/arm64\n", 1),
        ("echoing library invocations is not invoking them",
         f"run: |\n  echo 'source {lib}'\n  echo '{LIB_CALL} $IMAGE'\n"
         "  cosign sign --key x $IMAGE\n", 1),
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
    print(f"SUBJECTS={total}")
    verb = "workflow" if total == 1 else "workflows"
    if found:
        print(f"FAIL: {len(found)} of {total} {verb} hand-roll cosign signing.")
        return 1
    print(f"OK: {total} {verb} checked; no unaccompanied literal cosign sign/attest invocation found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
