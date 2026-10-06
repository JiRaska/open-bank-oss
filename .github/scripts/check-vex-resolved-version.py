#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A VEX statement's asserted resolved version must still be the version the build resolves.

WHY THIS EXISTS (#7987)
-----------------------
49 of 56 overlays carried, verbatim, "Resolved io.opentelemetry:opentelemetry-api is 1.60.1
(bundled by the Quarkus platform BOM ... 3.37.2) ... remediation requires the Quarkus platform
bump tracked in issue #1446". The platform bump had ALREADY landed — the fleet resolved 1.62.0,
the fixed version — and nothing noticed. A VEX document is a public, machine-readable statement
to downstream consumers; those 49 statements said, in machine-readable form, that a fix was
blocked when it was not.

This is the repo's most-repeated defect class in a VEX dress: a declaration that outlives its
subject, reported by nothing (the same shape as the stale baseline reasons of #7740/#7741).
`check-vex-range-reasoning.py` verifies that a version claim is ANCHORED to pinned-artifact
evidence; it does not check that the claim is still TRUE against today's resolution.

WHAT THIS CHECKS
----------------
For every statement in `openbank-libs/governance/vex/*.openvex.json`, find citations of the
shape `Resolved <group>:<artifact> is <version>` (markdown backticks/bold tolerated) and compare
`<version>` against that overlay's module `runtimeClasspath` resolution. Verification metadata
is not enough: it retains multiple versions of the same component across configurations and
modules (including three versions of opentelemetry-api), and choosing the last XML entry made
the old check accidentally report one version as the fleet's resolved version. A mismatch means
the statement's premise no longer holds and a human must re-triage
the verdict. This gate deliberately does NOT flip verdicts: `fixed` vs `not_affected` and
whether to keep compensating-control history are security judgements (#7987 says so
explicitly). It only makes the staleness LOUD.

An artifact absent from a module's runtimeClasspath is counted as UNVERIFIABLE, not failed:
the claim may cite a non-runtime artifact. A failed Gradle resolution is a gate failure, never
an unverifiable claim.

The 49 known-stale citations are BASELINED below with their issue, ratchet-only: a NEW mismatch
fails, a baseline entry that stops occurring fails too (so the baseline cannot rot), and the
tail stays visible until the overlays are re-triaged.

Usage:  check-vex-resolved-version.py [--root .] [--enforce]
        check-vex-resolved-version.py --self-test

Exit:   0  no new mismatches, no stale baseline entries
        1  a new mismatch, a stale baseline entry, or (--self-test) the fixture assertions fail
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from collections import defaultdict
from collections.abc import Callable
from pathlib import Path

# key: <overlay file>|<group>:<artifact>|<cited version>. Every entry needs a reason + issue.
# #7987: CVE-2026-45292 statements asserting opentelemetry-api 1.60.1 while 1.62.0 is resolved.
OTEL = "io.opentelemetry:opentelemetry-api"
BASELINE: set[str] = {
    f"{overlay}|{OTEL}|1.60.1"  # noqa: S105 — version string, not a secret
    for overlay in [
        "account-service.openvex.json",
        "agent-service.openvex.json",
        "aml-service.openvex.json",
        "anacredit-service.openvex.json",
        "ap2-service.openvex.json",
        "audit-service.openvex.json",
        "authz-policy-auditor.openvex.json",
        "balance-service.openvex.json",
        "billing-service.openvex.json",
        "card-issuance-service.openvex.json",
        "clearing-service.openvex.json",
        "clearing-simulator.openvex.json",
        "consent-service.openvex.json",
        "control-liveness-sentinel.openvex.json",
        "copilot-service.openvex.json",
        "customer-edge.openvex.json",
        "devops-agent.openvex.json",
        "dispute-service.openvex.json",
        "docs-truth-agent.openvex.json",
        "document-service.openvex.json",
        "domestic-payment.openvex.json",
        "finops-agent.openvex.json",
        "flaky-test-hunter.openvex.json",
        "fraud-service.openvex.json",
        "fx-service.openvex.json",
        "governance-auditor.openvex.json",
        "interest-service.openvex.json",
        "kyc-service.openvex.json",
        "ledger-service.openvex.json",
        "lending-service.openvex.json",
        "mcp-service.openvex.json",
        "notification-service.openvex.json",
        "onboarding-service.openvex.json",
        "party-service.openvex.json",
        "pid-service.openvex.json",
        "psd2-service.openvex.json",
        "release-steward.openvex.json",
        "sanctions-service.openvex.json",
        "sca-service.openvex.json",
        "sdd-service.openvex.json",
        "sepa-instant.openvex.json",
        "sepa-payment.openvex.json",
        "settlement-service.openvex.json",
        "standing-order-service.openvex.json",
        "statement-service.openvex.json",
        "swift-service.openvex.json",
        "transaction-service.openvex.json",
        "tpp-registry-service.openvex.json",
        "vop-service.openvex.json",
    ]
}

# "Resolved io.opentelemetry:opentelemetry-api is 1.60.1", markdown backticks/bold tolerated.
# The version must start with a digit, so phrasings like "resolved ... is pinned as ..." do not
# match (measured on the real corpus: at.yawk.lz4:lz4-java uses that spelling).
CITATION = re.compile(
    r"Resolved\s+`?([\w.\-]+):([\w.\-]+)`?\s+is\s+\*{0,2}`?(\d[\w.\-]*)`?\*{0,2}"
)


def parse_insight(output: str, coordinate: str, modules: list[str]) -> dict[str, str | None]:
    """Read only the selected top-level artifact line in each module's report."""
    selected: dict[str, set[str]] = {module: set() for module in modules}
    seen: set[str] = set()
    current = None
    version_line = re.compile(rf"^{re.escape(coordinate)}:([^\s]+)$")
    for line in output.splitlines():
        if line.startswith("> Task :"):
            task = re.match(r"> Task :([^:]+):dependencyInsight(?:\s|$)", line)
            current = task.group(1) if task and task.group(1) in selected else None
            if current:
                seen.add(current)
            continue
        if current:
            version = version_line.match(line)
            if version:
                selected[current].add(version.group(1))
    missing = set(modules) - seen
    ambiguous = {module: versions for module, versions in selected.items() if len(versions) > 1}
    if missing or ambiguous:
        raise RuntimeError(f"incomplete dependencyInsight: missing tasks={sorted(missing)}, "
                           f"ambiguous versions={ambiguous}")
    return {module: next(iter(versions)) if versions else None
            for module, versions in selected.items()}


def resolve_runtime(root: Path, coordinate: str, modules: list[str]) -> dict[str, str | None]:
    command = [str((root / "gradlew").resolve())]
    for module in modules:
        command.extend([f":{module}:dependencyInsight", "--dependency", coordinate,
                        "--configuration", "runtimeClasspath"])
    command.append("--console=plain")
    run = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, check=False)
    if run.returncode:
        raise RuntimeError(f"Gradle runtime resolution failed for {coordinate} "
                           f"(exit {run.returncode}); rerun dependencyInsight for "
                           f"{modules[0]} to inspect the failure")
    return parse_insight(run.stdout, coordinate, modules)


def find_mismatches(
    root: Path,
    resolver: Callable[[Path, str, list[str]], dict[str, str | None]] = resolve_runtime,
) -> tuple[set[str], int, int]:
    """Return (mismatch keys, citation count, unverifiable count)."""
    mismatches: set[str] = set()
    citations = 0
    unverifiable = 0
    claims: dict[str, list[tuple[str, str, str]]] = defaultdict(list)
    for overlay in sorted((root / "openbank-libs/governance/vex").glob("*.openvex.json")):
        doc = json.loads(overlay.read_text())
        for statement in doc.get("statements", []):
            text = " ".join(v for v in statement.values() if isinstance(v, str))
            for group, artifact, cited in CITATION.findall(text):
                citations += 1
                module = f"openbank-{overlay.name.removesuffix('.openvex.json')}"
                claims[f"{group}:{artifact}"].append((overlay.name, module, cited))
    for coordinate, entries in claims.items():
        modules = sorted({module for _, module, _ in entries})
        present = [module for module in modules if (root / module / "build.gradle.kts").exists()]
        versions = resolver(root, coordinate, present) if present else {}
        for overlay, module, cited in entries:
            actual = versions.get(module)
            if actual is None:
                unverifiable += 1
            elif actual != cited:
                mismatches.add(f"{overlay}|{coordinate}|{cited}")
    return mismatches, citations, unverifiable


def self_test() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        (tmpdir / "openbank-libs/governance/vex").mkdir(parents=True)
        (tmpdir / "openbank-svc").mkdir()
        (tmpdir / "openbank-svc/build.gradle.kts").touch()

        def fake_resolver(root: Path, coordinate: str,
                          modules: list[str]) -> dict[str, str | None]:
            assert root == tmpdir and modules == ["openbank-svc"]
            return {"openbank-svc": None if coordinate.endswith(":phantom") else "2.0.0"}

        def write(text: str) -> None:
            doc = {"statements": [{"action_statement": text}]}
            (tmpdir / "openbank-libs/governance/vex/svc.openvex.json").write_text(
                json.dumps(doc)
            )

        write("Resolved com.example:widget is 1.0.0 — blocked until the bump lands.")
        mism, cit, _ = find_mismatches(tmpdir, fake_resolver)
        assert cit == 1 and mism == {"svc.openvex.json|com.example:widget|1.0.0"}, (
            f"known-positive not caught: {mism}"
        )
        write("Resolved `com.example:widget` is **2.0.0**, which carries the fix.")
        mism, cit, _ = find_mismatches(tmpdir, fake_resolver)
        assert cit == 1 and not mism, f"clean claim flagged: {mism}"
        write("Resolved com.example:phantom is 9.9.9 — no such artifact here.")
        mism, cit, unv = find_mismatches(tmpdir, fake_resolver)
        assert cit == 1 and unv == 1 and not mism, f"unverifiable claim failed: {mism}"
        write("The resolved artifact is pinned as described above.")
        mism, cit, _ = find_mismatches(tmpdir, fake_resolver)
        assert cit == 0, f"non-version phrasing matched: {cit}"
        report = ("> Task :openbank-svc:dependencyInsight\n"
                  "com.example:widget:2.0.0\n"
                  "  com.example:widget:1.0.0 -> 2.0.0\n")
        assert parse_insight(report, "com.example:widget", ["openbank-svc"]) == {
            "openbank-svc": "2.0.0"}
        two_modules = report + ("> Task :openbank-other:dependencyInsight\n"
                                "com.example:widget:1.0.0\n")
        assert parse_insight(two_modules, "com.example:widget",
                             ["openbank-svc", "openbank-other"]) == {
            "openbank-svc": "2.0.0", "openbank-other": "1.0.0"}
        try:
            parse_insight(report, "com.example:widget", ["openbank-svc", "openbank-other"])
            assert False, "missing task was accepted"
        except RuntimeError:
            pass
    print("self-test OK")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = Path(".")
    if "--root" in argv:
        root = Path(argv[argv.index("--root") + 1])
    try:
        mismatches, citations, unverifiable = find_mismatches(root)
    except (OSError, RuntimeError) as exc:
        print(f"::error::VEX runtime resolution unavailable: {exc}")
        return 1
    new = mismatches - BASELINE
    stale = BASELINE - mismatches
    print(
        f"vex resolved-version audit: {citations} citations, "
        f"{len(mismatches)} stale ({len(BASELINE)} baselined against #7987), "
            f"{unverifiable} unverifiable (artifact absent from module runtimeClasspath)"
    )
    print(f"SUBJECTS={citations}")
    for key in sorted(new):
        print(f"::error::NEW stale VEX version claim: {key} — re-triage the verdict (#7987)")
    for key in sorted(stale):
        print(f"::error::baseline entry matched nothing — the claim it excused is gone; "
              f"delete the entry: {key}")
    return 1 if (new or stale) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
