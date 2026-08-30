#!/usr/bin/env python3
"""Fail when a PR claims an `openapi.yaml` info.version that the base branch already shipped.

WHY THIS IS NOT COVERED BY `api-contract-gate`
----------------------------------------------
`check-api-contract.py` classifies the bump from the diff against the **merge-base**. That is
correct for asking "is this bump big enough for the change?", and structurally blind to the
collision this gate catches.

Concretely, four PRs were green on 2026-08-30 while claiming a version `main` had already
released:

    account 1.11.0, consent 1.7.0, card-issuance 1.8.0, campaign 1.43.0

Each forked when the spec was one minor lower, bumped by one, and a competing PR then merged
the same number first. The merge-base still holds the *old* value, so the contract gate sees a
clean `1.42.0 -> 1.43.0` minor bump and passes. Git sees nothing either: identical text is not a
conflict, so the merge is exit 0 and prints nothing. Two independent checks each answer a
question that is individually right and jointly insufficient.

WHAT THIS GATE ASKS INSTEAD
---------------------------
Not "is the bump correct relative to where you forked", but "**is this number still free on the
base branch as it is right now**". Those differ exactly when someone else landed first, which is
the whole failure mode.

HONEST LIMIT, STATED SO NOBODY READS MORE INTO A GREEN RUN
----------------------------------------------------------
This is detection, not prevention. A run is only as fresh as the moment it executes: a PR whose
last CI run predates a competing merge, and which then merges with no later run, is invisible to
this gate as it is to any run-time check. Closing that needs a merge queue or up-to-date-branch
enforcement, and the repo has deliberately chosen detection (ADR-0048). What this buys is that a
stale claim goes **red on the next run** instead of merging silently — today all four collisions
are green, so nothing anywhere disagrees with them.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

OPENAPI_GLOB = re.compile(r"^(openbank-[^/]+)/src/main/resources/openapi\.yaml$")
VERSION_RE = re.compile(r"^\s{2}version:\s*(.+?)\s*$", re.MULTILINE)


def sh(*args: str, check: bool = True) -> str:
    proc = subprocess.run(args, capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} failed ({proc.returncode}): {proc.stderr.strip()}")
    return proc.stdout


def info_version(text: str) -> str | None:
    """First two-space-indented `version:` — the one under `info:`.

    Deliberately not a YAML parse: a spec that fails to load is the contract gate's problem, and
    this gate must not turn an unrelated syntax error into a version verdict.
    """
    m = VERSION_RE.search(text)
    return m.group(1).strip().strip('"').strip("'") if m else None


def parse_semver(raw: str | None) -> tuple[int, int, int] | None:
    if not raw:
        return None
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", raw)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def file_at(ref: str, path: str) -> str | None:
    proc = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True, text=True)
    return proc.stdout if proc.returncode == 0 else None


def evaluate(head_raw: str | None, base_tip_raw: str | None) -> tuple[bool, str]:
    """Return (is_violation, explanation). Pure, so the self-test can drive it directly."""
    head_v = parse_semver(head_raw)
    tip_v = parse_semver(base_tip_raw)
    if head_v is None:
        return False, f"unparseable head version {head_raw!r} — the contract gate owns that"
    if tip_v is None:
        return False, f"no comparable version on the base tip ({base_tip_raw!r}) — new spec"
    if head_v > tip_v:
        return False, f"{head_raw} is ahead of the base tip's {base_tip_raw}"
    if head_v == tip_v:
        return True, f"{head_raw} is ALREADY on the base branch — that number is taken"
    return True, f"{head_raw} is BEHIND the base tip's {base_tip_raw} — the bump was overtaken"


SELF_TEST_CASES = [
    # (head, base_tip, must_be_violation, label)
    ("1.43.0", "1.42.0", False, "ordinary minor bump ahead of the tip"),
    ("2.0.0", "1.42.0", False, "major bump"),
    ("1.43.0", "1.43.0", True, "the real 2026-08-30 collision: number already shipped"),
    ("1.42.0", "1.43.0", True, "bump overtaken by a larger release"),
    ("1.43.0", None, False, "no spec on the base tip yet — a new service"),
    (None, "1.43.0", False, "unparseable head — not this gate's verdict to give"),
    ("1.43.1", "1.43.0", False, "patch bump"),
    ("1.44.0", "1.43.0", False, "the fix for the collision"),
]


def self_test() -> int:
    """Every case must be able to fail. A gate whose self-test only exercises the passing
    direction cannot detect its own vacuity — the repo has shipped several of those."""
    failures = 0
    for head, tip, want, label in SELF_TEST_CASES:
        got, why = evaluate(head, tip)
        status = "ok " if got == want else "FAIL"
        if got != want:
            failures += 1
        print(f"  {status} {label}: head={head!r} tip={tip!r} violation={got} ({why})")
    # Negative control on the control itself: if `evaluate` were stubbed to always return False,
    # the three must-violate cases above would flip. Assert at least one case of each polarity
    # exists, so a future edit cannot leave an all-passing suite that proves nothing.
    if not any(c[2] for c in SELF_TEST_CASES) or not all(
        any(c[2] is p for c in SELF_TEST_CASES) for p in (True, False)
    ):
        print("  FAIL self-test has no must-violate case — it could not detect a dead gate")
        failures += 1
    print(f"self-test: {'ok' if failures == 0 else 'FAILED'} ({failures} failure(s))")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", help="merge-base sha, used only to list which specs the PR touches")
    ap.add_argument("--base-ref", default="origin/main", help="base branch TIP to compare against")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if not args.base:
        # Refuse rather than pass: an empty base would report a clean sweep of nothing, which is
        # the exact shape this repo keeps rediscovering in its own gates.
        print("::error::--base is empty but this gate requires it — refusing to run vacuously")
        return 1

    changed = [
        line
        for line in sh("git", "diff", "--name-only", args.base, "HEAD").splitlines()
        if OPENAPI_GLOB.match(line)
    ]
    if not changed:
        print("openapi-version-not-taken: no openapi.yaml changed — nothing to check.")
        return 0

    level = "error" if args.enforce else "warning"
    findings: list[str] = []
    print(f"openapi-version-not-taken: {len(changed)} spec(s) changed, base tip = {args.base_ref}")

    for rel in changed:
        head_path = Path(rel)
        if not head_path.is_file():
            continue  # deletion; service removal is reviewed elsewhere
        head_raw = info_version(head_path.read_text(encoding="utf-8", errors="replace"))
        tip_text = file_at(args.base_ref, rel)
        tip_raw = info_version(tip_text) if tip_text is not None else None

        violation, why = evaluate(head_raw, tip_raw)
        print(f"  {'FAIL' if violation else 'ok  '} {rel}: {why}")
        if violation:
            svc = OPENAPI_GLOB.match(rel).group(1)
            nxt = parse_semver(tip_raw)
            suggestion = f"{nxt[0]}.{nxt[1] + 1}.0" if nxt else "the next free version"
            findings.append(
                f"{rel}: info.version {head_raw} is not free on {args.base_ref} "
                f"(it holds {tip_raw}). Re-bump off live main — likely {suggestion}. "
                f"Read it with: git show {args.base_ref}:{svc}/src/main/resources/openapi.yaml "
                f"| grep -m1 '^  version:'"
            )

    print(f"SUBJECTS={len(changed)}")
    for f in findings:
        print(f"::{level}::openapi-version-not-taken: {f}")
    return 1 if (findings and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
