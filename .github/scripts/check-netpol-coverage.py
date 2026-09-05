#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""NetworkPolicy coverage KPI: which GitOps components carry the generated ingress policy.

WHY (ADR-0279 WS2 #17). The VPC CNI network-policy agent has no audit mode (runbook 0010):
a pod a policy selects is default-deny for that direction, a pod none select is fully
reachable. Coverage is therefore binary per workload and the only honest question is "how
much of the fleet is selected at all". Measured 2026-09-04: **59 of 73** components carry
the generator-owned `network-policies.yaml` ingress allow-list (ADR-0081); the 14 that do
not are baselined below with a reason each.

WHAT IT CHECKS (convention, fully decidable — not CNI semantics):
  * every component directory under gitops/components/ either contains
    `network-policies.yaml` or appears in KNOWN_UNCOVERED with a reason;
  * a KNOWN_UNCOVERED entry that has become covered FAILS until removed (paid-off debt
    must leave the baseline, same shape as the route-conformance gate).

The cluster-side half (does the live agent agree) stays in runbook 0010 — a manifest can
only prove intent. The KPI printed here (`SUBJECTS=` + the coverage line) is the number
surfaced to the security excellence review.

Usage:  check-netpol-coverage.py [--root .] [--enforce]
        check-netpol-coverage.py --self-test
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

GENERATED = "network-policies.yaml"

# Components with no generator-owned ingress policy today (measured 2026-09-04).
# Each entry owes a reason; removing the policy gap means removing the line.
KNOWN_UNCOVERED = {
    "argocd-sync-verifier": "CI-side verifier job, no inbound traffic",
    "external-dns": "controller, outbound-only (DNS provider API)",
    "external-secrets": "controller, outbound-only (secrets API)",
    "finops-scaledown": "CronJob actuator, no inbound traffic",
    "gradle-build-cache": "build infrastructure, not a runtime service",
    "infra-vuln-scanner": "scanner job, no inbound traffic",
    "keycloak-realm-drift": "drift detector job, no inbound traffic",
    "loyalty": "topic declaration only (the KafkaTopic CR for openbank.loyalty.events) — the "
               "service has no Deployment anywhere in gitops, so there is no pod for an ingress "
               "policy to select and gen-network-policies.py, which derives edges from Deployment "
               "env URLs, would emit nothing. This entry fails the moment the workload lands, "
               "which is exactly when the policy is needed (#8793).",
    "kyverno": "admission controller — policy covered by its own helm chart",
    "openbao": "secrets backend, hand-authored policies (runbook 0005/0006)",
    "platform": "umbrella dir, hosts no single workload",
    "registry-cache": "pull-through cache, infra not money-path",
    "sbom-drift-scanner": "scanner job, no inbound traffic",
    "temporal": "workflow engine, hand-authored policies (temporal-network-policies.yaml)",
    "vpa-objects": "recommender CRDs, no pods of its own",
}


def components(root: Path) -> list[Path]:
    base = root / "openbank-infra/gitops/components"
    return sorted(p for p in base.iterdir() if p.is_dir())


def run(root: Path, enforce: bool) -> int:
    comps = components(root)
    covered, uncovered = [], []
    for c in comps:
        (covered if (c / GENERATED).exists() else uncovered).append(c.name)
    gatelib.subjects(len(comps), "gitops components examined")
    pct = round(100 * len(covered) / len(comps)) if comps else 0
    print(f"netpol-coverage: {len(covered)}/{len(comps)} components carry the generated "
          f"ingress policy ({pct}%); {len(uncovered)} uncovered")

    bad = 0
    for name in uncovered:
        if name in KNOWN_UNCOVERED:
            print(f"  baseline: {name} — {KNOWN_UNCOVERED[name]}")
        else:
            print(f"::error::component '{name}' has no {GENERATED} and no KNOWN_UNCOVERED "
                  f"entry — run gen-network-policies.py, or baseline it with a reason")
            bad += 1
    for name in KNOWN_UNCOVERED:
        if name in covered:
            print(f"::error::'{name}' is now covered — remove its KNOWN_UNCOVERED entry "
                  f"(paid-off debt must not linger in the baseline)")
            bad += 1
        # A baselined component that no longer exists at all is noted, not failed:
        # renames happen in other PRs and must not turn this gate red fleet-wide.
    if bad and enforce:
        print(f"netpol-coverage: {bad} violation(s)")
        return 1
    return 0


def self_test() -> int:
    import tempfile
    bad = 0
    with tempfile.TemporaryDirectory() as td:
        base = Path(td) / "openbank-infra/gitops/components"
        (base / "ledger").mkdir(parents=True)
        (base / "ledger" / GENERATED).write_text("# generated\n")
        (base / "kyverno").mkdir()          # baselined
        (base / "newsvc").mkdir()           # must be flagged
        if run(Path(td), enforce=True) == 0:
            print("self-test FAIL: uncovered unbaselined component not caught")
            bad += 1
        (base / "newsvc" / GENERATED).write_text("# generated\n")
        if run(Path(td), enforce=True) != 0:
            print("self-test FAIL: fully covered tree flagged")
            bad += 1
        # paid-off baseline entry must fail until removed
        (base / "kyverno" / GENERATED).write_text("# generated\n")
        if run(Path(td), enforce=True) == 0:
            print("self-test FAIL: stale baseline entry not caught")
            bad += 1
    print("netpol-coverage self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(Path(args.root), args.enforce)


if __name__ == "__main__":
    sys.exit(main())
