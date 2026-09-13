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
`<version>` against the component version pinned in `gradle/verification-metadata.xml` — the
file Gradle itself verifies the build against, so it is the fleet's source of truth for "what
we resolve". A mismatch means the statement's premise no longer holds and a human must re-triage
the verdict. This gate deliberately does NOT flip verdicts: `fixed` vs `not_affected` and
whether to keep compensating-control history are security judgements (#7987 says so
explicitly). It only makes the staleness LOUD.

An artifact not present in verification-metadata is counted as UNVERIFIABLE, not failed: the
claim may cite evidence about a jar this repo does not resolve, and a gate cannot fail on what
it cannot check.

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
import sys
import xml.etree.ElementTree as ET
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


def pinned_versions(metadata: Path) -> dict[tuple[str, str], str]:
    root = ET.parse(metadata).getroot()
    m = re.match(r"\{.*\}", root.tag)
    ns = m.group(0) if m else ""
    return {
        (c.get("group"), c.get("name")): c.get("version")
        for c in root.iter(ns + "component")
    }


def find_mismatches(
    root: Path,
) -> tuple[set[str], int, int]:
    """Return (mismatch keys, citation count, unverifiable count)."""
    versions = pinned_versions(root / "gradle/verification-metadata.xml")
    mismatches: set[str] = set()
    citations = 0
    unverifiable = 0
    for overlay in sorted((root / "openbank-libs/governance/vex").glob("*.openvex.json")):
        doc = json.loads(overlay.read_text())
        for statement in doc.get("statements", []):
            text = " ".join(v for v in statement.values() if isinstance(v, str))
            for group, artifact, cited in CITATION.findall(text):
                citations += 1
                actual = versions.get((group, artifact))
                if actual is None:
                    unverifiable += 1
                elif actual != cited:
                    mismatches.add(f"{overlay.name}|{group}:{artifact}|{cited}")
    return mismatches, citations, unverifiable


def self_test() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        (tmpdir / "gradle").mkdir()
        (tmpdir / "openbank-libs/governance/vex").mkdir(parents=True)
        (tmpdir / "gradle/verification-metadata.xml").write_text(
            '<verification-metadata><components>'
            '<component group="com.example" name="widget" version="2.0.0"/>'
            "</components></verification-metadata>"
        )

        def write(text: str) -> None:
            doc = {"statements": [{"action_statement": text}]}
            (tmpdir / "openbank-libs/governance/vex/svc.openvex.json").write_text(
                json.dumps(doc)
            )

        write("Resolved com.example:widget is 1.0.0 — blocked until the bump lands.")
        mism, cit, _ = find_mismatches(tmpdir)
        assert cit == 1 and mism == {"svc.openvex.json|com.example:widget|1.0.0"}, (
            f"known-positive not caught: {mism}"
        )
        write("Resolved `com.example:widget` is **2.0.0**, which carries the fix.")
        mism, cit, _ = find_mismatches(tmpdir)
        assert cit == 1 and not mism, f"clean claim flagged: {mism}"
        write("Resolved com.example:phantom is 9.9.9 — no such artifact here.")
        mism, cit, unv = find_mismatches(tmpdir)
        assert cit == 1 and unv == 1 and not mism, f"unverifiable claim failed: {mism}"
        write("The resolved artifact is pinned as described above.")
        mism, cit, _ = find_mismatches(tmpdir)
        assert cit == 0, f"non-version phrasing matched: {cit}"
    print("self-test OK")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = Path(".")
    if "--root" in argv:
        root = Path(argv[argv.index("--root") + 1])
    mismatches, citations, unverifiable = find_mismatches(root)
    new = mismatches - BASELINE
    stale = BASELINE - mismatches
    print(
        f"vex resolved-version audit: {citations} citations, "
        f"{len(mismatches)} stale ({len(BASELINE)} baselined against #7987), "
        f"{unverifiable} unverifiable (artifact not in verification-metadata)"
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
