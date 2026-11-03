#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every test script under `.github/scripts` must be reachable from a gate or a workflow.

WHY THIS EXISTS
---------------
`.github/scripts/test-check-deploy-source-freshness.py` was committed with its checker and run by
nothing: no workflow named it, `gates.yaml` had no entry for it, and `governance-script-unit-tests`
discovers only `*_test.py` (top level) and `tests/test_*.py` — a hyphenated `test-*.py` matches
neither. It rotted unseen with 3 failures on `main` (#14697). Measured 2026-11-03 on `origin/main`:
**21 of 132** test files under `.github/scripts` were referenced by no gate, no workflow and no
discovery pattern; 1 was failing, 4 could not even be run without PyYAML, and nothing anywhere
said a test was not being consulted. `check-gate-script-registration.py` answers the same
question for `check-*` scripts only, by design ("is anything wired to this script at all").

THE RULE
--------
A test file is any tracked file under `.github/scripts` whose name matches one of
`test-*.{py,sh,mjs,cjs}`, `test_*.py`, `*_test.py`, `*.test.{py,sh,mjs,cjs}`. It is REACHABLE when:

  1. its basename appears in a `run:` or `selftest:` value (block scalar or one-liner) of
     `.github/gates/gates.yaml` or any `.github/workflows/*.yml` — a mention in a `rationale:`, a
     `name:`, a `paths:` filter, a `selftest_inputs:` list or a comment is not an invocation; or
  2. a `python3 -m unittest discover -s <dir> -p '<pattern>'` invocation in those files selects it
     (`-s` is the directory the file sits in directly — `discover` only recurses into packages,
     and these directories have no `__init__.py`; the pattern is fnmatch-ed against the basename); or
  3. its basename appears (outside comments) in a NON-test script under `.github/scripts` that is
     itself named in (1) — one hop, which is how `merge-train.py --self-test` runs
     `test-merge-train-main-red-freshness.py`.

Anything else is an orphan and fails the gate, unless listed in KNOWN_UNREACHABLE with a reason.
That list is held in both directions: an entry that becomes reachable fails too, so a debt cannot
quietly become permanent (same shape as `check-pact-provider-replay.py`'s KNOWN_UNCOVERED).

FAIL DIRECTION
--------------
Closed: a reference the scanner cannot see (a name built at runtime, a glob it does not derive)
reads as an orphan and the fix is to name the file in the manifest, which costs one line. The
opposite error — a test that is run by nothing reading as covered — is the defect this exists for.

Usage:
  check-test-script-reachability.py --root .          # gate
  check-test-script-reachability.py --self-test       # falsify every rule against a synthetic tree
"""
from __future__ import annotations

import argparse
import fnmatch
import pathlib
import re
import subprocess
import sys
import tempfile

SCRIPTS = ".github/scripts"
MANIFEST = ".github/gates/gates.yaml"
WORKFLOWS = ".github/workflows"
TEST_GLOBS = ("test-*.py", "test-*.sh", "test-*.mjs", "test-*.cjs", "test_*.py", "*_test.py",
              "*.test.py", "*.test.sh", "*.test.mjs", "*.test.cjs")
DISCOVER_RE = re.compile(r"unittest\s+discover\s+(?:.*?\s)?-s\s+['\"]?([^\s'\"]+)['\"]?(?:.*?\s)?-p\s+['\"]?([^\s'\"]+)['\"]?")

# name -> reason. Entries are checked in BOTH directions (see header).
KNOWN_UNREACHABLE: dict[str, str] = {
    # #14697 fixes its 3 failures on main and names it in gates.yaml; this entry becomes stale (and
    # this gate fails) the moment that lands — delete the line then.
    "test-check-deploy-source-freshness.py": "wired and fixed by #14697; failing on main until it merges",
}


def strip_comments(text: str) -> str:
    """Blank `#` comments outside quotes, line by line (mirrors check-gate-script-registration.py)."""
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


def read(path: pathlib.Path) -> str:
    try:
        return strip_comments(path.read_text(errors="replace"))
    except OSError:
        return ""


def tracked(root: pathlib.Path, under: str) -> list[pathlib.Path]:
    r = subprocess.run(["git", "-C", str(root), "ls-files", "--", under], text=True, capture_output=True, check=False)
    if r.returncode != 0:   # not a repository (self-test tree): walk instead
        return sorted(p for p in (root / under).rglob("*") if p.is_file())
    return [root / f for f in r.stdout.split("\n") if f.strip()]


def is_test(path: pathlib.Path) -> bool:
    return any(fnmatch.fnmatch(path.name, g) for g in TEST_GLOBS)


BLOCK_RE = re.compile(r"^(?P<indent>[ \t]*)(?:-[ \t]+)?(?:run|selftest):[ \t]*[|>][-+]?[ \t]*\n(?P<body>(?:(?P=indent)[ \t]+.*\n|[ \t]*\n)*)", re.MULTILINE)
PLAIN_RE = re.compile(r"^[ \t]*(?:-[ \t]+)?(?:run|selftest):[ \t]*(?P<cmd>[^|>\s].*)$", re.MULTILINE)


def command_blocks(text: str) -> str:
    """Only the `run:` / `selftest:` values of a manifest or workflow — block scalars and one-liners.

    A test NAMED in a `rationale:`, a `name:`, a `selftest_inputs:` list or a comment is not run by
    it. Measured on the first draft of this gate: its own rationale mentioned
    test-check-deploy-source-freshness.py and that read as reachable. Counting the executable
    values only is what makes a mention and an invocation different things. Nine real invocations
    are one-line scalars (`run: python3 .github/scripts/test-x.py`), so both shapes count."""
    blocks = [m.group("body") for m in BLOCK_RE.finditer(text + "\n")]
    blocks += [m.group("cmd") for m in PLAIN_RE.finditer(text)]
    return "\n".join(blocks)


def entry_corpus(root: pathlib.Path) -> str:
    files = [root / MANIFEST] + sorted((root / WORKFLOWS).glob("*.yml")) + sorted((root / WORKFLOWS).glob("*.yaml"))
    return "\n".join(command_blocks(read(f)) for f in files)


def discover_selectors(corpus: str) -> list[tuple[str, str]]:
    return [(d.rstrip("/"), p) for d, p in DISCOVER_RE.findall(corpus)]


def classify(root: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    """({test rel path: how reached}, [orphan rel paths])."""
    corpus = entry_corpus(root)
    selectors = discover_selectors(corpus)
    scripts = tracked(root, SCRIPTS)
    tests = [p for p in scripts if is_test(p)]
    # One hop: non-test scripts the entry corpus names.
    hop_corpus = "\n".join(read(p) for p in scripts if not is_test(p) and p.name in corpus)
    reached: dict[str, str] = {}
    orphans: list[str] = []
    for t in tests:
        rel = t.relative_to(root).as_posix()
        reldir = t.parent.relative_to(root).as_posix()
        if t.name in corpus:
            reached[rel] = "named in gates.yaml or a workflow"
        elif any(reldir == d and fnmatch.fnmatch(t.name, pat) for d, pat in selectors):
            reached[rel] = "selected by unittest discover"
        elif t.name in hop_corpus:
            reached[rel] = "named in a script that gates.yaml or a workflow names"
        else:
            orphans.append(rel)
    return reached, orphans


def run(root: pathlib.Path, known: dict[str, str]) -> int:
    reached, orphans = classify(root)
    print(f"SUBJECTS={len(reached) + len(orphans)}")
    rc = 0
    for rel in orphans:
        name = pathlib.Path(rel).name
        if name in known:
            print(f"known-unreachable {rel}: {known[name]}")
            continue
        print(f"::error file={rel}::test script is run by nothing — no gate, workflow or unittest-discover "
              f"pattern names it. Add it to a gates.yaml entry (or declare it in KNOWN_UNREACHABLE with a reason).")
        rc = 1
    for name, why in known.items():
        rel = next((r for r in reached if pathlib.Path(r).name == name), None)
        if rel is not None:
            print(f"::error file={rel}::declared KNOWN_UNREACHABLE ({why}) but it is now {reached[rel]} — remove the stale entry.")
            rc = 1
        elif not any(pathlib.Path(r).name == name for r in orphans):
            print(f"::error::KNOWN_UNREACHABLE names {name}, which does not exist — remove the stale entry.")
            rc = 1
    print(f"{len(reached)} reachable, {len(orphans)} orphan(s)")
    return rc


# ---------------------------------------------------------------------------------------------
def self_test() -> int:
    fails: list[str] = []

    def check(label: str, cond: bool, detail: str = "") -> None:
        if not cond:
            fails.append(f"{label}: {detail}")

    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)

        def w(rel: str, text: str = "") -> None:
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)

        w(MANIFEST, "gates:\n  - id: x\n    rationale: \"test-in-rationale.py rotted\"\n    name: \"runs test-in-name.py\"\n"
                    "    run: |\n      python3 .github/scripts/test-named.py\n"
                    "      python3 -m unittest discover -s .github/scripts -p '*_test.py' -v\n"
                    "      python3 -m unittest discover -s .github/scripts/tests -p 'test_*.py' -v\n"
                    "      python3 .github/scripts/hub.py --self-test\n"
                    "      # python3 .github/scripts/test-only-in-a-comment.py\n")
        w(f"{WORKFLOWS}/x.yml", "jobs:\n  j:\n    steps:\n      - run: bash .github/scripts/test-in-workflow.sh\n"
                                "      - run: |\n          python3 .github/scripts/test-in-workflow-block.py\n"
                                "    paths:\n      - '.github/scripts/test-in-paths-filter.py'\n")
        w(f"{SCRIPTS}/test-in-workflow-block.py"); w(f"{SCRIPTS}/test-in-paths-filter.py")
        w(f"{SCRIPTS}/test-named.py"); w(f"{SCRIPTS}/test-in-workflow.sh")
        w(f"{SCRIPTS}/foo_test.py"); w(f"{SCRIPTS}/tests/test_bar.py")
        w(f"{SCRIPTS}/hub.py", "subprocess.run(['python3', '.github/scripts/test-via-hub.py'])\n")
        w(f"{SCRIPTS}/test-via-hub.py")
        w(f"{SCRIPTS}/unwired.py", "subprocess.run(['python3', '.github/scripts/test-via-unwired.py'])\n")
        w(f"{SCRIPTS}/test-via-unwired.py")
        w(f"{SCRIPTS}/test-only-in-a-comment.py")
        w(f"{SCRIPTS}/test-in-rationale.py"); w(f"{SCRIPTS}/test-in-name.py")   # mentioned, never run
        w(f"{SCRIPTS}/test_top_level.py")        # discover -s .github/scripts -p '*_test.py' does NOT select it
        w(f"{SCRIPTS}/tests/nested_test.py")     # discover -s .github/scripts does not recurse (no __init__.py)
        w(f"{SCRIPTS}/tests/test-hyphen.sh")     # test_*.py pattern does not select a .sh
        w(f"{SCRIPTS}/check-something.py")       # not a test file: never a subject

        reached, orphans = classify(root)
        for name, how in (("test-named.py", "named in gates.yaml or a workflow"),
                          ("test-in-workflow.sh", "named in gates.yaml or a workflow"),
                          ("test-in-workflow-block.py", "named in gates.yaml or a workflow"),
                          ("foo_test.py", "selected by unittest discover"),
                          ("test_bar.py", "selected by unittest discover"),
                          ("test-via-hub.py", "named in a script that gates.yaml or a workflow names")):
            rel = next((r for r in reached if r.endswith("/" + name)), None)
            check(f"{name} reachable via {how}", rel is not None and reached[rel] == how, str(reached))
        for name in ("test-via-unwired.py", "test-only-in-a-comment.py", "test_top_level.py", "nested_test.py", "test-hyphen.sh",
                     "test-in-rationale.py", "test-in-name.py", "test-in-paths-filter.py"):
            check(f"{name} is an orphan", any(o.endswith("/" + name) for o in orphans), str(orphans))
        check("a check-* script is not a subject", not any("check-something" in x for x in list(reached) + orphans))
        check("subject count", len(reached) + len(orphans) == 14, f"{len(reached)}+{len(orphans)}")

        check("orphans fail the gate", run(root, {}) == 1)
        known = {n: "declared for the test" for n in ("test-via-unwired.py", "test-only-in-a-comment.py",
                                                     "test_top_level.py", "nested_test.py", "test-hyphen.sh",
                                                     "test-in-rationale.py", "test-in-name.py", "test-in-paths-filter.py")}
        check("every orphan declared -> pass", run(root, known) == 0)
        check("a stale declaration (file now reachable) fails", run(root, {**known, "test-named.py": "stale"}) == 1)
        check("a declaration naming a missing file fails", run(root, {**known, "test-gone.py": "gone"}) == 1)
        # The gate must be able to go red on the real defect shape: hyphenated test beside its checker.
        w(f"{SCRIPTS}/test-check-new-thing.py")
        check("a new hyphenated test-*.py beside a discover pattern is still an orphan", run(root, known) == 1)

    for f in fails:
        print(f"FAIL {f}")
    print(f"self-test: {len(fails)} failure(s)")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return run(pathlib.Path(a.root).resolve(), KNOWN_UNREACHABLE)


if __name__ == "__main__":
    sys.exit(main())
