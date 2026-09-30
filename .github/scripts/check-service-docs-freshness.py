#!/usr/bin/env python3
"""Require authored docs to accompany production changes in runnable services.

Generated build facts cover versions and provenance. They cannot explain a new
business rule, endpoint, or operation. This PR gate makes that editorial step
visible for every service, including newly added modules.
"""

import argparse
from pathlib import Path
import subprocess
import sys


def changed_paths(base: str) -> set[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMR", f"{base}...HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return set(result.stdout.splitlines())


def missing_updates(repo: Path, paths: set[str]) -> list[str]:
    failures = []
    modules = sorted(
        path.parent.name
        for path in repo.glob("openbank-*/build.gradle.kts")
        if "openbank.quarkus-service" in path.read_text()
        and ("project(\":openbank-libs-runtime\")" in path.read_text() or "project(\":openbank-libs\")" in path.read_text())
    )
    for module in modules:
        prefix = f"{module}/"
        code_changed = any(
            p == f"{module}/build.gradle.kts"
            or (p.startswith(f"{prefix}src/main/") and not p.startswith(f"{prefix}src/main/resources/docs/"))
            for p in paths
        )
        docs_changed = any(p.startswith(f"{prefix}src/main/resources/docs/") and p.endswith(".md") for p in paths)
        if code_changed and not docs_changed:
            failures.append(module)
    shared_sources = ("openbank-libs/", "openbank-libs-domain/", "openbank-libs-runtime/")
    shared_changed = any(
        p.startswith(f"{prefix}src/main/") or p == f"{prefix}build.gradle.kts"
        for prefix in shared_sources for p in paths
    )
    if shared_changed and not any(p.startswith("openbank-libs/docs/") and p.endswith(".md") for p in paths):
        failures.append("openbank-libs")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="PR base commit or ref")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    try:
        failures = missing_updates(repo, changed_paths(args.base))
    except subprocess.CalledProcessError as exc:
        print(f"Cannot compare service documentation with {args.base}: {exc}", file=sys.stderr)
        return 2
    if failures:
        for module in failures:
            docs = "openbank-libs/docs/*.md" if module == "openbank-libs" else f"{module}/src/main/resources/docs/*.md"
            print(f"{module}: production inputs changed without {docs}", file=sys.stderr)
        return 1
    print("Service documentation accompanies all changed production modules.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
