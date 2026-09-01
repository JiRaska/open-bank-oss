#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A gate must state how much it expects to find, or say why it cannot.

WHY THIS EXISTS
---------------
Measured on 2026-08-09 against `origin/main` (#4339). Delete every `.kt` file in the tree and
re-run the kotlin shard: nine gates still report PASS, several printing the zero out loud —
`0 .kt files checked`, `0 @RolesAllowed site(s) checked`, `0 service source file(s) checked`.
Delete all 647 gitops manifests and ten gitops gates do the same. The information was in the
output the whole time. Nothing acted on it.

That is not a bug in any one checker. It is a missing layer: no gate declared what "found my
corpus" means, so a renamed directory, a moved source root or a changed glob silently converts
a gate into a green no-op, and CI cannot tell that from a clean tree. It is the repo's own
lesson about a gate whose SCOPE is a hand-kept list — a short list reads as passing — with the
scope reduced all the way to nothing.

`gates.yaml` answers it with `min_subjects:`, and run-gates.py enforces it: the gate must print
`SUBJECTS=<n>` and clear the floor, or it fails. This script is the ratchet that makes the
answer spread — a NEW gate declares a floor or lands in a list a reviewer has to accept.

WHAT THE FLOOR IS, AND IS NOT
-----------------------------
It catches COLLAPSE, not drift. The seeded floors are roughly half of today's count, so the
fleet can shrink normally without a red, while a corpus that vanished or narrowed by an order
of magnitude cannot pass. A floor is not a ratchet on coverage: raising it as the fleet grows
is nobody's job, and a gate that failed because 3 services were retired is a gate people learn
to edit rather than read.

`no-corpus` is the honest exemption: a gate whose `run:` is a unit-test suite or a third-party
linter has no repo corpus to count. It is stated per id rather than inferred, because "this
gate has nothing to count" and "nobody has wired the count yet" are different claims and the
second one must not be able to hide inside the first.

Usage:  check-gate-subject-floor.py [--enforce]
        check-gate-subject-floor.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

import gatelib

MANIFEST = ".github/gates/gates.yaml"

# A gate with no repo corpus to count. Stated, not inferred.
NO_CORPUS = {
    "agent-review-proof-falsifiable",
    # DIFF-SCOPED: its corpus is the PR's own changed files, so zero is the ordinary case (most
    # PRs touch no openapi.yaml) and a floor would fail every one of them. It is not exempt from
    # the underlying question -- it prints SUBJECTS= including zero, so "no specs changed" and
    # "did not look" stay distinguishable in the log.
    "openapi-version-not-taken",
    "agent-review-scope-falsifiable",
    "auto-deploy-reconcile-probe-unit-test",
    "blocking-counterpart-probe-unit-test",
    "can-i-deploy-block-classifier-unit-test",
    "can-i-deploy-version-selector-unit-test",
    "co-deploy-set-derivation-unit-test",
    "ensure-ecr-repository",
    "gate-runner-self-test",
    "governance-script-unit-tests",
    "libs-change-dependents",
    "pact-version-probe-fail-closed-unit-test",
    "pact-provider-version-proof-unit-test",
    "pact-version-tree-equivalence-unit-test",
    "pr-build-cloud-credentials",
    "record-deployment-version-resolver",
    "runtime-conformance-comparators",
    "supersede-deploy-prs-ancestry",
}

# Gates that examine a real corpus and do not yet report how much of it they found. This list
# may SHRINK freely; growing it needs a reviewer to accept the reason, which is the point.
DEBT_MARKER = "debt — no SUBJECTS= count yet (baselined 2026-08-09, #4339)"
DEBT = {
    "accounting-clock-gate": DEBT_MARKER,
    "admin-ui-version-sync-guard": DEBT_MARKER,
    "adr-partial-followup": DEBT_MARKER,
    "adr-registry-integrity-check": DEBT_MARKER,
    "advisory-gate-registration": DEBT_MARKER,
    "agent-case-schema": DEBT_MARKER,
    "agent-charter-registry-parity": DEBT_MARKER,
    "agent-virtual-keys": DEBT_MARKER,
    "ai-act-high-risk-inventory-vs-code": DEBT_MARKER,
    "ai-governance-snapshot-drift": DEBT_MARKER,
    "api-contract-gate": DEBT_MARKER,
    "asvs-l3-mechanical-subset": DEBT_MARKER,
    "authz-enforce-pdp-sidecar-parity": DEBT_MARKER,
    "compliance-matrix": DEBT_MARKER,
    "compliance-page-evidence": DEBT_MARKER,
    "configproperty-kotlin-defaults": DEBT_MARKER,
    "critical-alert-egress": DEBT_MARKER,
    "db-backup-association-gate": DEBT_MARKER,
    "db-migration-gate": DEBT_MARKER,
    "deploy-drift-declaration": DEBT_MARKER,
    "dockerfile-runtime-only": DEBT_MARKER,
    "domain-purity-gate": DEBT_MARKER,
    "dotted-mp-messaging-key-guard": DEBT_MARKER,
    "enforcement-reachability": DEBT_MARKER,
    "eu-ai-act-inventory-drift": DEBT_MARKER,
    "evals-gate-replay": DEBT_MARKER,
    "evals-registry-integrity": DEBT_MARKER,
    "event-consumer-liveness": DEBT_MARKER,
    "event-contract-coverage-ratchet": DEBT_MARKER,
    "extension-bean-config": DEBT_MARKER,
    "external-feed-declaration-drift": DEBT_MARKER,
    "feature-flag-governance": DEBT_MARKER,
    "flyway-default-datasource": DEBT_MARKER,
    "gate-graduation-guard": DEBT_MARKER,
    "gate-script-registration": DEBT_MARKER,
    "gate-selftest-declaration": DEBT_MARKER,
    "gates-not-deregistered": DEBT_MARKER,
    "gen-network-policies-drift-gate": DEBT_MARKER,
    "gh-repo-context-in-checkoutless-jobs": DEBT_MARKER,
    "gitops-ref-integrity-guard": DEBT_MARKER,
    "governance-lineage-vs-code-audit": DEBT_MARKER,
    "gradle-cache-writer-budget": DEBT_MARKER,
    "gradle-home-isolation-on-self-hosted-runners": DEBT_MARKER,
    "identifier-intent-guard": DEBT_MARKER,
    "incluster-hostname-resolution": DEBT_MARKER,
    "kafka-acl-coverage": DEBT_MARKER,
    "kafka-cert-reader-grant": DEBT_MARKER,
    "kafka-dotted-key-ratchet": DEBT_MARKER,
    "kafka-topic-existence": DEBT_MARKER,
    "label-description-length": DEBT_MARKER,
    "libs-annotations-implemented": DEBT_MARKER,
    "license-header-consistency": DEBT_MARKER,
    "litellm-model-costs": DEBT_MARKER,
    "loki-rule-load-test": DEBT_MARKER,
    "main-red-watch-declaration": DEBT_MARKER,
    "matrix-write-grants": DEBT_MARKER,
    "mcp-charter-data-scope-binding": DEBT_MARKER,
    "mcp-real-port-requires-caller-auth-first": DEBT_MARKER,
    "model-residency-claims": DEBT_MARKER,
    "msg-channel-image-parity": DEBT_MARKER,
    "nat-ami-pinned": DEBT_MARKER,
    "network-policy-code-edges": DEBT_MARKER,
    "no-dead-code-service-principal-rego-rule": DEBT_MARKER,
    "no-runblocking-in-a-scheduled-body": DEBT_MARKER,
    "nonnull-jaxrs-param-ratchet": DEBT_MARKER,
    "object-store-blobs-migration": DEBT_MARKER,
    "oidc-client-secret-wiring": DEBT_MARKER,
    "oidc-secret-convention": DEBT_MARKER,
    "opa-bundle-apply-size": DEBT_MARKER,
    "opa-bundle-parses": DEBT_MARKER,
    "openapi-route-conformance": DEBT_MARKER,
    "openapi-server-port": DEBT_MARKER,
    "openssf-gold-evidence": DEBT_MARKER,
    "operator-write-naming": DEBT_MARKER,
    "outbox-has-writer": DEBT_MARKER,
    "pact-provider-replay-coverage": DEBT_MARKER,
    "pacticipant-matches-module": DEBT_MARKER,
    "pr-file-overlap": DEBT_MARKER,
    "prompt-registry-integrity": DEBT_MARKER,
    "psd2-anonymous-grant-stays-behind-eidas-mtls": DEBT_MARKER,
    "quarkus-application-version-override-guard": DEBT_MARKER,
    "readiness-attestation-format": DEBT_MARKER,
    "realm-template-importable": DEBT_MARKER,
    "release-registration-consistency": DEBT_MARKER,
    "release-scope-mismatch-gate": DEBT_MARKER,
    "route-exports": DEBT_MARKER,
    "scheduled-trigger-emitted": DEBT_MARKER,
    "scheduler-cron-syntax": DEBT_MARKER,
    "scheduler-exercised-in-tests": DEBT_MARKER,
    "scheduler-liveness-adoption": DEBT_MARKER,
    "schema-compat-gate": DEBT_MARKER,
    "service-runbook-drift": DEBT_MARKER,
    "single-replica-rollout-strategy": DEBT_MARKER,
    "slo-registry-consistency": DEBT_MARKER,
    "stale-comment-references": DEBT_MARKER,
    "temporal-namespace-registration": DEBT_MARKER,
    "threat-model-coverage": DEBT_MARKER,
    "threat-model-updated-on-trust-boundary-change": DEBT_MARKER,
    "workflow-run-step-size": DEBT_MARKER,
}


def load(root="."):
    f = pathlib.Path(root) / MANIFEST
    if not f.exists():
        raise FileNotFoundError(f"{MANIFEST} not found")
    gates = (yaml.safe_load(f.read_text()) or {}).get("gates")
    if not gates:
        raise ValueError(f"{MANIFEST}: no gates found — refusing to report a pass")
    return gates


def analyse(gates, no_corpus, debt):
    """Return (undeclared, stale) — gates needing a floor, and baseline entries that rotted."""
    ids = {g.get("id") for g in gates}
    known = set(no_corpus) | set(debt)
    undeclared = [g["id"] for g in gates
                  if g.get("min_subjects") is None and g.get("id") not in known]
    stale = []
    for gid in sorted(known):
        if gid not in ids:
            stale.append(f"{gid}: listed here but no such gate exists any more — remove it")
            continue
        g = next(x for x in gates if x.get("id") == gid)
        if g.get("min_subjects") is not None:
            stale.append(f"{gid}: now declares min_subjects — remove it from the list")
    return undeclared, stale


def report(undeclared, stale, enforce):
    bad = False
    for gid in undeclared:
        print(f"::error::{gid}: no `min_subjects:` and no entry in check-gate-subject-floor.py. "
              f"A gate that examines nothing passes everything — declare the floor and print "
              f"`SUBJECTS=<n>` (gatelib.subjects(n) in python), or record why it has no corpus.",
              file=sys.stderr)
        bad = True
    for msg in stale:
        print(f"::error::stale list — {msg}", file=sys.stderr)
        bad = True
    if bad and not enforce:
        print("::warning::gate-subject-floor found violations (advisory run)")
        return 0
    return 1 if bad else 0


def self_test():
    fails = []

    def case(label, gates, no_corpus, debt, want_undeclared, want_stale):
        u, s = analyse(gates, no_corpus, debt)
        got = (sorted(u), len(s))
        exp = (sorted(want_undeclared), want_stale)
        if got != exp:
            fails.append(f"{label}: expected {exp}, got {got}")

    case("a declared floor is clean", [{"id": "a", "min_subjects": 5}], set(), {}, [], 0)
    case("no floor and no entry is flagged", [{"id": "a"}], set(), {}, ["a"], 0)
    case("a no-corpus entry excuses it", [{"id": "a"}], {"a"}, {}, [], 0)
    case("a debt entry excuses it", [{"id": "a"}], set(), {"a": "debt"}, [], 0)
    # Both rot directions, so the list cannot quietly become permanent.
    case("an entry for a vanished gate is stale", [{"id": "b"}], set(), {"a": "d", "b": "d"}, [], 1)
    case("an entry for a gate that now declares one is stale",
         [{"id": "a", "min_subjects": 1}], set(), {"a": "d"}, [], 1)
    # min_subjects: 0 is not a declaration — run-gates.py rejects it at load time, and this
    # script must not read it as one either.
    case("a zero floor still counts as declared here (the loader is what rejects it)",
         [{"id": "a", "min_subjects": 0}], set(), {}, [], 0)

    try:
        load(root="/nonexistent-root-for-self-test")
        fails.append("a missing manifest did not raise (would report a false clean)")
    except (FileNotFoundError, ValueError):
        pass

    import contextlib, io
    sink = io.StringIO()
    with contextlib.redirect_stderr(sink), contextlib.redirect_stdout(sink):
        rc_adv = report(["x"], [], enforce=False)
        rc_enf = report(["x"], [], enforce=True)
        rc_ok = report([], [], enforce=True)
    if rc_adv != 0:
        fails.append("advisory mode did not downgrade a violation to 0")
    if rc_enf != 1:
        fails.append("--enforce did not fail on a violation")
    if rc_ok != 0:
        fails.append("a clean run did not exit 0 under --enforce")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: gate-subject-floor is falsifiable (7 cases + exit codes)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="every gate declares how much it expects to find")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    try:
        gates = load(args.root)
    except (FileNotFoundError, ValueError) as e:
        sys.stderr.write(f"::error::{e}\n")
        return 1
    # This gate's own corpus is the manifest, so it declares a floor like any other — the
    # alternative was a self-exemption, and a gate about missing declarations exempting itself
    # is the shape that made check-advisory-gate-registration.py flag itself (#2450).
    gatelib.subjects(len(gates), "gates in the manifest")
    u, s = analyse(gates, NO_CORPUS, DEBT)
    declared = len([g for g in gates if g.get("min_subjects") is not None])
    print(f"gate-subject-floor: {declared}/{len(gates)} gates declare a subject floor; "
          f"{len(NO_CORPUS)} have no corpus to count, {len(DEBT)} baselined as debt.")
    return report(u, s, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
