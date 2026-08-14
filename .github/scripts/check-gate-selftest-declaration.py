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

# WHERE THE EXEMPTIONS LIVE, and why they moved (#4587).
#
# They used to be two dicts in THIS file: BASELINE (correct as-is) and a DEBT set. That made
# one contiguous, alphabetically-sorted block of ~50 entries that EVERY gate PR edits — paying
# off a gate means deleting a line from it. Contiguous plus universally-edited is the exact
# recipe for a merge conflict, and it delivered: this one block conflicted SIX times on
# 2026-08-13 alone, across #4513, #4547, #4557, #4575 and two others.
#
# Worse than the cost is the shape of the mistake it invites. Git reports the same conflict
# whether both sides ADDED entries or both REMOVED them, and the two need opposite
# resolutions: "keep both" is right for additions and silently puts already-paid-off gates
# back on the debt list for removals. Nothing in the diff tells you which case you are in.
#
# So the exemption now lives on the gate it exempts, as `selftest_exempt:` in gates.yaml.
# Each gate's data is its own block in a 3000-line file, so two PRs touching different gates
# no longer touch the same lines — they conflict only when they genuinely edit the same gate,
# which is a conflict worth having.
#
# Two properties fall out for free, rather than being enforced:
#   * an exemption for a gate that no longer exists is now IMPOSSIBLE, not merely detected —
#     deleting the gate deletes its exemption with it;
#   * the reason sits next to the thing it excuses, where a reviewer of that gate reads it.
#
# The vocabulary is closed, and the category is the part that carries meaning:
#   is-a-test-suite   the `run:` IS a unit-test suite, so it falsifies itself by construction
#   third-party       an externally maintained linter; we do not own its tests
#   debt              a real checker with no harness. Not excused, just not fixed today.
EXEMPT_FIELD = "selftest_exempt"
CATEGORIES = ("is-a-test-suite", "third-party", "debt")


def exemptions(gates):
    """id -> reason, read from each gate's own entry. Split into (baseline, debt) because the
    two are NOT interchangeable: `debt` never excuses a harness that demonstrably exists
    (rule 1), while `is-a-test-suite` does."""
    baseline, debt, malformed = {}, {}, []
    for g in gates:
        raw = _norm(g.get(EXEMPT_FIELD))
        if not raw:
            continue
        gid = g.get("id")
        category = raw.split(" ", 1)[0].split("\u2014")[0].strip()
        if category not in CATEGORIES:
            malformed.append(
                f"{gid}: {EXEMPT_FIELD} must start with one of {', '.join(CATEGORIES)} "
                f"followed by a reason; got {raw!r}")
            continue
        (debt if category == "debt" else baseline)[gid] = raw
    return baseline, debt, malformed


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
    baseline = {} if baseline is None else baseline
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
    # Rule 3 — one direction only now, and that is a gain rather than a loss. The old
    # "baselined but no such gate exists" case is unreachable by construction: the exemption
    # is a field ON the gate, so deleting the gate deletes it. What remains is the case that
    # can still rot — a gate that has since grown a self-test and kept its exemption.
    stale = []
    for gid in sorted(known):
        g = next((x for x in gates if x.get("id") == gid), None)
        if g is None:
            continue
        if g.get("selftest") and gid not in baseline:
            stale.append(f"{gid}: now declares a self-test — drop its {EXEMPT_FIELD} field")
    return undeclared_with_harness, new_undeclared, stale, run_twice


def report(undeclared_with_harness, new_undeclared, stale, run_twice, enforce):
    bad = False
    for gid in run_twice:
        print(f"::error::{gid}: the command in `selftest:` also appears in `run:`, so the "
              f"harness executes twice for one signal. Either drop it from run: (the gate has "
              f"real work of its own), or drop the `selftest:` field and baseline the gate as "
              f"`{EXEMPT_FIELD}: is-a-test-suite — <reason>` on the gate (the gate IS the "
              f"harness).", file=sys.stderr)
        bad = True
    for gid in undeclared_with_harness:
        print(f"::error::{gid}: its run: invokes a --self-test but `selftest:` is not declared. "
              f"run-gates.py cannot see it, so there is no ordering guarantee, no "
              f"selftest_expect verdict check, and a broken harness is indistinguishable from "
              f"a failing gate. Move the command into the `selftest:` field.", file=sys.stderr)
        bad = True
    for gid in new_undeclared:
        print(f"::error::{gid}: a gate with no `selftest:` and no baseline entry. A gate that "
              f"has only ever passed is unfalsified — add a `selftest:`, or add a "
              f"`{EXEMPT_FIELD}:` field to THIS GATE'S OWN ENTRY in gates.yaml, starting "
              f"with one of {', '.join(CATEGORIES)} and a reason.", file=sys.stderr)
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
    # The old "an exemption for a vanished gate is stale" case is GONE, and deliberately so:
    # the exemption is a field on the gate, so deleting the gate deletes it. Asserting a
    # property that cannot fail would be exactly the vacuous test this whole checker exists to
    # forbid. What replaces it is a test that the derivation actually reads the field, and
    # that a malformed category is refused rather than silently treated as an exemption.
    def derive_case(label, gates, want_baseline, want_debt, want_malformed):
        b, d, mal = exemptions(gates)
        got = (sorted(b), sorted(d), len(mal))
        exp = (sorted(want_baseline), sorted(want_debt), want_malformed)
        if got != exp:
            fails.append(f"{label}: expected {exp}, got {got}")

    derive_case("an is-a-test-suite exemption lands in baseline, not debt",
                [{"id": "a", "selftest_exempt": "is-a-test-suite — the run: IS the harness"}],
                ["a"], [], 0)
    derive_case("a debt exemption lands in debt, not baseline",
                [{"id": "b", "selftest_exempt": "debt — no harness yet"}], [], ["b"], 0)
    derive_case("third-party counts as baseline",
                [{"id": "c", "selftest_exempt": "third-party — not our tests"}], ["c"], [], 0)
    derive_case("an unknown category is REFUSED, not treated as an exemption",
                [{"id": "d", "selftest_exempt": "because-i-said-so — trust me"}], [], [], 1)
    derive_case("a bare reason with no category is refused",
                [{"id": "e", "selftest_exempt": "no harness yet"}], [], [], 1)
    derive_case("a gate with no field is not exempt at all",
                [{"id": "f", "run": "x"}], [], [], 0)
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
    print("self-test ok: gate-selftest-declaration is falsifiable (22 cases)")
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

    baseline, debt, malformed = exemptions(gates)
    for msg in malformed:
        sys.stderr.write(f"::error::{msg}\n")
    if malformed:
        return 1
    h, n, s, t = analyse(gates, debt, baseline)
    declared = len([g for g in gates if g.get("selftest")])
    print(f"gate-selftest-declaration: {declared}/{len(gates)} gates declare a self-test; "
          f"{len(baseline)} exempt by kind, {len(debt)} carrying debt "
          f"(all read from each gate's own selftest_exempt field).")
    return report(h, n, s, t, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
