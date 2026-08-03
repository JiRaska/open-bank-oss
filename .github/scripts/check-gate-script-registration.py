#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every `check-*` script must actually be RUN by something. Nothing may be written and forgotten.

WHY THIS EXISTS
---------------
`check-compliance-matrix.py` was written for #2370 — 141 lines, with a docstring naming the exact
regulatory drift it prevents — and was then referenced from nowhere: no workflow, no `gates.yaml`
entry, and nothing in this repo iterates `.github/scripts/check-*` (`run-gates.py` reads the
manifest, not the directory). It had never run once. In the same sweep, `rules.yaml`'s
`shared_m2m_write_prohibition` named `check-operator-write-naming.py` as its own `ci_producer`
while nothing invoked it, so reading `rules.yaml` gave every impression the rule was enforced
(#3240, both fixed by registering them).

That failure is worse than an unfalsified gate, because there is no signal at all: a gate that has
never run cannot go red, cannot appear in a run list, and its mere existence in the tree reads as
coverage to anyone grepping for it. The repo's own lesson — *the gate that never existed beats the
unfalsified one* (#2280) — with the twist that here the gate WAS written.

WHAT IT CHECKS
--------------
For every `.github/scripts/check-*` file, exactly one of:

  1. it appears in a `gates.yaml` entry's `run:` or `selftest:` — the normal case; or
  2. something INVOKES it from a tracked file — a workflow `run:` block, a gitops CronJob's
     `args:`, another script; or
  3. it is listed in HELPERS below, with a reason.

WHY "INVOKES" AND NOT "IS MENTIONED"
------------------------------------
A mention is not an invocation, and this repo has been bitten by the difference in BOTH
directions. Measured while writing this: `check-dockerfile-no-build-stage.py` is named in 40-odd
service `Dockerfile`s and `check-openapi-request-schema-conformance.py` in 38 OPA bundles — those
are comments and embedded `rules.yaml` text respectively, i.e. the string propagating, not the
check executing. A naive substring rule would report both as covered on that evidence alone, which
is the code-about-code collision running in its SILENT direction: a false negative here reads
exactly like a pass.

So a reference counts only in an invocation-shaped position (`python3 x.py`, `bash x.sh`, `./x.py`,
`sh x.sh`) on a line that is not a comment. Comment stripping is line-based and deliberately
conservative — `#` outside quotes for YAML/shell/Dockerfile/Python.

WHAT IT DELIBERATELY DOES NOT CHECK
-----------------------------------
That the invocation is REACHABLE. A script invoked from a workflow whose `if:` is always false, or
from a job excluded on the PR lane, passes this gate. That is a different and harder question — the
`@PactFolder`-vs-`@PactBroker` problem (#2327) is the same shape, and it needed its own gate. This
one answers only "is anything wired to it at all", which is the question #3240 answered wrongly.

Usage:  check-gate-script-registration.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO / ".github/scripts"
MANIFEST = REPO / ".github/gates/gates.yaml"

# Scripts that are legitimately not gates and not invoked as one. Each needs a reason: an entry
# here is a claim a human made, and the check fails on a STALE one (a helper that has since been
# registered, or one that no longer exists) so this list cannot quietly become permanent.
HELPERS: dict[str, str] = {}

# `python3 foo.py`, `bash foo.sh`, `sh foo.sh`, `./foo.py` — the shapes that actually run a file.
#
# A bare PATH is deliberately not one of them. The first draft accepted `.github/scripts/<name>`
# anywhere, and its own self-test caught that on `ci_producer: ".github/scripts/check-demo.py"` —
# stale-ref-ok: check-demo.py is a self-test fixture name, deliberately not a real file.
# a rules.yaml value naming a script, which is precisely the #3240 case where the name is written
# down and nothing runs it. Accepting the path would have made this gate green about the very
# defect it exists to find.
def invocation_re(name: str) -> re.Pattern[str]:
    # A COMMAND POSITION, not merely a path: after a runner, after `./`, after a workflow `run:`,
    # or after a shell operator. `agent-review.yml:464` runs an executable script by bare relative
    # path (`run: .github/scripts/check-claude-fallback-result.sh "$F"`), so `run:` has to count —
    # but a bare path anywhere is exactly the rules.yaml `ci_producer:` shape that #3240 was about.
    #
    # Line-start alone is deliberately NOT a command position, even though a shell script could
    # call another that way. It would match any prose line that happens to BEGIN with the script
    # name, and that error is silent — it marks an orphan as covered. Being too strict instead
    # produces a false orphan report, which is loud and costs one HELPERS entry to resolve. When
    # the two failure directions are not symmetric, take the loud one.
    return re.compile(rf"(?:(?:python3?|bash|sh)\s+|\./|run:\s+|&&\s*|\|\|\s*|[;|]\s*)"
                      rf"\S*{re.escape(name)}")


def strip_comments(text: str) -> str:
    """Blank out `#` comments, line-based and conservative.

    Only strips when the `#` is outside quotes on that line. A `#` inside a string (a colour code,
    a URL fragment, a regex) must not truncate the line, or a real invocation after it disappears
    and the script is reported as an orphan it is not.
    """
    out = []
    for line in text.splitlines():
        in_s = in_d = False
        cut = None
        for i, ch in enumerate(line):
            if ch == "'" and not in_d:
                in_s = not in_s
            elif ch == '"' and not in_s:
                in_d = not in_d
            elif ch == "#" and not in_s and not in_d:
                cut = i
                break
        out.append(line if cut is None else line[:cut])
    return "\n".join(out)


def tracked_files() -> list[pathlib.Path]:
    proc = subprocess.run(["git", "ls-files"], cwd=REPO, capture_output=True, text=True, check=False)
    return [REPO / f for f in proc.stdout.split()]


def check_scripts() -> list[pathlib.Path]:
    return sorted(p for p in SCRIPTS_DIR.glob("check-*") if p.is_file())


def coverage(names: list[str] | None = None) -> tuple[dict[str, str], list[str]]:
    """Return ({script: how it is covered}, [uncovered script names]).

    `names` is a parameter so the self-test can run the REAL resolution logic against a name that
    cannot possibly be covered. Without that it could only assert "something was covered", which
    an unconditionally-covering bug satisfies — measured: breaking the manifest lookup so every
    script resolved left the self-test green.
    """
    names = names if names is not None else [p.name for p in check_scripts()]
    manifest_text = MANIFEST.read_text(encoding="utf-8") if MANIFEST.is_file() else ""

    covered: dict[str, str] = {}
    pending = []
    for name in names:
        if name in manifest_text:
            covered[name] = "gates.yaml"
        else:
            pending.append(name)

    if pending:
        pats = {n: invocation_re(n) for n in pending}
        for path in tracked_files():
            if not pending:
                break
            if path.parent == SCRIPTS_DIR or path == MANIFEST:
                continue
            try:
                text = strip_comments(path.read_text(encoding="utf-8", errors="ignore"))
            except OSError:
                continue
            for name in list(pending):
                if pats[name].search(text):
                    covered[name] = f"invoked by {path.relative_to(REPO)}"
                    pending.remove(name)

    for name in list(pending):
        if name in HELPERS:
            covered[name] = f"declared helper: {HELPERS[name]}"
            pending.remove(name)

    return covered, pending


def stale_helpers(covered: dict[str, str]) -> list[str]:
    """A HELPERS entry that is wrong in either direction is itself a finding."""
    names = {p.name for p in check_scripts()}
    stale = []
    for name, reason in HELPERS.items():
        if name not in names:
            stale.append(f"{name} is declared a helper but does not exist")
        elif not covered.get(name, "").startswith("declared helper"):
            stale.append(f"{name} is declared a helper but is now {covered.get(name)} — drop the entry")
    return stale


def selftest() -> int:
    """Feed both rules inputs they MUST flag and inputs they must NOT."""
    names = check_scripts()
    if len(names) < 40:
        print(f"selftest FAIL: only {len(names)} check-* script(s) found — the scan is broken.")
        return 1

    pat = invocation_re("check-demo.py")
    must_flag = [
        "python3 .github/scripts/check-demo.py --enforce",
        "bash check-demo.py",
        "  ./check-demo.py",
        "python3 scripts/check-demo.py",
        # An executable run by bare relative path from a workflow step — the real shape in
        # agent-review.yml, and the one the stricter first draft of this regex missed.
        "        run: .github/scripts/check-demo.py \"$EXECUTION_FILE\"",
        "make thing && python3 check-demo.py",
    ]
    must_not = [
        # The exact shapes measured in this repo: a comment in a Dockerfile and rules.yaml text
        # embedded verbatim into an OPA bundle. Both are the string propagating, not the check
        # running — and a false negative here is silent, which is why they are pinned.
        "ci_producer: \".github/scripts/check-demo.py\"",
        "see check-demo.py for the rationale",
        "the check-demo.py gate explains why",
        # A prose line BEGINNING with the name. Accepting line-start as a command position would
        # mark an orphan as covered on this, silently — see the note on invocation_re.
        "check-demo.py has never been wired to anything",
    ]
    for line in must_flag:
        if not pat.search(line):
            print(f"selftest FAIL: missed an invocation: {line!r}")
            return 1
    for line in must_not:
        if pat.search(line):
            print(f"selftest FAIL: treated a bare mention as an invocation: {line!r}")
            return 1

    # Comment stripping must not eat a real invocation that follows a `#` inside a string, and
    # must eat a commented-out one. Both directions, because only one of them is loud.
    cases = [
        ('  # python3 check-demo.py', False, "a commented-out invocation"),
        ('  echo "#5" && python3 check-demo.py', True, "an invocation after a # inside quotes"),
        ("  python3 check-demo.py  # why", True, "an invocation with a trailing comment"),
    ]
    for text, want, what in cases:
        got = bool(pat.search(strip_comments(text)))
        if got != want:
            print(f"selftest FAIL: {what} — expected {want}, got {got}")
            return 1

    # The whole gate must be able to report an orphan. Run the REAL resolution against a name
    # nothing can reference, rather than trusting the live tree to be clean — and against one that
    # is genuinely registered, so a rule that flags everything fails here too. Both directions:
    # an earlier version asserted only "something was covered", and stayed green when the manifest
    # lookup was broken to cover everything unconditionally.
    orphan_probe = "check-this-name-is-referenced-nowhere-selftest.py"
    real = next((p.name for p in names if p.name in MANIFEST.read_text(encoding="utf-8")), None)
    if real is None:
        print("selftest FAIL: no check-* script is in gates.yaml — the manifest read is broken.")
        return 1
    covered, orphans = coverage([orphan_probe, real])
    if orphan_probe not in orphans:
        print("selftest FAIL: a script referenced nowhere was NOT reported as an orphan — "
              "the gate would be green about the defect it exists to find.")
        return 1
    if covered.get(real) != "gates.yaml":
        print(f"selftest FAIL: {real} is in gates.yaml but resolved as {covered.get(real)!r} — "
              "the gate would report a registered script as never run.")
        return 1

    print(f"selftest OK: {len(must_flag)} invocation shapes flagged, {len(must_not)} bare mentions "
          f"not flagged, {len(cases)} comment cases both ways, and the resolution reports an "
          f"orphan while keeping a registered script covered.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    covered, orphans = coverage()
    messages = [
        f"::error file=.github/scripts/{name}::{name} is never run: it is not in gates.yaml, "
        f"nothing invokes it from a tracked file, and it is not a declared helper. A check that "
        f"has never executed cannot go red — its presence in the tree reads as coverage while "
        f"providing none (#3240). Register it in .github/gates/gates.yaml, wire it to whatever "
        f"should run it, or add it to HELPERS with a reason."
        for name in orphans
    ]
    messages += [f"::error::stale HELPERS entry — {s}" for s in stale_helpers(covered)]

    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))

    by_gates = sum(1 for v in covered.values() if v == "gates.yaml")
    print(f"check-gate-script-registration: {len(covered) + len(orphans)} check-* script(s) — "
          f"{by_gates} in gates.yaml, {len(covered) - by_gates} invoked elsewhere or declared, "
          f"{len(orphans)} never run.")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
