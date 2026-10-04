#!/usr/bin/env python3
"""Run independent governance test discovery roots concurrently, keeping every verdict."""

from __future__ import annotations

import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor


SUITES = (
    ("infra", (sys.executable, "-m", "unittest", "discover", "-s", "openbank-infra/scripts", "-p", "*_test.py", "-v")),
    ("github", (sys.executable, "-m", "unittest", "discover", "-s", ".github/scripts", "-p", "*_test.py", "-v")),
    ("github-tests", (sys.executable, "-m", "unittest", "discover", "-s", ".github/scripts/tests", "-p", "test_*.py", "-v")),
)


def run_suites(suites=SUITES) -> int:
    if not suites:
        raise ValueError("at least one test discovery root is required")

    def run_one(suite):
        name, command = suite
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        return name, result

    with ThreadPoolExecutor(max_workers=len(suites)) as pool:
        results = list(pool.map(run_one, suites))

    failed = False
    for name, result in results:
        print(f"=== governance test suite: {name} (exit {result.returncode}) ===", flush=True)
        print(result.stdout, end="", flush=True)
        failed |= result.returncode != 0
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(run_suites())
