#!/usr/bin/env python3
"""Require authored docs to accompany production changes in runnable services.

Generated build facts cover versions and provenance. They cannot explain a new
business rule, endpoint, or operation. This PR gate makes that editorial step
visible for every service, including newly added modules.
"""

import argparse
from pathlib import Path
import re
import subprocess
import sys


def changed_paths(repo: Path, base: str, head: str) -> dict[str, str]:
    result = subprocess.run(
        ["git", "diff", "--name-status", "-z", "--no-renames", "--diff-filter=ACDM", f"{base}...{head}", "--"],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    )
    entries = result.stdout.rstrip("\0").split("\0") if result.stdout else []
    return {path: status for status, path in zip(entries[::2], entries[1::2], strict=True)}


def authored_docs_at_head(repo: Path, head: str) -> set[str]:
    result = subprocess.run(
        ["git", "ls-tree", "-r", "-l", "-z", head, "--"],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    )
    docs = set()
    for entry in result.stdout.split("\0"):
        if not entry:
            continue
        metadata, path = entry.split("\t", 1)
        if path.endswith(".md") and int(metadata.rsplit(" ", 1)[-1]) > 0:
            docs.add(path)
    return docs


def runnable_modules_at_head(repo: Path, head: str) -> list[str]:
    paths = subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", "-z", head, "--"], cwd=repo, text=True,
    ).split("\0")
    modules = []
    for path in paths:
        if not re.fullmatch(r"openbank-[^/]+/build\.gradle\.kts", path):
            continue
        build = subprocess.check_output(["git", "show", f"{head}:{path}"], cwd=repo, text=True)
        if re.search(r'^\s*(?:plugins\s*\{\s*)?id\(["\']openbank\.quarkus-service["\']\)', build, re.MULTILINE) and (
            'project(":openbank-libs-runtime")' in build or 'project(":openbank-libs")' in build
        ):
            modules.append(path.split("/")[0])
    return sorted(modules)


def missing_updates(paths: dict[str, str], head_files: set[str], modules: list[str]) -> list[str]:
    failures = []
    for module in modules:
        prefix = f"{module}/"
        code_changed = any(
            p == f"{module}/build.gradle.kts"
            or (p.startswith(f"{prefix}src/main/") and not p.startswith(f"{prefix}src/main/resources/docs/"))
            for p in paths
        )
        docs_changed = any(
            p.startswith(f"{prefix}src/main/resources/docs/") and p.endswith(".md") and status in ("A", "M")
            for p, status in paths.items()
        )
        if code_changed and not docs_changed:
            failures.append(module)
        elif not any(
            path.startswith(f"{prefix}src/main/resources/docs/") and path.endswith(".md")
            for path in head_files
        ):
            failures.append(module)
    shared_sources = ("openbank-libs/", "openbank-libs-domain/", "openbank-libs-runtime/")
    shared_changed = any(
        p.startswith(f"{prefix}src/main/") or p == f"{prefix}build.gradle.kts"
        for prefix in shared_sources for p in paths
    )
    if shared_changed and not any(
        p.startswith("openbank-libs/docs/") and p.endswith(".md") and status in ("A", "M")
        for p, status in paths.items()
    ):
        failures.append("openbank-libs")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="PR base commit or ref")
    parser.add_argument("--head", required=True, help="Actual PR head commit, never the synthetic merge commit")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    try:
        failures = missing_updates(changed_paths(repo, args.base, args.head), authored_docs_at_head(repo, args.head), runnable_modules_at_head(repo, args.head))
    except subprocess.CalledProcessError as exc:
        print(f"Cannot compare service documentation with {args.base}: {exc}", file=sys.stderr)
        return 2
    if failures:
        for module in failures:
            docs = "openbank-libs/docs/*.md" if module == "openbank-libs" else f"{module}/src/main/resources/docs/*.md"
            print(f"{module}: missing authored documentation or production inputs changed without {docs}", file=sys.stderr)
        return 1
    print("Service documentation accompanies all changed production modules.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
