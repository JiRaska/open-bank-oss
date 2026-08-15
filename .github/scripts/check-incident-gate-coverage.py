#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""How many of CLAUDE.md's documented incidents produced a gate — as a number, not a claim.

WHY THIS EXISTS (ADR-0254)
--------------------------
The tracked `CLAUDE.md` holds dozens of paragraph-length write-ups — an operational footgun, an
issue number, usually the fix that now catches the defect class. Whether that fix is actually a
CI gate, versus the write-up being the only artifact, is established today by a human re-reading
the paragraph and grepping for the issue number by hand. #4339 mechanised exactly this kind of
claim for scripts (`check-gate-script-registration.py`: is anything wired to this check) and for
`ci.yml` jobs (`check-gate-invocation-reachability.py`); there is no structural reason the same
question should stop at the boundary of prose.

SCOPE, STATED SO IT CANNOT BE MISREAD
--------------------------------------
Only the TRACKED `CLAUDE.md`. `.claude/CLAUDE.md` and `.claude/rules/*.md` are gitignored and
therefore structurally absent from a CI checkout — this cannot measure them, and does not try
to. A private incident write-up (a break-glass procedure, an internal account specific) is by
the same design kept off this check's coverage, which is the correct trade for a public repo.

THE HEURISTIC, AND WHY IT IS ADVISORY FOREVER
------------------------------------------------
A "covered" incident is a top-level bullet (`- **Title.** ...`) under a `###` subsection whose
body cites at least one `#<n>` issue number that ALSO appears anywhere in `gates.yaml` or
`rules.yaml`. That is a real signal here, not the "matching a comment ABOUT the thing" trap this
repo has been burned by before (`check-gate-selftest-declaration.py`'s own header names it): the
artifact being searched — `gates.yaml`'s own comments — is WHERE this repo already puts the
issue-number citation that substantiates "this incident has a gate" (every #4339 PR cites its
issue this way). The heuristic can still be wrong in both directions: a shared issue number
across two unrelated incidents reads as coverage for both, and a gate that cites a DIFFERENT
issue number for the same underlying defect reads as no coverage at all. Neither is provable
without a human reading both texts, which is exactly why this reports a number and never fails
a PR — see ADR-0254's "Alternatives considered" for why an enforced version would be worse than
the gap it closes.

Usage:  check-incident-gate-coverage.py [--root .]
        check-incident-gate-coverage.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ISSUE_RE = re.compile(r"#(\d{2,6})\b")
# A top-level incident bullet: "- **<bold lead-in>" at zero indent. Nested bullets (two-space
# indent, used for sub-points inside one incident) are swept into the SAME incident, not
# counted as their own — only the outermost `- **` starts a new one.
BULLET_RE = re.compile(r"^- \*\*")
HEADING_RE = re.compile(r"^#{2,3} ")


def extract_incidents(text: str) -> list[tuple[str, set[str]]]:
    """[(title, {issue numbers cited anywhere in this bullet's body}), ...].

    A bullet's body runs from its `- **` line to the line before the next top-level bullet or
    the next heading — i.e. it DOES absorb nested `-`-indented continuation lines, which is
    where several real incidents put their issue numbers (a sub-bullet listing "root-caused in
    #X, generalised in #Y"). The bold TITLE itself is often multi-line too — "- **A Kotlin
    annotation binds to the NEXT declaration — a top-level function between..." closes its
    `**` two lines down — so the title is extracted from the whole joined body, not line one
    alone; matching only line one silently fell back to a 60-char slice of raw markdown
    (`- **A Kotlin annot...`) for every multi-line title, which is most of the long ones."""
    lines = text.split("\n")
    incidents: list[tuple[str, set[str]]] = []
    i = 0
    while i < len(lines):
        if BULLET_RE.match(lines[i]):
            body = [lines[i]]
            j = i + 1
            while j < len(lines) and not BULLET_RE.match(lines[j]) and not HEADING_RE.match(lines[j]):
                body.append(lines[j])
                j += 1
            joined = "\n".join(body)
            title_match = re.match(r"^- \*\*(.+?)\*\*", joined, flags=re.DOTALL)
            title = " ".join(title_match.group(1).split()) if title_match else joined[:60]
            issues = set(ISSUE_RE.findall(joined))
            incidents.append((title, issues))
            i = j
        else:
            i += 1
    return incidents


def cited_elsewhere(root: pathlib.Path) -> set[str]:
    """Every #<n> that appears anywhere in gates.yaml or rules.yaml."""
    out: set[str] = set()
    for rel in (".github/gates/gates.yaml", "openbank-libs/governance/rules.yaml"):
        p = root / rel
        if p.is_file():
            out |= set(ISSUE_RE.findall(p.read_text(encoding="utf-8", errors="ignore")))
    return out


def findings(root: pathlib.Path) -> tuple[list[tuple[str, set[str]]], list[tuple[str, set[str]]], int]:
    """Return (covered, uncovered, total_incidents_with_at_least_one_issue_number).

    A bullet citing NO issue number at all (a pure style/convention note, not an incident) is
    excluded from both lists and from the denominator — it never claimed to be an incident.
    """
    claude_md = root / "CLAUDE.md"
    if not claude_md.is_file():
        raise FileNotFoundError("CLAUDE.md not found at repo root")
    incidents = [(t, i) for t, i in extract_incidents(claude_md.read_text(encoding="utf-8")) if i]
    known = cited_elsewhere(root)
    covered = [(t, i) for t, i in incidents if i & known]
    uncovered = [(t, i) for t, i in incidents if not (i & known)]
    return covered, uncovered, len(incidents)


def self_test() -> int:
    import tempfile

    fails = []

    def write(root: pathlib.Path, claude_md: str, gates_yaml: str = "", rules_yaml: str = ""):
        (root / "CLAUDE.md").write_text(claude_md)
        (root / ".github" / "gates").mkdir(parents=True, exist_ok=True)
        (root / ".github" / "gates" / "gates.yaml").write_text(gates_yaml)
        (root / "openbank-libs" / "governance").mkdir(parents=True, exist_ok=True)
        (root / "openbank-libs" / "governance" / "rules.yaml").write_text(rules_yaml)

    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        write(root, "# CLAUDE.md\n\n### Section\n"
              "- **A covered incident.** Something broke (#1000). Fixed by a gate.\n"
              "- **An uncovered incident.** Something else broke (#2000), never gated.\n"
              "- **No issue number here.** A style note with no incident behind it.\n",
              gates_yaml="# gate comment citing #1000\n")
        covered, uncovered, total = findings(root)
        if [t for t, _ in covered] != ["A covered incident."]:
            fails.append(f"covered: want ['A covered incident.'], got {[t for t,_ in covered]}")
        if [t for t, _ in uncovered] != ["An uncovered incident."]:
            fails.append(f"uncovered: want ['An uncovered incident.'], got {[t for t,_ in uncovered]}")
        if total != 2:
            fails.append(f"total: want 2 (the style note must not count), got {total}")

    # Nested sub-bullets fold into the parent incident's issue set.
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        write(root, "# CLAUDE.md\n\n### Section\n"
              "- **An incident with the number on a sub-line.**\n"
              "  Root-caused in #3000, generalised in #3001.\n"
              "- **Next incident.** Unrelated (#4000).\n",
              gates_yaml="cites #3001 here\n")
        covered, uncovered, total = findings(root)
        if len(covered) != 1 or len(uncovered) != 1:
            fails.append(f"nested-line issue numbers not folded into the parent bullet: "
                          f"covered={covered}, uncovered={uncovered}")

    # A title whose bold span closes on a LATER line — the common shape in this repo's real
    # CLAUDE.md — must not fall back to a 60-char slice of raw markdown including the `- **`
    # prefix. This is the bug the first version of this script shipped with.
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        write(root, "# CLAUDE.md\n\n### Section\n"
              "- **A title that runs onto a second line before the bold span\n"
              "  closes here.** The body continues after that (#5000).\n",
              gates_yaml="#5000\n")
        covered, uncovered, total = findings(root)
        got_title = (covered + uncovered)[0][0] if (covered or uncovered) else None
        want_title = ("A title that runs onto a second line before the bold span closes "
                      "here.")
        if got_title != want_title:
            fails.append(f"multi-line title: want {want_title!r}, got {got_title!r}")

    # A missing CLAUDE.md must raise, never report a false "0 incidents, all covered".
    with tempfile.TemporaryDirectory() as d:
        try:
            findings(pathlib.Path(d))
            fails.append("a missing CLAUDE.md did not raise")
        except FileNotFoundError:
            pass

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: incident-gate-coverage is falsifiable (6 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import gatelib

    try:
        covered, uncovered, total = findings(pathlib.Path(args.root))
    except FileNotFoundError as exc:
        sys.stderr.write(f"::error::incident-gate-coverage: {exc}\n")
        return 1

    gatelib.subjects(total, "CLAUDE.md incidents citing an issue number")
    for title, issues in uncovered:
        cites = ", ".join(f"#{n}" for n in sorted(issues, key=int))
        print(f"::notice::incident-gate-coverage: {title!r} ({cites}) — no matching #<n> found "
              f"in gates.yaml or rules.yaml. May be a genuine gap, a process-only fix that "
              f"cannot be mechanised, or a gate citing a different issue number for the same "
              f"defect (heuristic limit — see this script's header).")
    pct = round(100 * len(covered) / total) if total else 0
    print(f"incident-gate-coverage: {total} documented incident(s) with an issue number, "
          f"{len(covered)} ({pct}%) cite a gate, {len(uncovered)} do not.")
    return 0  # advisory forever — see ADR-0254 "Alternatives considered"


if __name__ == "__main__":
    sys.exit(main())
