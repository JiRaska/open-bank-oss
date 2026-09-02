#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A gate must say WHY it exists and WHEN someone last checked that reason still holds.

WHY THIS EXISTS
---------------
Prompted by an external review of this repo's gate manifest (2026-08-10): it correctly named
`selftest:`/`min_subjects:`/`budget_seconds:` as the right shape, then proposed adding `owner:`
to every gate. Checked against `CODEOWNERS` before building it: this repo has exactly one
maintainer on every path. `owner:` would resolve to the same value 140 times — a field that
carries zero information is worse than no field, because it *looks* like governance while
deciding nothing. Not built. Recorded here so the next reviewer does not re-propose it without
first running the same one-line check the rejection rests on.

What IS missing, and does carry information: **why a gate exists** and **whether anyone has
looked at that reason lately**. `check-advisory-finding-staleness.py` already proves the pattern
for one narrow case (an advisory gate's "these findings are benign" claim) — this generalises
the same shape (a dated claim, re-checked on a ratchet, no fabricated backfill) to every gate's
reason for existing, not only advisory ones.

WHAT THIS CHECKS
----------------
Every gate should carry:
  * `rationale:` — one sentence, why this gate exists (the failure it prevents, not what it
    does — `gates.yaml`'s own comments already say what; this says why it is worth the CI
    seconds it costs).
  * `review_after:` — an ISO date. Past that date, the gate is flagged (not failed — see
    "ratchet, not audit" below) as due for a human to re-ask "does this still catch something
    real, or has its failure mode already been fixed for good."

Like every other structural property this repo has learned to enforce (`selftest:`,
`min_subjects:`, `budget_seconds:`, `verified:`): NEW gates must declare both fields or be
baselined with a reason a reviewer accepts. The 140 gates existing when this check was written
are baselined as debt — this script does NOT invent 140 rationales it cannot verify are true.
Writing a plausible-sounding "why" for a gate nobody re-examined would be exactly the fabricated
claim `check-advisory-finding-staleness.py` exists to catch, aimed at itself.

RATCHET, NOT AUDIT — WHY A PAST review_after IS A WARNING, NOT A FAILURE
--------------------------------------------------------------------------
`check-advisory-finding-staleness.py` FAILS an aged `verified:` because advisory mode is a
standing exception to normal enforcement, and an unexamined exception is a live risk by
construction. A gate's `review_after` is different: an enforced gate past its review date is
still enforcing, still falsifiable, still doing its job — the date passing means "worth asking
whether this earns its keep," not "this is now unsafe." Failing the PR of whoever happens to
touch `gates.yaml` next for an unrelated reason would be exactly the kind of blocked-for-no-
reason cost this repo's own CI-tax section already learned to avoid. Reported as a warning
(`::warning`) always, regardless of `--enforce` — visible, never blocking.

Usage:  check-gate-lifecycle-metadata.py --today YYYY-MM-DD [--enforce]
        check-gate-lifecycle-metadata.py --self-test
"""

from __future__ import annotations

import argparse
import datetime
import pathlib
import sys

import yaml

MANIFEST = ".github/gates/gates.yaml"

# Gates with no rationale/review_after as of 2026-08-10, when this check was added. Growing
# this needs a reason a reviewer accepts — the same rule every other debt list here follows.
DEBT_MARKER = "debt — no rationale:/review_after: yet (baselined 2026-08-10)"
DEBT = {
    "gate-runner-self-test": DEBT_MARKER,
    "yamllint": DEBT_MARKER,
    "shellcheck": DEBT_MARKER,
    "scheduler-liveness-adoption": DEBT_MARKER,
    "deploy-drift-declaration": DEBT_MARKER,
    "litellm-model-costs": DEBT_MARKER,
    "agent-virtual-keys": DEBT_MARKER,
    "model-residency-claims": DEBT_MARKER,
    "oidc-secret-convention": DEBT_MARKER,
    "single-replica-rollout-strategy": DEBT_MARKER,
    "gen-network-policies-drift-gate": DEBT_MARKER,
    "network-policy-code-edges": DEBT_MARKER,
    "podmonitor-namespace-coverage": DEBT_MARKER,
    "temporal-namespace-registration": DEBT_MARKER,
    "loki-rule-load-test": DEBT_MARKER,
    "service-runbook-drift": DEBT_MARKER,
    "oidc-client-secret-wiring": DEBT_MARKER,
    "msg-channel-image-parity": DEBT_MARKER,
    "gitops-ref-integrity-guard": DEBT_MARKER,
    "incluster-hostname-resolution": DEBT_MARKER,
    "db-backup-association-gate": DEBT_MARKER,
    "probe-scrape-port-has-a-listener": DEBT_MARKER,
    "license-header-consistency": DEBT_MARKER,
    "stale-comment-references": DEBT_MARKER,
    "gh-repo-context-in-checkoutless-jobs": DEBT_MARKER,
    "gradle-home-isolation-on-self-hosted-runners": DEBT_MARKER,
    "governance-script-unit-tests": DEBT_MARKER,
    "release-registration-consistency": DEBT_MARKER,
    "release-scope-mismatch-gate": DEBT_MARKER,
    "admin-ui-version-sync-guard": DEBT_MARKER,
    "deploy-coverage-guard": DEBT_MARKER,
    "auto-deploy-reconcile-probe-unit-test": DEBT_MARKER,
    "can-i-deploy-block-classifier-unit-test": DEBT_MARKER,
    "can-i-deploy-version-selector-unit-test": DEBT_MARKER,
    "pact-version-tree-equivalence-unit-test": DEBT_MARKER,
    "pact-version-probe-fail-closed-unit-test": DEBT_MARKER,
    "blocking-counterpart-probe-unit-test": DEBT_MARKER,
    "workflow-run-step-size": DEBT_MARKER,
    "gradle-cache-writer-budget": DEBT_MARKER,
    "co-deploy-set-derivation-unit-test": DEBT_MARKER,
    "quarkus-application-version-override-guard": DEBT_MARKER,
    "gate-graduation-guard": DEBT_MARKER,
    "advisory-gate-registration": DEBT_MARKER,
    "slo-registry-consistency": DEBT_MARKER,
    "prompt-registry-integrity": DEBT_MARKER,
    "evals-registry-integrity": DEBT_MARKER,
    "evals-gate-replay": DEBT_MARKER,
    "governance-lineage-vs-code-audit": DEBT_MARKER,
    "mcp-charter-data-scope-binding": DEBT_MARKER,
    "journey-money-path-accountability": DEBT_MARKER,
    "journey-catalog-integrity": DEBT_MARKER,
    "adr-registry-integrity-check": DEBT_MARKER,
    "adr-partial-followup": DEBT_MARKER,
    "agent-charter-registry-parity": DEBT_MARKER,
    "agent-model-parity": DEBT_MARKER,
    "agent-case-schema": DEBT_MARKER,
    "eu-ai-act-inventory-drift": DEBT_MARKER,
    "ai-act-high-risk-inventory-vs-code": DEBT_MARKER,
    "ensure-ecr-repository": DEBT_MARKER,
    "gitops-duplicate-resources": DEBT_MARKER,
    "scheduler-cron-syntax": DEBT_MARKER,
    "scheduled-trigger-emitted": DEBT_MARKER,
    "gates-not-deregistered": DEBT_MARKER,
    "dockerfile-runtime-only": DEBT_MARKER,
    "gate-script-registration": DEBT_MARKER,
    "main-red-watch-declaration": DEBT_MARKER,
    "merged-past-red-check-declaration": DEBT_MARKER,
    "opa-sidecar-bundle-shape": DEBT_MARKER,
    "kafka-cert-reader-grant": DEBT_MARKER,
    "extension-bean-config": DEBT_MARKER,
    "route-exports": DEBT_MARKER,
    "compliance-matrix": DEBT_MARKER,
    "openssf-gold-evidence": DEBT_MARKER,
    "operator-write-naming": DEBT_MARKER,
    "matrix-write-grants": DEBT_MARKER,
    "ai-governance-snapshot-drift": DEBT_MARKER,
    "event-consumer-liveness": DEBT_MARKER,
    "no-runblocking-in-a-scheduled-body": DEBT_MARKER,
    "test-runblocking-unit-guard": DEBT_MARKER,
    "no-service-local-exceptionmapper-collision-with-libs-runtime": DEBT_MARKER,
    "nonnull-jaxrs-param-ratchet": DEBT_MARKER,
    "outbox-dispatch-enabled-guard": DEBT_MARKER,
    "identifier-intent-guard": DEBT_MARKER,
    "domainevent-occurredat-constructor-guard": DEBT_MARKER,
    "clock-injection-gate": DEBT_MARKER,
    "accounting-clock-gate": DEBT_MARKER,
    "domain-purity-gate": DEBT_MARKER,
    "libs-annotations-implemented": DEBT_MARKER,
    "pacticipant-matches-module": DEBT_MARKER,
    "record-deployment-version-resolver": DEBT_MARKER,
    "runtime-conformance-comparators": DEBT_MARKER,
    "outbox-has-writer": DEBT_MARKER,
    "pact-provider-replay-coverage": DEBT_MARKER,
    "api-contract-gate": DEBT_MARKER,
    "openapi-route-conformance": DEBT_MARKER,
    "asvs-l3-mechanical-subset": DEBT_MARKER,
    "openapi-server-port": DEBT_MARKER,
    "kafka-topic-existence": DEBT_MARKER,
    "flyway-default-datasource": DEBT_MARKER,
    "object-store-blobs-migration": DEBT_MARKER,
    "libs-change-dependents": DEBT_MARKER,
    "kafka-acl-coverage": DEBT_MARKER,
    "duplicate-yaml-key-guard": DEBT_MARKER,
    "dotted-mp-messaging-key-guard": DEBT_MARKER,
    "db-migration-gate": DEBT_MARKER,
    "event-contract-coverage-ratchet": DEBT_MARKER,
    "schema-compat-gate": DEBT_MARKER,
    "kafka-dotted-key-ratchet": DEBT_MARKER,
    "external-feed-declaration-drift": DEBT_MARKER,
    "critical-alert-egress": DEBT_MARKER,
    "threat-model-coverage": DEBT_MARKER,
    "threat-model-updated-on-trust-boundary-change": DEBT_MARKER,
    "compliance-page-evidence": DEBT_MARKER,
    "scheduler-exercised-in-tests": DEBT_MARKER,
    "feature-flag-governance": DEBT_MARKER,
    "no-dead-code-service-principal-rego-rule": DEBT_MARKER,
    "psd2-anonymous-grant-stays-behind-eidas-mtls": DEBT_MARKER,
    "mcp-real-port-requires-caller-auth-first": DEBT_MARKER,
    "authz-enforce-pdp-sidecar-parity": DEBT_MARKER,
    "rolesallowed-realm-parity": DEBT_MARKER,
    "realm-template-importable": DEBT_MARKER,
    "opa-bundle-parses": DEBT_MARKER,
    "opa-bundle-apply-size": DEBT_MARKER,
    "configproperty-kotlin-defaults": DEBT_MARKER,
    "nat-ami-pinned": DEBT_MARKER,
    "enforcement-reachability": DEBT_MARKER,
    "readiness-attestation-format": DEBT_MARKER,
    "label-description-length": DEBT_MARKER,
    "pr-file-overlap": DEBT_MARKER,
    "agent-review-proof-falsifiable": DEBT_MARKER,
    "agent-review-scope-falsifiable": DEBT_MARKER,
    "manifest-types-only": DEBT_MARKER,
    "governance-manifest": DEBT_MARKER,
    "gate-invocation-reachability": DEBT_MARKER,
    "python-lint": DEBT_MARKER,
    "ruleset-context-parity": DEBT_MARKER,
    "advisory-finding-staleness": DEBT_MARKER,
    "gate-subject-floor": DEBT_MARKER,
    "gate-selftest-declaration": DEBT_MARKER,
}


def load(root="."):
    f = pathlib.Path(root) / MANIFEST
    if not f.exists():
        raise FileNotFoundError(f"{MANIFEST} not found")
    gates = (yaml.safe_load(f.read_text()) or {}).get("gates")
    if not gates:
        raise ValueError(f"{MANIFEST}: no gates found — refusing to report a pass")
    return gates


def analyse(gates, debt, today: datetime.date):
    """Return (missing, malformed, overdue, stale_baseline)."""
    ids = {g.get("id") for g in gates}
    missing, malformed, overdue = [], [], []
    for g in gates:
        gid = g.get("id")
        has_rationale = bool(g.get("rationale"))
        has_review = "review_after" in g
        if not has_rationale and not has_review:
            if gid not in debt:
                missing.append(gid)
            continue
        if not has_rationale or not has_review:
            malformed.append(f"{gid}: has one of rationale:/review_after: but not both")
            continue
        raw = g["review_after"]
        try:
            d = datetime.date.fromisoformat(str(raw))
        except ValueError:
            malformed.append(f"{gid}: review_after '{raw}' is not an ISO date (YYYY-MM-DD)")
            continue
        if d < today:
            overdue.append(f"{gid}: review_after {d} has passed (rationale: {g['rationale']!r})")

    stale = []
    for gid in sorted(debt):
        if gid not in ids:
            stale.append(f"{gid}: baselined but no such gate exists any more — remove it")
            continue
        g = next(x for x in gates if x.get("id") == gid)
        if g.get("rationale") and "review_after" in g:
            stale.append(f"{gid}: now declares rationale/review_after — remove it from the debt list")
    return missing, malformed, overdue, stale


def report(missing, malformed, overdue, stale, enforce):
    bad = False
    for gid in missing:
        print(f"::error::{gid}: no rationale:/review_after: and no baseline entry. A gate "
              f"needs a one-sentence reason it exists and a date to re-examine that reason — "
              f"add both, or add the id to DEBT in check-gate-lifecycle-metadata.py with why.",
              file=sys.stderr)
        bad = True
    for msg in malformed:
        print(f"::error::{msg}", file=sys.stderr)
        bad = True
    for msg in stale:
        print(f"::error::stale baseline — {msg}", file=sys.stderr)
        bad = True
    for msg in overdue:
        # Always a warning, never gated on --enforce — see the file header for why an overdue
        # review date is a prompt, not a failure.
        print(f"::warning::{msg}")
    if bad and not enforce:
        print("::warning::gate-lifecycle-metadata found violations (advisory run)")
        return 0
    return 1 if bad else 0


def self_test() -> int:
    fails = []
    today = datetime.date(2026, 8, 10)

    def case(label, gates, debt, want_missing, want_malformed, want_overdue, want_stale):
        m, mf, o, s = analyse(gates, debt, today)
        got = (sorted(m), len(mf), len(o), len(s))
        exp = (sorted(want_missing), want_malformed, want_overdue, want_stale)
        if got != exp:
            fails.append(f"{label}: expected {exp}, got {got}")

    ok = {"id": "a", "rationale": "catches X", "review_after": "2027-01-01"}
    case("a fully-declared gate is clean", [ok], set(), [], 0, 0, 0)
    case("neither field and no baseline is flagged", [{"id": "a"}], set(), ["a"], 0, 0, 0)
    case("neither field but baselined is accepted", [{"id": "a"}], {"a"}, [], 0, 0, 0)
    case("only rationale, no review_after is malformed",
         [{"id": "a", "rationale": "x"}], set(), [], 1, 0, 0)
    case("only review_after, no rationale is malformed",
         [{"id": "a", "review_after": "2027-01-01"}], set(), [], 1, 0, 0)
    case("a non-ISO review_after is malformed",
         [{"id": "a", "rationale": "x", "review_after": "not-a-date"}], set(), [], 1, 0, 0)
    case("a past review_after WARNS, does not fail",
         [{"id": "a", "rationale": "x", "review_after": "2020-01-01"}], set(), [], 0, 1, 0)
    case("a future review_after is clean",
         [{"id": "a", "rationale": "x", "review_after": "2099-01-01"}], set(), [], 0, 0, 0)
    case("a baseline entry for a vanished gate is stale", [ok], {"gone"}, [], 0, 0, 1)
    case("a baseline entry that healed is stale", [ok], {"a"}, [], 0, 0, 1)

    try:
        load(root="/nonexistent-root-for-self-test")
        fails.append("a missing manifest did not raise (would report a false clean)")
    except (FileNotFoundError, ValueError):
        pass

    import contextlib
    import io

    sink = io.StringIO()
    with contextlib.redirect_stderr(sink), contextlib.redirect_stdout(sink):
        rc_adv = report(["x"], [], [], [], enforce=False)
        rc_enf = report(["x"], [], [], [], enforce=True)
        rc_ok = report([], [], [], [], enforce=True)
        rc_overdue_only = report([], [], ["x overdue"], [], enforce=True)
    if rc_adv != 0:
        fails.append("advisory mode did not downgrade a violation to 0")
    if rc_enf != 1:
        fails.append("--enforce did not fail on a missing-field violation")
    if rc_ok != 0:
        fails.append("a clean run did not exit 0 under --enforce")
    if rc_overdue_only != 0:
        fails.append("an overdue review_after alone failed the gate under --enforce (must warn only)")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: gate-lifecycle-metadata is falsifiable (10 cases + exit codes)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="a gate declares why it exists and when to recheck")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--root", default=".")
    ap.add_argument("--today", help="YYYY-MM-DD (required unless --self-test)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not args.today:
        sys.stderr.write("::error::--today YYYY-MM-DD is required (see file header: no bare clock reads)\n")
        return 2
    try:
        today = datetime.date.fromisoformat(args.today)
    except ValueError:
        sys.stderr.write(f"::error::--today '{args.today}' is not an ISO date\n")
        return 2
    try:
        gates = load(args.root)
    except (FileNotFoundError, ValueError) as e:
        sys.stderr.write(f"::error::{e}\n")
        return 1

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import gatelib

    gatelib.subjects(len(gates), "gates in the manifest")
    m, mf, o, s = analyse(gates, DEBT, today)
    declared = len([g for g in gates if g.get("rationale") and "review_after" in g])
    print(f"gate-lifecycle-metadata: {declared}/{len(gates)} gates declare rationale + "
          f"review_after; {len(DEBT)} baselined as debt; {len(o)} overdue for review.")
    return report(m, mf, o, s, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
