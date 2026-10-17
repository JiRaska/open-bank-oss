#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Coverage floors only go up — a PR that lowers a Kover `minValue` must say why (ADR-0020).

WHY THIS EXISTS
---------------
Every module's Kover floor is a static `minValue = N` in its `build.gradle.kts`, enforced by
`check -> koverVerify`. That proves the module clears ITS floor; nothing proved the floor itself
had not moved. "Ratchet-only" lived in comments and in `rules.yaml: floors`, and prose is not a
control: fx-service went 65 -> 60 in #12773 and every gate stayed green, because a lowered floor
is just a different number the build then clears more easily.

THE RULE
--------
For every `build.gradle.kts` tracked at the PR's merge base, each Kover `minValue` at HEAD must be
greater than or equal to the value at the base, position by position. A floor that disappears
counts as lowered to zero. A NEW floor (absent at base) is always allowed — it is the direction the
ratchet wants.

The one escape is explicit and auditable: a `coverage-floor-lowered:` trailer, in the PR body or in
any commit message of the PR range, carrying an issue reference (`#1234` or a GitHub issue URL).
The trailer is the human saying "re-baseline, and here is the issue that owns the debt"; without an
issue it is a comment, not a decision, and the gate rejects it. The squash merge carries the body
into the commit, so the reason survives on `main`.

WHAT IT DOES NOT DO
-------------------
It does not know whether the NEW value is honest — a floor raised to a number the build cannot
reach fails koverVerify on its own. It does not parse Kotlin: block comments are stripped and
`minValue = <int>` is matched as a bare integer literal, which is how every floor in the fleet
is written (including the one-line `bound { minValue = 60 }` form). Any OTHER `minValue =`
assignment (a variable, an expression) is refused outright rather than guessed at, so the regex
cannot be talked past. Floors within one file are compared as a sorted multiset (at least as
many, and HEAD's k-th smallest >= the base's k-th smallest), so reordering bounds cannot hide a
lowering. Renames are not followed: a moved build script shows up as a deletion of the old path,
which counts as every floor in it removed.

Usage:  check-coverage-floor-ratchet.py --base <merge-base> [--body <pr body>]
        check-coverage-floor-ratchet.py --self-test
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
# A bare integer literal, terminated by whitespace, a closing brace, a line comment or the line
# end — the one-line `bound { minValue = 60 }` form counts, `minValue = 60 * factor` does not.
FLOOR_RE = re.compile(r"\bminValue\s*=\s*(\d+)(?=\s*(?:\}|//|$))", re.MULTILINE)
ANY_MINVALUE_RE = re.compile(r"^.*\bminValue\s*=.*$", re.MULTILINE)
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
TRAILER = "coverage-floor-lowered:"
ISSUE_RE = re.compile(r"(?:#\d+|https://github\.com/[\w.-]+/[\w.-]+/issues/\d+)")


class UnparseableFloor(ValueError):
    """A `minValue =` the strict shape does not recognise — refused, never guessed."""


def floors(text: str | None) -> list[int] | None:
    """Every Kover `minValue` in a build script, sorted; None when the file is absent.

    Block and line comments are stripped first so a commented-out literal is not a floor. Any remaining `minValue =` that is not a bare integer literal raises, which the
    caller turns into a failure.
    """
    if text is None:
        return None
    code = LINE_COMMENT_RE.sub("", BLOCK_COMMENT_RE.sub("", text))
    for line in ANY_MINVALUE_RE.findall(code):
        if not FLOOR_RE.search(line):
            raise UnparseableFloor(line.strip())
    return sorted(int(m.group(1)) for m in FLOOR_RE.finditer(code))


def lowering_justified(trailer_text: str) -> bool:
    """True iff a `coverage-floor-lowered:` trailer names an issue."""
    for line in trailer_text.splitlines():
        stripped = line.strip()
        if stripped.lower().startswith(TRAILER) and ISSUE_RE.search(stripped[len(TRAILER):]):
            return True
    return False


def evaluate(base: dict[str, list[int] | None], head: dict[str, list[int] | None],
             trailer_text: str) -> list[str]:
    """Pure decision over {path: floors} maps, so the self-test drives the real rule."""
    lowered: list[str] = []
    for path in sorted(set(base) | set(head)):
        before, after = base.get(path), head.get(path)
        if not before:
            continue  # file or floor absent at base: adding a floor is always allowed
        after = sorted(after or [])
        before = sorted(before)
        # Sorted-multiset domination: at least as many floors, and the k-th smallest at HEAD must
        # cover the k-th smallest at base, so swapping two bounds cannot disguise a lowering as a
        # reorder. One finding per file, naming both multisets.
        if len(after) < len(before):
            lowered.append(f"{path}: Kover floor(s) removed — base {before}, head {after} "
                           f"(a missing floor is a floor of 0)")
        elif any(new < old for old, new in zip(before, after, strict=False)):
            lowered.append(f"{path}: Kover floor lowered — base {before}, head {after}")
    if not lowered:
        return []
    if lowering_justified(trailer_text):
        return []
    return [
        f"::error file={finding.split(':', 1)[0]}::{finding}. Coverage floors are ratchet-only "
        f"(ADR-0020, rules.yaml: floors). To re-baseline deliberately, add a trailer "
        f"`{TRAILER} #<issue>` to the PR body (or a commit message) naming the issue that owns the debt."
        for finding in lowered
    ]


def tracked_build_scripts(ref: str) -> list[str]:
    out = subprocess.run(["git", "-C", str(REPO), "ls-tree", "-r", "--name-only", ref],
                         capture_output=True, text=True, check=True).stdout
    return [p for p in out.splitlines() if p.endswith("build.gradle.kts") and "/build/" not in p]


def git_show(ref: str, path: str) -> str | None:
    proc = subprocess.run(["git", "-C", str(REPO), "show", f"{ref}:{path}"],
                          capture_output=True, text=True, check=False)
    return proc.stdout if proc.returncode == 0 else None


def pr_trailer_text(base: str, body: str | None) -> str:
    parts = [body or os.environ.get("PR_BODY", "")]
    log = subprocess.run(["git", "-C", str(REPO), "log", "--format=%B", f"{base}..HEAD"],
                         capture_output=True, text=True, check=False)
    parts.append(log.stdout if log.returncode == 0 else "")
    return "\n".join(parts)


def changed_build_scripts(base: str) -> list[str]:
    """Build scripts that differ between the base and HEAD, deleted ones included."""
    # --no-renames: a moved build script must appear as a deletion of the old path (every floor
    # in it removed), not vanish behind rename detection.
    out = subprocess.run(["git", "-C", str(REPO), "diff", "--no-renames", "--name-only", f"{base}...HEAD",
                          "--", "*build.gradle.kts"], capture_output=True, text=True, check=True).stdout
    return [p for p in out.splitlines() if "/build/" not in p]


def main_check(base_arg: str, body: str | None) -> int:
    base = (base_arg or os.environ.get("PR_DIFF_BASE", "")).strip()
    if not base:
        print("::error::PR_DIFF_BASE is empty but this gate requires it — refusing to run vacuously")
        return 1
    # Only a changed build script can lower a floor, so only those are read at both ends; the
    # subject count is the whole fleet, read once from the working tree.
    changed = changed_build_scripts(base)
    base_floors = {p: floors(git_show(base, p)) for p in changed}
    head_floors: dict[str, list[int] | None] = {}
    errors: list[str] = []
    for p in changed:
        try:
            head_floors[p] = floors(git_show("HEAD", p))
        except UnparseableFloor as exc:
            head_floors[p] = []
            errors.append(f"::error file={p}::{p}: `{exc}` is not a bare integer floor. Write "
                          f"`minValue = <int>` so the ratchet can read it; an expression here is refused.")
    errors += evaluate(base_floors, head_floors, pr_trailer_text(base, body))
    subjects = sum(1 for p in tracked_build_scripts("HEAD") if floors((REPO / p).read_text(errors="ignore")))
    print(f"SUBJECTS={subjects}  # build scripts carrying a Kover floor at HEAD")
    for line in errors:
        print(line)
    if errors:
        print(f"\n{len(errors)} coverage floor(s) lowered without a `{TRAILER}` trailer")
        return 1
    print("coverage floors: none lowered")
    return 0


def self_test() -> int:
    ok = True

    def case(label: str, base, head, trailer: str, expected: int) -> None:
        nonlocal ok
        got = len(evaluate(base, head, trailer))
        if got != expected:
            ok = False
        print(f"  [{'ok ' if got == expected else 'FAIL'}] {label}: found={got} expected={expected}")

    script = "openbank-x-service/build.gradle.kts"
    case("lowered floor, no trailer — MUST flag", {script: [65]}, {script: [60]}, "", 1)
    case("raised floor — must not flag", {script: [65]}, {script: [67]}, "", 0)
    case("unchanged floor — must not flag", {script: [65]}, {script: [65]}, "", 0)
    case("floor removed — MUST flag", {script: [65]}, {script: []}, "", 1)
    case("build script deleted — MUST flag (module retired without saying so)",
         {script: [65]}, {script: None}, "", 1)
    case("new floor on a module that had none — must not flag", {script: []}, {script: [40]}, "", 0)
    case("new build script — must not flag", {script: None}, {script: [40]}, "", 0)
    case("lowered with trailer + issue — must not flag",
         {script: [65]}, {script: [60]}, "chore: re-baseline\n\ncoverage-floor-lowered: #12773 fx re-baseline", 0)
    case("lowered with trailer + issue URL — must not flag",
         {script: [65]}, {script: [60]},
         "Coverage-Floor-Lowered: https://github.com/JiRaska/open-bank-oss/issues/12773", 0)
    case("lowered with trailer but NO issue — MUST flag",
         {script: [65]}, {script: [60]}, "coverage-floor-lowered: because reasons", 1)
    case("trailer mentioned mid-sentence, not as a trailer line — MUST flag",
         {script: [65]}, {script: [60]}, "see coverage-floor-lowered: #1 above", 1)
    case("two modules, one lowered — MUST flag exactly one",
         {script: [65], "b/build.gradle.kts": [50]}, {script: [65], "b/build.gradle.kts": [49]}, "", 1)
    case("second bound of two lowered — MUST flag",
         {script: [34, 38]}, {script: [34, 30]}, "", 1)
    case("two bounds swapped with one lowered — MUST flag (a positional compare would miss it)",
         {script: [34, 38]}, {script: [38, 33]}, "", 1)
    case("two bounds reordered, values intact — must not flag",
         {script: [34, 38]}, {script: [38, 34]}, "", 0)
    case("one of two bounds removed — MUST flag", {script: [34, 38]}, {script: [38]}, "", 1)

    # The parser against the shape every fleet floor is written in.
    sample = """kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020).
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
pitest { mutationThreshold = 0 }
"""
    parsed = floors(sample)
    parser_ok = parsed == [60]
    ok = ok and parser_ok
    print(f"  [{'ok ' if parser_ok else 'FAIL'}] parser reads the fleet floor shape and ignores pitest: {parsed}")
    inline = floors("    minValue = 95 // 97.30 % measured, minus 2\n")
    inline_ok = inline == [95]
    ok = ok and inline_ok
    print(f"  [{'ok ' if inline_ok else 'FAIL'}] parser accepts a trailing comment: {inline}")
    one_liner = floors("kover { reports { verify { rule { bound { minValue = 60 } } } } }\n")
    one_ok = one_liner == [60]
    ok = ok and one_ok
    print(f"  [{'ok ' if one_ok else 'FAIL'}] parser reads the one-line bound form: {one_liner}")
    commented = floors("/* minValue = 99 */\n// minValue = 98\n    minValue = 40\n")
    commented_ok = commented == [40]
    ok = ok and commented_ok
    print(f"  [{'ok ' if commented_ok else 'FAIL'}] parser ignores block and line comments: {commented}")
    refused = 0
    for expression in ("    minValue = floorFor(project)\n", "    minValue = 60 * factor\n"):
        try:
            floors(expression)
        except UnparseableFloor:
            refused += 1
    expr_ok = refused == 2
    ok = ok and expr_ok
    print(f"  [{'ok ' if expr_ok else 'FAIL'}] a non-literal minValue is refused, not guessed ({refused}/2)")

    # Known-positive against the REAL tree: lowering a real floor in memory must be caught.
    real = sorted(p for p in tracked_build_scripts("HEAD") if floors((REPO / p).read_text(errors="ignore")))
    live_ok = len(real) >= 50
    ok = ok and live_ok
    print(f"  [{'ok ' if live_ok else 'FAIL'}] fleet carries >= 50 Kover floors (found {len(real)})")
    if real:
        head = {p: floors((REPO / p).read_text(errors="ignore")) for p in real}
        victim = real[0]
        lowered = dict(head)
        lowered[victim] = [head[victim][0] - 1] + head[victim][1:]
        caught = len(evaluate(head, lowered, "")) == 1
        ok = ok and caught
        print(f"  [{'ok ' if caught else 'FAIL'}] lowering {victim} by one point in memory is caught")
        clean = len(evaluate(head, head, "")) == 0
        ok = ok and clean
        print(f"  [{'ok ' if clean else 'FAIL'}] the unchanged fleet passes")

    print("self-test:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="", help="merge-base ref (the runner passes $PR_DIFF_BASE)")
    ap.add_argument("--body", default=None, help="PR body inline (defaults to $PR_BODY)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return main_check(args.base, args.body)


if __name__ == "__main__":
    sys.exit(main())
