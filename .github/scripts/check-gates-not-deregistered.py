#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A gate may not disappear from the manifest by accident. Removing one has to be said out loud.

WHY THIS EXISTS — a measured incident, not a hypothetical
---------------------------------------------------------
On 2026-08-02, PR #3492 squash-merged and silently removed THREE enforced gates from
`.github/gates/gates.yaml`:

    scheduled-trigger-emitted     (added by #3484, hours earlier)
    gitops-duplicate-resources    (added by #3474)
    gate-script-registration      (added by #3486)

Nobody removed them. #3492 was branched from an older `main` and every new gate is appended near the
same anchor in this one file, so its version of `gates.yaml` simply replayed over the others'.
GitHub reported the PR `MERGEABLE` / `CLEAN`; all five required checks were green. Three enforced
gates stopped running and nothing said anything — the textbook semantic merge conflict this repo
already documents for parallel agents, in its silent form.

`gates.yaml` is the highest-collision file in the tree precisely BECAUSE the manifest design
succeeded: adding a gate is now one entry in one file, so every agent adding a gate edits the same
file in the same region.

WHY THE OTHER GATE IS NOT ENOUGH
--------------------------------
`check-gate-script-registration.py` catches the *symptom* — a `check-*` script nothing runs — and it
did catch two of the three above. It cannot catch a lost entry whose script is still invoked
somewhere else, nor a gate whose `run:` is an inline command with no script at all, nor a gate
silently downgraded from `enforced` to `advisory`. This one compares the manifest to its own past.

WHAT IT CHECKS, against the merge-base with the default branch:
  1. no gate id present there is missing here;
  2. no gate has been downgraded from `enforced` to `advisory` —
     the quieter half of the same failure.

Either is allowed, but only DELIBERATELY: put a line in `gates.yaml` reading

    # GATE-REMOVED: <id> — <reason>
    # GATE-DOWNGRADED: <id> — <reason>

so the intent lives next to the manifest and shows up in the diff a human reads. A removal that
someone wrote down is a decision; a removal nobody wrote down is this incident.

Usage:  check-gates-not-deregistered.py [--enforce] [--self-test] [--base <ref>]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
MANIFEST_REL = ".github/gates/gates.yaml"
DEFAULT_BASE = "origin/main"

REMOVED_RE = re.compile(r"^\s*#\s*GATE-REMOVED:\s*([A-Za-z0-9._-]+)\s*[—:-]", re.MULTILINE)
DOWNGRADED_RE = re.compile(r"^\s*#\s*GATE-DOWNGRADED:\s*([A-Za-z0-9._-]+)\s*[—:-]", re.MULTILINE)


def parse(text: str) -> dict[str, str]:
    """gate id -> mode."""
    doc = yaml.safe_load(text)
    gates = doc["gates"] if isinstance(doc, dict) and "gates" in doc else doc
    return {g["id"]: g.get("mode", "enforced") for g in gates if isinstance(g, dict) and "id" in g}


def base_manifest(base: str) -> str | None:
    """The manifest as of the merge-base, or None when the base ref is unavailable.

    Uses the MERGE-BASE, not the base tip: on a branch cut before a gate was added, comparing
    against the tip would demand entries this branch never had a chance to see, which is a false
    positive on every stale branch. The merge-base asks the only honest question — did THIS branch
    drop something it started with?
    """
    for ref in (base, base.split("/")[-1]):
        try:
            mb = subprocess.run(["git", "merge-base", "HEAD", ref], cwd=REPO,
                                capture_output=True, text=True, check=True).stdout.strip()
            return subprocess.run(["git", "show", f"{mb}:{MANIFEST_REL}"], cwd=REPO,
                                  capture_output=True, text=True, check=True).stdout
        except subprocess.CalledProcessError:
            continue
    return None


def compare(before: str, after: str) -> list[str]:
    old, new = parse(before), parse(after)
    declared_removed = set(REMOVED_RE.findall(after))
    declared_downgraded = set(DOWNGRADED_RE.findall(after))
    out = []

    for gid in sorted(set(old) - set(new) - declared_removed):
        out.append(
            f"gate '{gid}' ({old[gid]}) is in the manifest at the merge-base and gone here, with no "
            f"'# GATE-REMOVED: {gid} — <reason>' line. Three enforced gates were lost exactly this "
            f"way on 2026-08-02 by a stale branch replaying its own copy of this file, with the PR "
            f"reporting MERGEABLE/CLEAN. If the removal is intended, say so in gates.yaml.")

    for gid in sorted(set(old) & set(new)):
        if old[gid] == "enforced" and new[gid] != "enforced" and gid not in declared_downgraded:
            out.append(
                f"gate '{gid}' was enforced at the merge-base and is now '{new[gid]}', with no "
                f"'# GATE-DOWNGRADED: {gid} — <reason>' line. A gate that stops blocking is a "
                f"policy decision; make it a visible one.")

    # A declaration that is wrong in the other direction is also a finding — otherwise the markers
    # rot into permanent noise, the failure mode of every hand-kept exception list in this repo.
    for gid in sorted(declared_removed & set(new)):
        out.append(f"'# GATE-REMOVED: {gid}' is declared but the gate is still in the manifest — "
                   f"drop the stale declaration.")
    for gid in sorted(declared_downgraded):
        if gid in new and new[gid] == "enforced":
            out.append(f"'# GATE-DOWNGRADED: {gid}' is declared but the gate is enforced — "
                       f"drop the stale declaration.")
    return out


def selftest() -> int:
    def man(entries: list[tuple[str, str]], extra: str = "") -> str:
        body = "gates:\n" + "".join(
            f"  - id: {i}\n    name: \"{i}\"\n    group: lint\n    mode: {m}\n    run: |\n      true\n"
            for i, m in entries)
        return body + extra

    a = man([("one", "enforced"), ("two", "enforced"), ("three", "advisory")])
    cases = [
        ("nothing changed", a, 0),
        ("a gate removed silently", man([("one", "enforced"), ("three", "advisory")]), 1),
        ("a gate removed with a declaration",
         man([("one", "enforced"), ("three", "advisory")], "\n# GATE-REMOVED: two — superseded\n"), 0),
        ("enforced downgraded to advisory silently",
         man([("one", "enforced"), ("two", "advisory"), ("three", "advisory")]), 1),
        ("enforced downgraded with a declaration",
         man([("one", "enforced"), ("two", "advisory"), ("three", "advisory")],
             "\n# GATE-DOWNGRADED: two — blocked on #123\n"), 0),
        ("a gate ADDED is fine", man([("one", "enforced"), ("two", "enforced"),
                                      ("three", "advisory"), ("four", "enforced")]), 0),
        ("advisory -> enforced is an upgrade, not a finding",
         man([("one", "enforced"), ("two", "enforced"), ("three", "enforced")]), 0),
        ("a stale GATE-REMOVED declaration is itself a finding",
         man([("one", "enforced"), ("two", "enforced"), ("three", "advisory")],
             "\n# GATE-REMOVED: two — superseded\n"), 1),
        ("three removed at once — the real incident",
         man([("one", "enforced")]), 2),
    ]
    for label, after, want in cases:
        got = compare(a, after)
        if len(got) != want:
            print(f"selftest FAIL: {label} — expected {want}, got {len(got)}: {got}")
            return 1

    # The gate must not pass when it cannot read a base: silence would then mean "no base", which
    # looks exactly like "nothing was removed".
    if base_manifest("refs/heads/definitely-not-a-real-ref-xyz") is not None:
        print("selftest FAIL: a bogus base ref resolved to something.")
        return 1

    print(f"selftest OK: {len(cases)} manifest transitions — silent removal and downgrade flagged, "
          f"declared ones allowed, additions and upgrades ignored, stale declarations flagged.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    ap.add_argument("--base", default=DEFAULT_BASE)
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    after = (REPO / MANIFEST_REL).read_text(encoding="utf-8")
    before = base_manifest(args.base)
    if before is None:
        # Not a finding: a shallow clone or a detached checkout has no base to compare with, and
        # inventing one would fail every such run. Said out loud so the silence is not read as a
        # pass, which is the distinction this whole file is about.
        print(f"check-gates-not-deregistered: no merge-base with {args.base} available — "
              f"NOT VERIFIED (this run proves nothing about removals).")
        return 0

    found = compare(before, after)
    for line in found:
        print(("::error::" if args.enforce else "::warning::") + line)
    print(f"check-gates-not-deregistered: {len(parse(before))} gate(s) at the merge-base, "
          f"{len(parse(after))} here — {'clean.' if not found else f'{len(found)} finding(s) above.'}")
    return 1 if found and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
