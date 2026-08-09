#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# FALSIFIABILITY MUST BE DECLARED, NOT MERELY PRESENT.
#
# This repo's hardest-won CI rule is that a gate which has only ever passed is unfalsified:
# its failure path is code nobody has run, and it fails in ways a green/red signal cannot
# express (#2165, #2154, #2177). `gates.yaml` answers that with a `selftest:` field, and
# run-gates.py acts on it — it runs the self-test BEFORE the gate and skips the gate entirely
# when the verdict is wrong.
#
# All of which is worth exactly nothing if the self-test is not in that field. Measured on
# 2026-08-09 across 128 gates: 63 declared one, and **12 more ran a `--self-test` inside
# their own `run:` block**. To a human reading gates.yaml those twelve look falsified. To
# run-gates.py they do not exist: no ordering guarantee, no `selftest_expect` verdict check,
# and a self-test that starts failing is indistinguishable from the gate failing. Two others
# (`check-slo-registry.py`, `check-asvs-l3.py`) shipped a `--self-test` harness that nothing
# anywhere called.
#
# This is the same defect as `mode:` — the repo already learned that advisory-vs-enforced has
# to be stated outright rather than inferred, because inferring it from a step name is how
# the registration gate once flagged itself (#2450, #2392). Falsifiability is the same kind
# of property: a declared one is enumerable, auditable and acted upon; an undeclared one is a
# convention that reads as coverage.
#
# WHAT THIS ENFORCES
#   1. A gate whose `run:` invokes `--self-test`/`--selftest` must declare it in `selftest:`.
#      The harness demonstrably exists, so there is nothing to weigh. It is also the direction
#      that silently regresses, since copying an existing gate's shape is how the twelve
#      happened. One exception, and it must be WRITTEN DOWN in BASELINE: a gate whose entire
#      purpose is the harness (`*-unit-test`, the runner's own self-test) has nothing else to
#      run, and declaring the command would only make it run twice — see rule 4.
#   4. (listed here because it is the direct consequence of 1) A command must not be BOTH declared in `selftest:` and invoked by `run:`. run-gates.py
#      runs the declaration first and the gate after it, so that shape executes the suite twice
#      for one signal. Ten gates landed that way on 2026-08-09 while fixing rule 1: for
#      `pact-version-tree-equivalence-unit-test` the duplicate half is ~17s, on the shard that
#      was already the slowest after gitops.
#   2. RATCHET: a NEW gate must declare `selftest:` or be listed in the baseline below with a
#      reason. Today's 52 undeclared gates are baselined, not fixed — writing 52 harnesses is
#      a separate piece of work, and a gate that fails on the debt is a gate nobody can merge
#      past. What this stops is the debt GROWING, which is the property that matters.
#   3. Both directions: a baseline entry for a gate that now declares a self-test, or for a
#      gate id that no longer exists, is also an error. A baseline that can only grow is the
#      hand-kept list this repo already knows reads as passing when it is short.
#
# Usage:  check-gate-selftest-declaration.py [--enforce]
#         check-gate-selftest-declaration.py --self-test
#
# (Yes, this checker has a self-test. A gate about falsifiability that could not itself fail
#  would be the joke writing itself.)

import argparse
import pathlib
import re
import sys

import yaml

MANIFEST = ".github/gates/gates.yaml"
SELFTEST_RE = re.compile(r"--self-?test\b")

# Gates with no self-test as of 2026-08-09, each with the reason it is acceptable — or, where
# it is simply debt, saying so in those words. This list may SHRINK freely; growing it needs a
# reason a reviewer accepts, which is the point (repo rule: make the exclusions the thing a
# human has to justify).
#
# Categories used below:
#   is-a-test-suite   the `run:` IS a unit-test suite, so it falsifies itself by construction
#   third-party       the command is an externally maintained linter; we do not own its tests
#   debt              a real checker with no harness. Not excused, just not fixed today.
BASELINE = {
    # --- the run: is itself a test suite -------------------------------------------------
    "gate-runner-self-test": "is-a-test-suite — the run: IS run-gates.py --self-test",
    "governance-script-unit-tests": "is-a-test-suite — pytest over the governance scripts",
    "auto-deploy-reconcile-probe-unit-test": "is-a-test-suite",
    "can-i-deploy-block-classifier-unit-test": "is-a-test-suite",
    "can-i-deploy-version-selector-unit-test": "is-a-test-suite",
    "co-deploy-set-derivation-unit-test": "is-a-test-suite",
    "pact-version-tree-equivalence-unit-test": "is-a-test-suite",
    "pact-version-probe-fail-closed-unit-test": "is-a-test-suite",
    "blocking-counterpart-probe-unit-test": "is-a-test-suite",
    "record-deployment-version-resolver": "is-a-test-suite",
    "runtime-conformance-comparators": "is-a-test-suite",
    "libs-change-dependents": "is-a-test-suite",
    "agent-review-proof-falsifiable": "is-a-test-suite",
    "agent-review-scope-falsifiable": "is-a-test-suite",
    "ensure-ecr-repository": "is-a-test-suite — the deploy path needs AWS, so the "
                             "classification harness is the whole gate",
    # --- externally maintained tooling ---------------------------------------------------
    "yamllint": "third-party — yamllint's own test suite is not ours to run",
    "shellcheck": "third-party — shellcheck's own test suite is not ours to run",
    "python-lint": "third-party — ruff's own test suite is not ours to run",
}

# Everything else undeclared as of the measurement date is debt, enumerated at import time so
# the file cannot silently disagree with the manifest. Kept separate from BASELINE so the two
# reasons never blur: BASELINE is "correct as is", DEBT is "should get a harness eventually".
DEBT_MARKER = "debt — no self-test harness yet (baselined 2026-08-09, #4335)"


def load(root="."):
    f = pathlib.Path(root) / MANIFEST
    if not f.exists():
        raise FileNotFoundError(f"{MANIFEST} not found")
    doc = yaml.safe_load(f.read_text()) or {}
    gates = doc.get("gates")
    if not gates:
        # Never report a clean verdict about an empty manifest: that is the shape where a
        # gate over a list reads as passing precisely because it found nothing.
        raise ValueError(f"{MANIFEST}: no gates found — refusing to report a pass")
    return gates


def _norm(text):
    return " ".join(str(text or "").split())


def analyse(gates, debt, baseline=None):
    """Return (undeclared_with_harness, new_undeclared, stale_baseline, run_twice).

    `baseline` is a PARAMETER, not the module global, so the self-test can drive fixtures.
    An earlier version read the global here and every fixture inherited the real 8-entry
    exemption list — every case reported 8 stale entries and the self-test could not express
    any of the behaviours it was written to pin down. The counter-example has to reach the
    code, and a shared global is one of the ways it quietly does not."""
    baseline = BASELINE if baseline is None else baseline
    ids = {g.get("id") for g in gates}
    known = set(baseline) | set(debt)

    undeclared_with_harness, new_undeclared, run_twice = [], [], []
    for g in gates:
        gid = g.get("id")
        declared = _norm(g.get("selftest"))
        run = _norm(g.get("run"))
        runs_selftest = bool(SELFTEST_RE.search(run))
        if declared:
            # Rule 4 — the same harness must not be BOTH declared and invoked by the gate.
            # run-gates.py runs `selftest:` first and `run:` after it, so a gate whose run:
            # still contains the command executes the suite twice, for one signal. This is
            # not hypothetical tidiness: it is how #4336 landed, and on
            # pact-version-tree-equivalence-unit-test the duplicate half is ~17s of the
            # supplychain shard. A gate whose ONLY purpose is the harness belongs in
            # BASELINE as `is-a-test-suite` with the command in run: and no declaration —
            # same signal, half the cost.
            if declared in run:
                run_twice.append(gid)
            continue
        if runs_selftest and gid not in baseline:
            # Rule 1 — hard. The harness exists; only the declaration is missing. A BASELINE
            # entry is the one way out, and it has to be written down: `gate-runner-self-test`
            # used to be a hardcoded exception here, which made the legitimate shape
            # unstateable for every other gate of the same kind.
            undeclared_with_harness.append(gid)
        elif gid not in known:
            # Rule 2 — ratchet.
            new_undeclared.append(gid)

    # Rule 3 — both directions.
    stale = []
    for gid in sorted(known):
        if gid not in ids:
            stale.append(f"{gid}: baselined but no such gate exists any more — remove the entry")
        else:
            g = next(x for x in gates if x.get("id") == gid)
            if g.get("selftest") and gid not in baseline:
                stale.append(f"{gid}: now declares a self-test — remove it from the debt list")
    return undeclared_with_harness, new_undeclared, stale, run_twice


def report(undeclared_with_harness, new_undeclared, stale, run_twice, enforce):
    bad = False
    for gid in run_twice:
        print(f"::error::{gid}: the command in `selftest:` also appears in `run:`, so the "
              f"harness executes twice for one signal. Either drop it from run: (the gate has "
              f"real work of its own), or drop the `selftest:` field and baseline the gate as "
              f"`is-a-test-suite` (the gate IS the harness).", file=sys.stderr)
        bad = True
    for gid in undeclared_with_harness:
        print(f"::error::{gid}: its run: invokes a --self-test but `selftest:` is not declared. "
              f"run-gates.py cannot see it, so there is no ordering guarantee, no "
              f"selftest_expect verdict check, and a broken harness is indistinguishable from "
              f"a failing gate. Move the command into the `selftest:` field.", file=sys.stderr)
        bad = True
    for gid in new_undeclared:
        print(f"::error::{gid}: a gate with no `selftest:` and no baseline entry. A gate that "
              f"has only ever passed is unfalsified — add a self-test, or add the id to "
              f"BASELINE/DEBT in this script with a reason.", file=sys.stderr)
        bad = True
    for msg in stale:
        print(f"::error::stale baseline — {msg}", file=sys.stderr)
        bad = True

    if bad and not enforce:
        print("::warning::gate-selftest-declaration found violations (advisory run)")
        return 0
    return 1 if bad else 0


def self_test():
    fails = []

    def case(label, gates, debt, want_harness, want_new, want_stale, baseline=None,
             want_twice=()):
        h, n, s, t = analyse(gates, debt, baseline if baseline is not None else {})
        got = (sorted(h), sorted(n), len(s), sorted(t))
        exp = (sorted(want_harness), sorted(want_new), want_stale, sorted(want_twice))
        if got != exp:
            fails.append(f"{label}: expected {exp}, got {got}")

    ok = {"id": "ok", "selftest": "x --self-test", "run": "x"}

    # A properly declared gate is clean — the case that separates this from a gate which
    # flags everything.
    case("a declared self-test is clean", [ok], {}, [], [], 0)

    # Rule 1: the harness exists in run: but is not declared. This is the measured defect —
    # 12 gates on 2026-08-09 — and it must fail even though a human reading gates.yaml would
    # see a self-test right there.
    case("an inline --self-test must be flagged",
         [{"id": "inline", "run": "python3 x.py --self-test\npython3 x.py --enforce"}], {},
         ["inline"], [], 0)
    case("the --selftest spelling counts too",
         [{"id": "inline2", "run": "python3 x.py --selftest"}], {}, ["inline2"], [], 0)

    # ...and being baselined must NOT excuse it. A declaration that exists cannot be waived;
    # otherwise the baseline becomes a way to hide the one violation that is never a
    # judgement call.
    case("a baseline entry does not excuse an existing harness",
         [{"id": "inline3", "run": "x --self-test"}], {"inline3": "debt"}, ["inline3"], [], 0)

    # Rule 2: ratchet. Unknown and undeclared fails; baselined and undeclared passes.
    case("a new undeclared gate must be flagged",
         [{"id": "fresh", "run": "python3 x.py"}], {}, [], ["fresh"], 0)
    case("a baselined undeclared gate is accepted",
         [{"id": "old", "run": "python3 x.py"}], {"old": "debt"}, [], [], 0)

    # Rule 3: both directions, so the list cannot rot either way.
    case("a baseline entry for a vanished gate is stale",
         [ok], {"gone": "debt"}, [], [], 1)
    case("a baseline entry that healed is stale",
         [{"id": "healed", "selftest": "x --self-test", "run": "x"}], {"healed": "debt"}, [], [], 1)

    # A gate that IS its harness — the runner's own self-test, and the *-unit-test gates whose
    # run: is a `--self-test` invocation. There is no separate harness to declare, and
    # declaring one would just run the suite twice (rule 4). BASELINE is what states that; DEBT
    # is not, which is why the case above still fails on a DEBT entry.
    case("a gate that IS its harness is exempt when baselined as such",
         [{"id": "gate-runner-self-test", "run": "python3 .github/scripts/run-gates.py --self-test"}],
         {}, [], [], 0, baseline={"gate-runner-self-test": "is-a-test-suite"})

    # Rule 4: declared AND still invoked by the gate = the suite runs twice for one signal.
    case("a declared harness also invoked in run: is flagged",
         [{"id": "twice", "selftest": "x --self-test", "run": "x --self-test\nx --enforce"}],
         {}, [], [], 0, want_twice=["twice"])
    case("run: IS the declared harness, verbatim",
         [{"id": "same", "selftest": "x --self-test", "run": "x --self-test"}],
         {}, [], [], 0, want_twice=["same"])
    case("whitespace and line breaks do not hide the duplicate",
         [{"id": "wrapped", "selftest": "x  --self-test\n", "run": "  x --self-test  \n"}],
         {}, [], [], 0, want_twice=["wrapped"])
    # ...and the shape it must NOT flag: a declared harness whose gate does real work. Without
    # this case a rule that flagged every declared self-test would look correct.
    case("a declared harness plus unrelated gate work is clean",
         [{"id": "fine", "selftest": "x --self-test", "run": "x --enforce --root ."}],
         {}, [], [], 0)

    # An empty manifest must RAISE, never report clean.
    try:
        load(root="/nonexistent-root-for-self-test")
        fails.append("a missing manifest did not raise (would report a false clean)")
    except (FileNotFoundError, ValueError):
        pass

    # Exit codes: advisory downgrades to 0, --enforce does not. Silenced — report() prints to
    # stderr by design, and an ::error line from a PASSING self-test reads as a failure in CI.
    import contextlib, io
    sink = io.StringIO()
    with contextlib.redirect_stderr(sink), contextlib.redirect_stdout(sink):
        rc_adv = report(["x"], [], [], [], enforce=False)
        rc_enf = report(["x"], [], [], [], enforce=True)
        rc_ok = report([], [], [], [], enforce=True)
        rc_twice = report([], [], [], ["x"], enforce=True)
    if rc_adv != 0:
        fails.append("advisory mode did not downgrade a violation to 0")
    if rc_enf != 1:
        fails.append("--enforce did not fail on a violation")
    if rc_ok != 0:
        fails.append("a clean run did not exit 0 under --enforce")
    if rc_twice != 1:
        fails.append("--enforce did not fail on a run-twice violation")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: gate-selftest-declaration is falsifiable (17 cases)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="every gate's falsifiability must be DECLARED")
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

    debt = DEBT
    h, n, s, t = analyse(gates, debt)
    declared = len([g for g in gates if g.get("selftest")])
    print(f"gate-selftest-declaration: {declared}/{len(gates)} gates declare a self-test; "
          f"{len(BASELINE)} exempt by kind, {len(DEBT)} baselined as debt.")
    return report(h, n, s, t, args.enforce)


# The debt list, written out rather than derived, so that shrinking it is a visible diff and
# growing it needs a reviewer. Derived would have been worse here: a set computed from the
# manifest agrees with the manifest by construction and could never flag anything (the same
# self-corroboration trap as widening a known-good set with the layer it is checking).
DEBT = {
    "accounting-clock-gate": DEBT_MARKER,
    "admin-ui-version-sync-guard": DEBT_MARKER,
    "adr-registry-integrity-check": DEBT_MARKER,
    "advisory-gate-registration": DEBT_MARKER,
    "agent-charter-registry-parity": DEBT_MARKER,
    "ai-act-high-risk-inventory-vs-code": DEBT_MARKER,
    "ai-governance-snapshot-drift": DEBT_MARKER,
    "authz-enforce-pdp-sidecar-parity": DEBT_MARKER,
    "clock-injection-gate": DEBT_MARKER,
    "critical-alert-egress": DEBT_MARKER,
    "db-backup-association-gate": DEBT_MARKER,
    "db-migration-gate": DEBT_MARKER,
    "deploy-coverage-guard": DEBT_MARKER,
    "domainevent-occurredat-constructor-guard": DEBT_MARKER,
    "dotted-mp-messaging-key-guard": DEBT_MARKER,
    "duplicate-yaml-key-guard": DEBT_MARKER,
    "eu-ai-act-inventory-drift": DEBT_MARKER,
    "evals-registry-integrity": DEBT_MARKER,
    "event-consumer-liveness": DEBT_MARKER,
    "event-contract-coverage-ratchet": DEBT_MARKER,
    "feature-flag-governance": DEBT_MARKER,
    "gate-graduation-guard": DEBT_MARKER,
    "gen-network-policies-drift-gate": DEBT_MARKER,
    "gitops-ref-integrity-guard": DEBT_MARKER,
    "identifier-intent-guard": DEBT_MARKER,
    "mcp-charter-data-scope-binding": DEBT_MARKER,
    "mcp-real-port-requires-caller-auth-first": DEBT_MARKER,
    "no-dead-code-service-principal-rego-rule": DEBT_MARKER,
    "no-runblocking-in-a-scheduled-body": DEBT_MARKER,
    "no-service-local-exceptionmapper-collision-with-libs-runtime": DEBT_MARKER,
    "openapi-route-conformance": DEBT_MARKER,
    "openapi-server-port": DEBT_MARKER,
    "operator-write-naming": DEBT_MARKER,
    "outbox-dispatch-enabled-guard": DEBT_MARKER,
    "prompt-registry-integrity": DEBT_MARKER,
    "psd2-anonymous-grant-stays-behind-eidas-mtls": DEBT_MARKER,
    "quarkus-application-version-override-guard": DEBT_MARKER,
    "release-registration-consistency": DEBT_MARKER,
    "release-scope-mismatch-gate": DEBT_MARKER,
    "rolesallowed-realm-parity": DEBT_MARKER,
    "schema-compat-gate": DEBT_MARKER,
    "service-runbook-drift": DEBT_MARKER,
    "test-runblocking-unit-guard": DEBT_MARKER,
    "threat-model-coverage": DEBT_MARKER,
}

if __name__ == "__main__":
    sys.exit(main())
