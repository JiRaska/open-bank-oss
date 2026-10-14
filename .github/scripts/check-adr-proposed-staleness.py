#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A `decision-status: proposed` ADR must not sit undecided forever.

WHY THIS EXISTS
---------------
The 2026-10 fleet audit counted 84 `proposed` ADRs (309 total). A proposed ADR is a
promise that a decision is still open — but nothing distinguishes "open because it is
genuinely awaiting work" from "open because it was written once and everyone forgot".
`decision-status` is front-matter prose: it is read once, at authoring time, and after
that only by whoever goes looking.

WHY THE CLOCK IS creation-age + last-reviewed, NOT last commit
--------------------------------------------------------------
The obvious staleness signal — `git log -1` on the ADR file — is structurally broken in
THIS repo, measured at authoring time: weekly mechanical docs sweeps touch ADR files
fleet-wide, so every proposed ADR's last-commit date was <= 86 days old even though the
oldest had been proposed for 110+ days. A sweep commit is not a re-affirmation; a clock
that mechanical edits reset can never fire. So the clock is:

  * CREATION date (`git log --diff-filter=A`): when the proposal started being open, and
  * an optional `last-reviewed: YYYY-MM-DD` front-matter key: the ONLY deliberate
    re-affirmation knob — a human decides "still intentionally proposed" and writes a
    date mechanical sweeps do not touch. Same shape as check-advisory-finding-staleness
    .py's `verified:` field.

Stale when `today - max(creation, last-reviewed) > MAX_AGE_DAYS` (90 — one quarter).
Resolution paths: accept/supersede/reject the ADR (leaves the proposed pool), set
`last-reviewed`, or shrink-only-baseline the ADR below.

ADVISORY FIRST (ADR-0144 gate-graduation): the first live run flags the genuine ~3-month
tail; each finding then takes one of those three exits. Undated files (no reachable git
history) are skipped with a warning — a permission- or shallow-clone-shaped absence is
not a pass.

WHAT "TODAY" MEANS WITHOUT A CLOCK
----------------------------------
Same rule as check-advisory-finding-staleness.py: `--today` is an explicit ISO date the
caller supplies (`$(date -u +%F)`), never a bare `date.today()` — a self-test must
produce the same verdict every time it runs, in six months as much as today.

Usage:  check-adr-proposed-staleness.py --root . --today YYYY-MM-DD [--max-age-days N] [--enforce]
        check-adr-proposed-staleness.py --self-test
"""

from __future__ import annotations

import argparse
import datetime
import itertools
import pathlib
import subprocess
import sys
import tempfile

MAX_AGE_DAYS_DEFAULT = 90
ADR_DIR = "docs/adr"
STALE_RULE = "proposed"
LAST_REVIEWED_KEY = "last-reviewed"

FRONT_MATTER_DELIMITER = "---"
FRONT_MATTER_MAX_LINES = 60
IGNORED_FILES = {"README.md", "CURRENT.md", "DIGEST.md", "SCHEMA.md", "TEMPLATE.md"}


def front_matter(adr_path: pathlib.Path) -> dict[str, str]:
    """The (trimmed) front-matter key/value pairs, or {} when the file has no front matter.

    Bounded to the first FRONT_MATTER_MAX_LINES lines via itertools.islice — no tell()
    here: mixing next() iteration with tell() in text mode raises OSError ("telling
    position disabled"), and this function's OSError fallback would then silently report
    no front matter for every well-formed ADR.
    """
    values: dict[str, str] = {}
    try:
        with adr_path.open(encoding="utf-8") as handle:
            if handle.readline().rstrip("\n") != FRONT_MATTER_DELIMITER:
                return values
            for line in itertools.islice(handle, FRONT_MATTER_MAX_LINES):
                if line.rstrip("\n") == FRONT_MATTER_DELIMITER:
                    break
                key, _, value = line.partition(":")
                if key.strip():
                    values[key.strip()] = value.strip()
    except OSError:
        return {}
    return values


def first_commit_date(root: pathlib.Path, adr_path: pathlib.Path) -> datetime.date | None:
    """Creation date (committer date of the ADDING commit), or None when git cannot say.

    None means "skip", never "fresh": a file with no reachable history (untracked, or a
    git error) must not be flagged as stale by a gate that simply failed to read the clock.
    """
    relative = adr_path.relative_to(root)
    result = subprocess.run(
        ["git", "-C", str(root), "log", "--diff-filter=A", "--format=%cs", "--", str(relative)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0 or not result.stdout.strip():
        return None
    try:
        return datetime.date.fromisoformat(result.stdout.strip().splitlines()[-1])
    except ValueError:
        return None


def reviewed_date(meta: dict[str, str]) -> datetime.date | None:
    """The optional `last-reviewed` re-affirmation date; absent or malformed is None."""
    raw = meta.get(LAST_REVIEWED_KEY)
    if not raw:
        return None
    try:
        return datetime.date.fromisoformat(raw)
    except ValueError:
        return None


def find_stale(
    root: pathlib.Path,
    today: datetime.date,
    max_age_days: int,
) -> tuple[list[str], int]:
    """(stale ADR file names, number of proposed ADRs git could not date)."""
    adr_root = root / ADR_DIR
    stale: list[str] = []
    undated = 0
    if not adr_root.is_dir():
        return stale, undated
    for adr_path in sorted(adr_root.glob("*.md")):
        if adr_path.name in IGNORED_FILES:
            continue
        meta = front_matter(adr_path)
        if meta.get("decision-status") != STALE_RULE:
            continue
        created = first_commit_date(root, adr_path)
        if created is None:
            undated += 1
            continue
        effective = max(created, reviewed_date(meta) or created)
        if (today - effective).days > max_age_days:
            stale.append(adr_path.name)
    return stale, undated


def run(root: pathlib.Path, today: datetime.date, max_age_days: int, enforce: bool) -> int:
    stale, undated = find_stale(root, today, max_age_days)
    annotation = "error" if enforce else "warning"
    for name in stale:
        print(f"::{annotation}:: {ADR_DIR}/{name} is `decision-status: proposed` for more than "
              f"{max_age_days} days with no `last-reviewed` re-affirmation — decide it, "
              f"re-affirm it, or baseline it explicitly.")
    if undated:
        print(f"::{annotation}:: {undated} proposed ADR(s) could not be dated from git history "
              f"and were skipped — a permission- or shallow-clone-shaped absence, not a pass.")
    if stale:
        print(f"adr-proposed-staleness: {len(stale)} proposed ADR(s) stale "
              f"(>{max_age_days}d undecided), {undated} undated")
        return 1 if enforce else 0
    print(f"adr-proposed-staleness: 0 stale proposed ADRs, {undated} undated")
    return 0


def self_test() -> int:
    """Fabricated git history in a temp repo — deterministic because every commit date is
    pinned via GIT_AUTHOR_DATE/GIT_COMMITTER_DATE and --today is a fixed constant."""
    fails: list[str] = []
    today = datetime.date(2026, 10, 14)
    created = today - datetime.timedelta(days=120)

    def adr(status: str, extra_front_matter: str = "") -> str:
        return (
            "---\ndate: 2026-01-01\n"
            f"decision-status: {status}\n"
            f"{extra_front_matter}"
            "delivery-status: partial\nauthors: [test]\n---\n\n# body\n"
        )

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        adr_dir = root / ADR_DIR
        adr_dir.mkdir(parents=True)
        fixtures = [
            # (name, status, committed_days_ago, extra front matter)
            ("0001-stale-proposed.md", "proposed", 120, ""),
            ("0002-fresh-proposed.md", "proposed", 10, ""),
            ("0003-reviewed-proposed.md", "proposed", 120, "last-reviewed: 2026-10-01\n"),
            ("0004-old-accepted.md", "accepted", 120, ""),
            ("TEMPLATE.md", "proposed", 120, ""),  # template file must be ignored
        ]
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        subprocess.run(
            ["git", "-C", str(root), "config", "user.email", "gate-selftest@openbank.local"],
            check=True,
        )
        subprocess.run(
            ["git", "-C", str(root), "config", "user.name", "gate-selftest"],
            check=True,
        )
        import os

        for name, status, days_ago, extra in fixtures:
            (adr_dir / name).write_text(adr(status=status, extra_front_matter=extra),
                                        encoding="utf-8")
            commit_date = today - datetime.timedelta(days=days_ago)
            env = {
                **os.environ,
                "GIT_AUTHOR_DATE": f"{commit_date}T00:00:00",
                "GIT_COMMITTER_DATE": f"{commit_date}T00:00:00",
            }
            subprocess.run(["git", "-C", str(root), "add", "."], check=True, env=env)
            subprocess.run(["git", "-C", str(root), "commit", "-q", "-m", f"add {name}"],
                           check=True, env=env)
        # One proposed ADR with NO commit at all — the undated/skip path.
        (adr_dir / "0006-uncommitted-proposed.md").write_text(
            adr(status="proposed"), encoding="utf-8")

        stale, undated = find_stale(root, today, MAX_AGE_DAYS_DEFAULT)
        if "0001-stale-proposed.md" not in stale:
            fails.append("a proposed ADR 120 days undecided was NOT flagged (RED unreachable)")
        for name in ("0002-fresh-proposed.md", "0003-reviewed-proposed.md",
                     "0004-old-accepted.md", "TEMPLATE.md"):
            if name in stale:
                fails.append(f"{name} flagged despite being fresh, reviewed, accepted, or the template")
        if undated != 1:
            fails.append(f"expected exactly 1 undated proposed ADR, got {undated}")
        if run(root, today, MAX_AGE_DAYS_DEFAULT, enforce=True) == 0:
            fails.append("--enforce on a repo with a stale ADR exited 0")
        if run(root, today, MAX_AGE_DAYS_DEFAULT, enforce=False) != 0:
            fails.append("advisory run (no --enforce) exited non-zero")

    if fails:
        for fail in fails:
            sys.stderr.write(f"self-test FAILED: {fail}\n")
        return 1
    print("self-test OK")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--today", help="explicit ISO date (YYYY-MM-DD); CI passes $(date -u +%F)")
    parser.add_argument("--max-age-days", type=int, default=MAX_AGE_DAYS_DEFAULT)
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if not args.today:
        parser.error("--today is required (pass $(date -u +%F)) — a bare date.today() would "
                     "make the self-test verdict drift with wall time")
    try:
        today = datetime.date.fromisoformat(args.today)
    except ValueError:
        parser.error(f"--today must be YYYY-MM-DD, got {args.today!r}")
    return run(pathlib.Path(args.root), today, args.max_age_days, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
