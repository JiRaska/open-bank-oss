#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Classify a complete PR diff for the narrowly scoped Admin UI Pact run.

Any missing, empty, malformed, ambiguous, or out-of-scope diff selects the full JVM run.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys


FULL = "full"
UI_ONLY = "ui-only"


def classify(repo: Path, base: str, head: str) -> tuple[str, str]:
    try:
        ancestry = subprocess.run(
            ["git", "merge-base", "--is-ancestor", base, head],
            cwd=repo,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        return FULL, f"could not validate diff ancestry ({type(exc).__name__})"
    if ancestry.returncode != 0:
        return FULL, "base is not an ancestor of head"

    try:
        result = subprocess.run(
            [
                "git", "diff", "--name-status", "--find-renames", "--find-copies",
                "--find-copies-harder", "-z", f"{base}...{head}",
            ],
            cwd=repo,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        return FULL, f"could not read complete diff ({type(exc).__name__})"

    fields = result.stdout.split(b"\0")
    if fields and fields[-1] == b"":
        fields.pop()
    if not fields:
        return FULL, "empty diff"

    paths: list[bytes] = []
    index = 0
    while index < len(fields):
        status = fields[index]
        index += 1
        if status in {b"A", b"M", b"D"}:
            if index >= len(fields):
                return FULL, "malformed diff status record"
            paths.append(fields[index])
            index += 1
            continue
        # Rename/copy records carry two paths; all other statuses (including type changes,
        # unmerged paths, and unknown future status forms) are ambiguous for this optimization.
        if status.startswith((b"R", b"C")):
            return FULL, "rename/copy in diff"
        return FULL, "unsupported diff status"

    try:
        decoded = [path.decode("utf-8", errors="strict") for path in paths]
    except UnicodeDecodeError:
        return FULL, "non-UTF-8 path in diff"

    if not decoded:
        return FULL, "empty path set"
    if any(
        not path.startswith("openbank-admin-ui/")
        or path.startswith("/")
        or "\\" in path
        or ".." in path.split("/")
        for path in decoded
    ):
        return FULL, "diff contains a path outside the Admin UI tree"
    return UI_ONLY, "all changed paths are under openbank-admin-ui/"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", type=Path, help="GitHub Actions output file")
    args = parser.parse_args()

    mode, reason = classify(args.repo, args.base, args.head)
    print(f"Pact regeneration scope: {mode} ({reason})")
    if args.output:
        with args.output.open("a", encoding="utf-8") as output:
            output.write(f"mode={mode}\n")
            shards = '["ui"]' if mode == UI_ONLY else '["0", "1", "2", "3"]'
            output.write(f"shards={shards}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
