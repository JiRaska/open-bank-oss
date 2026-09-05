#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: every ADVISORY gate in CI must be registered in rules.yaml with a deadline (issue #2392).
#
# WHY THIS EXISTS
#   ADR-0144 says an advisory rule must carry a `target_enforce_date`, and
#   check-gate-graduation.sh enforces that — but it walks rules.yaml and NOTHING ELSE. So the
#   invariant only ever applied to gates that had already opted in. A CI gate that never got a
#   rules.yaml entry was invisible to it: no deadline, no graduation pressure, no failure when it
#   sat advisory forever.
#
#   That is not hypothetical. check-prompt-registry.py ran `continue-on-error: true` with no entry
#   and was RED on main for a month (#2363) — the unregistered file being the prompt hardened
#   against injection. check-evals-registry.py the same (#2375). check-authz-pdp-parity.py cited
#   `rules.yaml: authz_pdp_parity`, a key that never existed, so it read as governed while being
#   exactly as unwatched (#2393). Each flip happened because a person ran the script by hand.
#
#   This script closes the loop from the CI side: rules.yaml can no longer be the only place that
#   knows a gate exists.
#
#   THE OTHER DIRECTION (#7941). The loop above starts from an ADVISORY gate, so the only
#   disagreement it can see is "gate advisory, rule enforced". The mirror — the gate already
#   BLOCKS while its rule still says `enforced: advisory` with a `target_enforce_date` — was
#   invisible, because an enforced gate was never enumerated. `cnpg_backup_declared` sat that
#   way: gates.yaml `mode: enforced`, `verified: 2026-08-16`; rules.yaml `enforced: advisory`,
#   due 2026-09-15. Besides being the same permanent-advisory defect recorded in the other file,
#   it arms an outage — on the stale date check-gate-graduation.sh fails every PR in the repo
#   over a control that has enforced for weeks, which is #7897 exactly.
#
# WHAT IT CHECKS
#   For every advisory governance gate — whether declared in .github/gates/gates.yaml or still
#   living as a workflow step — the checker it runs (`check-*.py|sh`, `run-evals.py`) must be named
#   as some rules.yaml rule's `ci_producer:`, and that rule must carry both
#   `enforced: advisory|planned` and a `target_enforce_date`.
#
#   TWO SOURCES, deliberately. Most gates now live in .github/gates/gates.yaml, where `mode:` says
#   advisory-or-enforced OUTRIGHT — no name heuristic, no `continue-on-error` inference. Workflow
#   steps are still scanned because gates exist outside the manifest (opa-policy.yml, fleet-lint.yml,
#   the pact workflows). Dropping the workflow scan when the manifest landed would have quietly
#   narrowed this gate; keeping only the workflow scan would have quietly emptied it, since the 79
#   steps it used to read moved into the manifest in the same PR. `--expect-manifest` (used in CI)
#   fails if the manifest yields nothing, so "the manifest moved again" cannot read as "no advisory
#   gates exist".
#
#   Both halves of "advisory" are covered on purpose. `continue-on-error` is the obvious one, but
#   most advisory gates here are advisory INSIDE the script — it prints ::warning and exits 0 unless
#   passed --enforce — so a scan for continue-on-error alone would have found 1 of the 12 that exist
#   today and reported the other 11 as enforced.
#
#   Deliberately NOT flagged: non-governance steps with continue-on-error (ECR login, cache warm,
#   Codecov upload, Trivy install). Those are best-effort infrastructure, not rules with a
#   graduation story, and demanding a rules.yaml entry for "Install Trivy" would make this gate
#   noise that gets silenced.
#
# Run:  python3 .github/scripts/check-advisory-gate-registration.py [--root .]

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

WORKFLOWS = ".github/workflows/*.yml"
MANIFEST = ".github/gates/gates.yaml"
RULES = "openbank-libs/governance/rules.yaml"

CHECKER_RE = re.compile(r"((?:\.github|openbank-infra)/scripts/(?:check|run)-[a-z0-9-]+\.(?:py|sh))")
GRADUATING = {"advisory", "planned"}


def rules_by_producer(root: pathlib.Path, errors):
    """Map every ci_producer script path -> (rule name, enforced, has_date).

    Walks the PARSED document, not the raw lines. A line-wise scan (the first draft here) attributes
    `enforced:`/`ci_producer:` to whichever bare `key:` line it saw last, which is wrong the moment a
    rule contains a nested mapping — `finops_tiers` holds a `tiers:` sub-tree and declares its own
    `enforced`/`target_enforce_date` AFTER it, so the fields landed under `T3`. The gate reported a
    rule as registered while reading another rule's metadata.
    """
    f = root / RULES
    if not f.is_file():
        errors.append(f"{RULES} not found — cannot check gate registration")
        return {}
    try:
        doc = yaml.safe_load(f.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{RULES} — not valid YAML: {e}")
        return {}

    out = {}

    def walk(node, name):
        if not isinstance(node, dict):
            return
        producer = node.get("ci_producer")
        if isinstance(producer, str):
            for script in CHECKER_RE.findall(producer):
                out[script] = (name, node.get("enforced"), "target_enforce_date" in node)
        for key, value in node.items():
            if isinstance(value, dict):
                walk(value, key)

    walk(doc, "<root>")
    return out


def advisory_steps(root: pathlib.Path, errors):
    """Yield (workflow, job, step-name, script) for advisory governance-checker steps."""
    for wf in sorted(root.glob(WORKFLOWS)):
        try:
            doc = yaml.safe_load(wf.read_text()) or {}
        except yaml.YAMLError as e:
            errors.append(f"{wf.name} — not valid YAML: {e}")
            continue
        for job_name, job in (doc.get("jobs") or {}).items():
            for step in (job.get("steps") or []):
                run = str(step.get("run") or "")
                name = str(step.get("name") or "")
                scripts = CHECKER_RE.findall(run)
                if not scripts:
                    continue
                # "enforced" in the name WINS over "advisory". Without that precedence this gate
                # flags itself — its own step is named "advisory-gate registration (…, enforced)",
                # and a step that says `(ADR-0148, enforced)` while explaining what advisory means
                # is a normal shape here. Same family as the comment-stripping in
                # check-roles-allowed-realm.py: a name-matching heuristic that cannot tell a gate
                # from a gate ABOUT gates manufactures its own findings.
                label = name.lower()
                is_advisory = step.get("continue-on-error") is True or (
                    "advisory" in label and "enforced" not in label
                )
                if not is_advisory:
                    continue
                for script in scripts:
                    yield wf.name, job_name, name, script


def advisory_manifest_gates(root: pathlib.Path, errors):
    """Yield (source, group, gate-name, script) for advisory gates in .github/gates/gates.yaml.

    No heuristic here: the manifest states `mode: advisory` outright, which is the whole reason
    the gates were moved into it. The step-name matching below exists only for the gates that
    still live in workflows.
    """
    f = root / MANIFEST
    if not f.is_file():
        return
    try:
        doc = yaml.safe_load(f.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{MANIFEST} — not valid YAML: {e}")
        return
    for gate in doc.get("gates") or []:
        if gate.get("mode") != "advisory":
            continue
        for script in CHECKER_RE.findall(str(gate.get("run") or "")):
            yield MANIFEST, gate.get("group", "?"), gate.get("id", "?"), script


def enforced_manifest_gates(root: pathlib.Path, errors):
    """Yield (source, group, gate-name, script) for ENFORCED gates in .github/gates/gates.yaml.

    The mirror of advisory_manifest_gates, and the direction this script was missing (#7941).
    Everything above walks the ADVISORY gates, so the pair it can detect is "gate advisory,
    rule enforced". The opposite pair — the gate already BLOCKS while its rule still says
    `enforced: advisory` with a `target_enforce_date` — never entered any loop, because an
    enforced gate was never enumerated at all.

    That is not the harmless half. A rule left advisory after its producer graduated is the
    exact failure ADR-0144 exists to prevent, and here it also arms a fleet-wide outage: on the
    stale `target_enforce_date`, check-gate-graduation.sh fails EVERY PR over a control that has
    been enforcing for weeks. `cnpg_backup_declared` was in that state — gates.yaml `mode:
    enforced` and `verified: 2026-08-16`, rules.yaml `enforced: advisory` due 2026-09-15 — and
    would have repeated #7897 a fortnight later.
    """
    f = root / MANIFEST
    if not f.is_file():
        return
    try:
        doc = yaml.safe_load(f.read_text()) or {}
    except yaml.YAMLError:
        return  # advisory_manifest_gates already reported it; do not double-count
    for gate in doc.get("gates") or []:
        if gate.get("mode") != "enforced":
            continue
        for script in CHECKER_RE.findall(str(gate.get("run") or "")):
            yield MANIFEST, gate.get("group", "?"), gate.get("id", "?"), script


def self_test() -> int:
    """Falsify the rules walker and both advisory-gate discoverers.

    ADR-0144: an advisory rule must carry a `target_enforce_date`, or "advisory" becomes
    permanent by omission — a gate that warns forever is a gate nobody ever has to satisfy.
    This script is what makes that checkable, so its own failure mode is the meta version of
    the same thing: discover FEWER advisory gates and the registration requirement quietly
    applies to fewer of them, with no signal that coverage shrank.

    The walker is the part with real history. A line-wise scan attributed `enforced:` to
    whichever bare `key:` it saw last, so a rule containing a nested mapping (`finops_tiers`
    holds a `tiers:` sub-tree) had its metadata read under the wrong name — the gate reported
    a rule as registered while reading ANOTHER rule's fields. The nested fixture below is
    that case.
    """
    import tempfile

    fails: list[str] = []

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        (root / RULES).parent.mkdir(parents=True, exist_ok=True)
        (root / RULES).write_text(
            "simple_rule:\n"
            "  enforced: advisory\n"
            '  target_enforce_date: "2026-12-31"\n'
            '  ci_producer: ".github/scripts/check-simple.py"\n'
            "no_date_rule:\n"
            "  enforced: advisory\n"
            '  ci_producer: ".github/scripts/check-nodate.py"\n'
            "nested_rule:\n"
            "  tiers:\n"
            "    T3:\n"
            "      budget: 10\n"
            "  enforced: advisory\n"
            '  target_enforce_date: "2026-12-31"\n'
            '  ci_producer: ".github/scripts/check-nested.py"\n'
        )
        errors: list = []
        got = rules_by_producer(root, errors)
        if errors:
            fails.append(f"a well-formed rules.yaml produced errors: {errors}")

        for script, want_name, want_date in (
            (".github/scripts/check-simple.py", "simple_rule", True),
            (".github/scripts/check-nodate.py", "no_date_rule", False),
            # THE HISTORICAL DEFECT: metadata after a nested mapping must still attribute to
            # the OUTER rule. Read under `T3` it looked registered while describing nothing.
            (".github/scripts/check-nested.py", "nested_rule", True),
        ):
            entry = got.get(script)
            if entry is None:
                fails.append(f"{script} was not found in the walk: {sorted(got)}")
                continue
            name, _enforced, has_date = entry
            if name != want_name:
                fails.append(f"{script} attributed to rule {name!r}, expected {want_name!r} "
                             f"— metadata was read under the wrong rule")
            if has_date != want_date:
                fails.append(f"{script} has_date={has_date}, expected {want_date}")

        # A missing rules.yaml is an ERROR, not an empty map: silently returning {} makes
        # every advisory gate look unregistered, or (worse, depending on the caller) makes the
        # check pass having read nothing.
        errors2: list = []
        rules_by_producer(root / "nowhere", errors2)
        if not errors2:
            fails.append("a missing rules.yaml produced no error")

        # --- the manifest discoverer -------------------------------------------------------
        gates_dir = root / ".github" / "gates"
        gates_dir.mkdir(parents=True, exist_ok=True)
        (gates_dir / "gates.yaml").write_text(
            "gates:\n"
            "  - id: adv\n"
            "    name: an advisory gate\n"
            "    group: lint\n"
            "    mode: advisory\n"
            "    run: |\n"
            "      python3 .github/scripts/check-adv.py\n"
            "  - id: enf\n"
            "    name: an enforced gate\n"
            "    group: lint\n"
            "    mode: enforced\n"
            "    run: |\n"
            "      python3 .github/scripts/check-enf.py\n"
        )
        errs3: list = []
        found = {script for _src, _grp, _gid, script in advisory_manifest_gates(root, errs3)}
        if ".github/scripts/check-adv.py" not in found:
            fails.append(f"the advisory manifest gate was not discovered: {sorted(found)}")
        if ".github/scripts/check-enf.py" in found:
            fails.append("an ENFORCED gate was discovered as advisory — it would then be "
                         "required to carry a target_enforce_date it has no need of")

        # --- the reverse discoverer (#7941) ------------------------------------------------
        # The mirror of the two assertions above, and the direction this script was blind to.
        # It must find the ENFORCED gate and must NOT find the advisory one, or the new check
        # either misses the drift it exists for or re-reports every advisory gate as drifted.
        enf_found = {script for _s, _g, _i, script in enforced_manifest_gates(root, [])}
        if ".github/scripts/check-enf.py" not in enf_found:
            fails.append(f"the enforced manifest gate was not discovered: {sorted(enf_found)}")
        if ".github/scripts/check-adv.py" in enf_found:
            fails.append("an ADVISORY gate was discovered as enforced — every advisory gate "
                         "would then be reported as drifted from its own rule")

        # END TO END, which is what actually matters: the same tree must go RED while the rule
        # lags its enforced gate, and GREEN once the rule catches up. Asserting only the
        # discoverer would leave the comparison itself unfalsified — a set of gate names proves
        # nothing about whether anything is compared to rules.yaml.
        drift_root = root / "drift"
        (drift_root / ".github" / "gates").mkdir(parents=True, exist_ok=True)
        (drift_root / RULES).parent.mkdir(parents=True, exist_ok=True)
        (drift_root / ".github" / "gates" / "gates.yaml").write_text(
            "gates:\n"
            "  - id: enf\n"
            "    name: an enforced gate\n"
            "    group: lint\n"
            "    mode: enforced\n"
            "    run: |\n"
            "      python3 .github/scripts/check-enf.py\n"
        )
        lagging = (
            "lagging_rule:\n"
            "  enforced: advisory\n"
            '  target_enforce_date: "2026-12-31"\n'
            '  ci_producer: ".github/scripts/check-enf.py"\n'
        )
        caught_up = (
            "lagging_rule:\n"
            "  enforced: enforce\n"
            '  enforced_since: "2026-09-01"\n'
            '  ci_producer: ".github/scripts/check-enf.py"\n'
        )
        import io, contextlib

        def run_against(text):
            (drift_root / RULES).write_text(text)
            buf = io.StringIO()
            argv = sys.argv
            sys.argv = ["x", "--root", str(drift_root)]
            try:
                with contextlib.redirect_stderr(buf), contextlib.redirect_stdout(io.StringIO()):
                    rc = main()
            finally:
                sys.argv = argv
            return rc, buf.getvalue()

        rc, out = run_against(lagging)
        if rc != 1:
            fails.append("a rule still `advisory` while its gate is `mode: enforced` did not "
                         "fail the check — the drift that armed #7897 goes unreported")
        elif "BLOCKS today" not in out:
            fails.append(f"the drift was flagged for the wrong reason: {out.strip()[:200]}")
        # ...and the SAME tree, one field changed, must go green. Without this the check could
        # be failing on anything at all and still look correct above.
        rc, out = run_against(caught_up)
        if rc != 0:
            fails.append(f"a rule that caught up to its enforced gate still fails: {out.strip()[:200]}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: advisory-gate registration is falsifiable (13 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument(
        "--expect-manifest",
        action="store_true",
        help="fail if .github/gates/gates.yaml contributes no advisory gate (see header)",
    )
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()

    errors = []
    producers = rules_by_producer(root, errors)
    checked = 0

    from_manifest = list(advisory_manifest_gates(root, errors))
    if args.expect_manifest and not from_manifest:
        errors.append(
            f"{MANIFEST} contributed 0 advisory gates. Either every gate really is enforced now "
            f"(then drop --expect-manifest in ci.yml, in the same commit), or this script has "
            f"stopped reading the manifest and is about to report a green over nothing.",
        )

    for wf, job, name, script in list(from_manifest) + list(advisory_steps(root, errors)):
        checked += 1
        where = f"{wf} :: {job} :: {name or script}"
        if script not in producers:
            errors.append(
                f"{where} — advisory gate running {script} has no rules.yaml rule naming it as "
                f"`ci_producer`. An advisory gate with no rule has no target_enforce_date, so "
                f"check-gate-graduation.sh (ADR-0144) cannot see it and it stays advisory forever. "
                f"Add a rule with `enforced: advisory` + `target_enforce_date` + `ci_producer`, or "
                f"make the step enforced.",
            )
            continue
        rule, enforced, has_date = producers[script]
        if enforced not in GRADUATING:
            errors.append(
                f"{where} — rules.yaml `{rule}` declares `enforced: {enforced}` but the CI step is "
                f"advisory. The rule and the gate disagree about whether this blocks; fix whichever "
                f"is wrong.",
            )
        elif not has_date:
            errors.append(
                f"{where} — rules.yaml `{rule}` is `enforced: {enforced}` with no "
                f"`target_enforce_date` (ADR-0144 requires one).",
            )

    # --- the reverse direction (#7941) --------------------------------------------------
    # An ENFORCED gate whose rule still reads `enforced: advisory|planned`. Nothing above can
    # see this: every loop so far starts from an advisory gate.
    enforced_checked = 0
    for _wf, job, name, script in enforced_manifest_gates(root, errors):
        if script not in producers:
            continue  # an enforced gate needs no rule; that is not this check's business
        enforced_checked += 1
        rule, enforced, has_date = producers[script]
        if enforced in GRADUATING:
            errors.append(
                f"{MANIFEST} :: {job} :: {name} — the gate is `mode: enforced` and BLOCKS today, "
                f"but rules.yaml `{rule}` still declares `enforced: {enforced}`"
                + (" with a `target_enforce_date`" if has_date else "")
                + ". The producer graduated and the rule did not follow it, which is the "
                "permanent-advisory failure ADR-0144 exists to prevent — and while the stale "
                "date stands, check-gate-graduation.sh will fail EVERY PR on it the day it "
                "passes, over a control that already enforces (#7897). Set `enforced: enforce` "
                "+ `enforced_since:` and drop the date.",
            )

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=Advisory gate registration::{e}\n")
        sys.stderr.write(
            f"::error::check-advisory-gate-registration: {len(errors)} gate/rule "
            f"registration mismatch(es).\n",
        )
        return 1

    print(
        f"advisory-gate registration: {checked} advisory governance gate(s) checked "
        f"({len(from_manifest)} from {MANIFEST}, {checked - len(from_manifest)} from workflow "
        f"steps) against {len(producers)} rules.yaml ci_producer entr(ies); all registered with "
        f"a deadline. {enforced_checked} enforced gate(s) cross-checked the other way; none is "
        f"still advisory in rules.yaml.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
